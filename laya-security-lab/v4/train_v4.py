"""Train identical cohorts with Laya, constrained Qwen, XGBoost, RF and GRU."""
import argparse,json,random,time,math
import numpy as np
import torch
from torch import nn
from sklearn.metrics import average_precision_score
from common_v4 import ROOT,LAB,VIEWS,load_rows,view_state,sequence_array,tabular_array,output_dir,torch_bindings,encode_laya,qwen_text,QUESTION
from acquire import digest

class GRUDetector(nn.Module):
    def __init__(self):
        super().__init__();self.gru=nn.GRU(15,64,batch_first=True);self.head=nn.Sequential(nn.Linear(71,32),nn.ReLU(),nn.Linear(32,2))
    def forward(self,x,lengths,summary):
        packed=nn.utils.rnn.pack_padded_sequence(x,lengths.cpu(),batch_first=True,enforce_sorted=False)
        _,hidden=self.gru(packed);return self.head(torch.cat([hidden[-1],summary],dim=1))

def gru_batch(rows,view,mean,std,device):
    states=[view_state(r['state'],view) for r in rows];seq=[(sequence_array(s,max_events=1000)-mean)/std for s in states]
    lengths=torch.tensor([len(s) for s in seq]);x=torch.zeros(len(seq),int(lengths.max()),15)
    for i,s in enumerate(seq):x[i,:len(s)]=torch.from_numpy(s)
    summary=np.stack([np.log1p(np.maximum(0,np.asarray(s.get('summary',[0]*7),dtype=np.float32))) for s in states])
    return x.to(device),lengths,torch.from_numpy(summary).to(device)

def train(args):
    random.seed(args.seed);np.random.seed(args.seed);torch.manual_seed(args.seed);torch.set_num_threads(4)
    folder=output_dir(args.experiment,args.view,args.model,args.seed)
    if folder.exists():raise FileExistsError('Training artifact exists; use another experiment or seed')
    folder.mkdir(parents=True)
    train=load_rows(args.experiment,'train');val=load_rows(args.experiment,'validation')
    if {r['group'] for r in train}&{r['group'] for r in val}:raise ValueError('Train/validation group leakage')
    manifest=json.loads((ROOT/'data/experiments'/args.experiment/'manifest.json').read_text())
    max_events=manifest['context_events']+1;device=args.device;started=time.time();history=[];best=-math.inf
    counts=np.bincount([r['y'] for r in train],minlength=2)
    if min(counts)==0:raise ValueError('Both classes required')
    metadata={'model':args.model,'view':args.view,'seed':args.seed,'experiment':args.experiment,'context_events':manifest['context_events'],
        'train_sha256':manifest['counts']['train']['sha256'],'validation_sha256':manifest['counts']['validation']['sha256'],
        'selection':'highest validation average precision; no calibration/test used for model selection',
        'pilot':manifest['pilot'],'epochs_budget':args.epochs,'history':history,'train_class_counts':counts.tolist(),
        'contract':'current-flow binary label; context is previous completed flows, network only',
        'hardware':torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU'}
    if args.model in ('rf','xgboost'):
        import joblib
        x=np.stack([tabular_array(view_state(r['state'],args.view),max_events) for r in train]);y=np.array([r['y'] for r in train])
        xv=np.stack([tabular_array(view_state(r['state'],args.view),max_events) for r in val])
        if args.model=='rf':
            from sklearn.ensemble import RandomForestClassifier
            model=RandomForestClassifier(n_estimators=300,min_samples_leaf=2,max_features=.8,class_weight='balanced',n_jobs=4,random_state=args.seed)
        else:
            from xgboost import XGBClassifier
            model=XGBClassifier(n_estimators=300,max_depth=5,learning_rate=.05,subsample=.8,colsample_bytree=.8,
                tree_method='hist',n_jobs=4,random_state=args.seed,scale_pos_weight=float(counts[0]/counts[1]),eval_metric='logloss')
        model.fit(x,y);score=average_precision_score([r['y'] for r in val],model.predict_proba(xv)[:,1]);joblib.dump(model,folder/'model.joblib')
        history.append({'validation_ap':float(score)});metadata['weights_sha256']=digest(folder/'model.joblib')
    else:
        loss_weight=torch.tensor(len(train)/(2*counts),dtype=torch.float32,device=device)
        if args.model=='laya':
            load,collate,forward,export,_,_=torch_bindings();model,tok,cfg=load(LAB/'models/base',device,1536)
            for p in model.act_head.parameters():p.requires_grad_(False)
            model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.head_checkpointing=True
            items=encode_laya(train,tok,cfg,args.view);vitems=encode_laya(val,tok,cfg,args.view)
            metadata['maximum_tokens']=max(len(r['ids']) for r in items+vitems)
            metadata['base_weights_sha256']=digest(LAB/'models/base/model.safetensors')
            optimizer=torch.optim.AdamW([{'params':[p for n,p in model.named_parameters() if n.startswith('encoder.') and p.requires_grad],'lr':8e-6},
                {'params':[p for n,p in model.named_parameters() if not n.startswith('encoder.') and p.requires_grad],'lr':3e-5}],weight_decay=.01)
            def logits(indices,valid=False):
                pool=vitems if valid else items
                return forward(model,collate([pool[i] for i in indices],tok.pad_token_id,device),device)
            def save():
                export(model,tok,cfg,folder/'model',metadata)
                config=json.loads((folder/'model/rl_agent_config.json').read_text());config.update(model_name='laya-public-network-v4',security_schema='public-network-v4')
                (folder/'model/rl_agent_config.json').write_text(json.dumps(config,indent=2))
                (folder/'model/questions.json').write_text(json.dumps({'threat':QUESTION},indent=2))
        elif args.model=='qwen':
            from transformers import AutoModelForCausalLM,AutoTokenizer
            from peft import LoraConfig,get_peft_model
            base=LAB/'v2/models/qwen3-0.6b';tok=AutoTokenizer.from_pretrained(base,local_files_only=True);tok.padding_side='left'
            model=AutoModelForCausalLM.from_pretrained(base,torch_dtype=torch.bfloat16,attn_implementation='sdpa',local_files_only=True).to(device)
            model=get_peft_model(model,LoraConfig(r=16,lora_alpha=32,lora_dropout=.05,target_modules=['q_proj','k_proj','v_proj','o_proj','gate_proj','up_proj','down_proj'],task_type='CAUSAL_LM'))
            model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.config.use_cache=False
            items=[tok.encode(qwen_text(tok,r['state'],args.view),add_special_tokens=False) for r in train]
            vitems=[tok.encode(qwen_text(tok,r['state'],args.view),add_special_tokens=False) for r in val]
            metadata['maximum_tokens']=max(map(len,items+vitems))
            if metadata['maximum_tokens']>1536:raise ValueError('Refusing Qwen truncation')
            letters=[tok.encode(v,add_special_tokens=False) for v in ('A','B')]
            if any(len(v)!=1 for v in letters):raise ValueError('Non-atomic answer token')
            letters=[v[0] for v in letters];metadata['base_revision']='c1899de289a04d12100db370d81485cdf75e47ca'
            optimizer=torch.optim.AdamW([p for p in model.parameters() if p.requires_grad],lr=1e-4,weight_decay=.01)
            def logits(indices,valid=False):
                pool=vitems if valid else items;x=tok.pad({'input_ids':[pool[i] for i in indices]},padding=True,return_tensors='pt').to(device)
                return model(**x,use_cache=False,logits_to_keep=1).logits[:,-1,letters].float()
            def save():model.save_pretrained(folder/'model');tok.save_pretrained(folder/'model')
        else:
            vectors=np.concatenate([sequence_array(view_state(r['state'],args.view),max_events) for r in train])
            mean=vectors.mean(0);std=np.maximum(vectors.std(0),.01);model=GRUDetector().to(device)
            optimizer=torch.optim.AdamW(model.parameters(),lr=1e-3,weight_decay=.01)
            def logits(indices,valid=False):
                pool=val if valid else train
                return model(*gru_batch([pool[i] for i in indices],args.view,mean,std,device))
            def save():torch.save({'state':model.state_dict(),'mean':torch.from_numpy(mean),'std':torch.from_numpy(std)},folder/'model.pt')
        effective=args.batch_size*args.accumulation;step=0
        scheduler=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,max(1,math.ceil(len(train)/effective)*args.epochs),eta_min=1e-6)
        for epoch in range(args.epochs):
            order=np.random.default_rng(args.seed+epoch).permutation(len(train));model.train();optimizer.zero_grad(set_to_none=True);total=0.
            for start in range(0,len(order),args.batch_size):
                indices=order[start:start+args.batch_size];z=logits(indices);y=torch.tensor([train[i]['y'] for i in indices],device=device)
                loss=nn.functional.cross_entropy(z,y,weight=loss_weight)
                if not torch.isfinite(loss):raise ValueError('Nonfinite training loss')
                group_size=min(effective,len(order)-(start//effective)*effective);(loss*len(indices)/group_size).backward();total+=float(loss.detach())*len(indices)
                if (start//args.batch_size+1)%args.accumulation==0 or start+args.batch_size>=len(order):
                    nn.utils.clip_grad_norm_(model.parameters(),1,error_if_nonfinite=True);optimizer.step();scheduler.step();optimizer.zero_grad(set_to_none=True);step+=1
            model.eval();raw=[]
            with torch.inference_mode():
                for start in range(0,len(val),args.batch_size):
                    z=logits(range(start,min(len(val),start+args.batch_size)),True);raw.extend((z[:,1]-z[:,0]).cpu().tolist())
            score=float(average_precision_score([r['y'] for r in val],raw))
            entry={'epoch':epoch+1,'steps':step,'loss':total/len(train),'validation_ap':score,'seconds':time.time()-started};history.append(entry)
            if score>best:best=score;metadata['selected_epoch']=epoch+1;save()
            (folder/'history.json').write_text(json.dumps(history,indent=2));print(json.dumps(dict(entry,model=args.model,view=args.view)),flush=True)
        weight=folder/('model/model.safetensors' if args.model=='laya' else 'model/adapter_model.safetensors' if args.model=='qwen' else 'model.pt')
        metadata['weights_sha256']=digest(weight)
    metadata.update(status='trained',seconds=time.time()-started)
    (folder/'training.json').write_text(json.dumps(metadata,indent=2));print(json.dumps({'trained':str(folder),'seconds':metadata['seconds']}),flush=True)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--experiment',required=True);ap.add_argument('--model',choices=['laya','qwen','rf','xgboost','gru'],required=True)
    ap.add_argument('--view',choices=VIEWS,default='sequence');ap.add_argument('--seed',type=int,default=20261005);ap.add_argument('--epochs',type=int,default=3)
    ap.add_argument('--batch-size',type=int,default=4);ap.add_argument('--accumulation',type=int,default=8);ap.add_argument('--device',default='cuda:0')
    args=ap.parse_args()
    if min(args.epochs,args.batch_size,args.accumulation)<1:raise ValueError('Positive training sizes required')
    train(args)
if __name__=='__main__':main()
