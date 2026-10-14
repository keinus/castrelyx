"""Equal-update baseline vs normal-sequence auxiliary training, then identical SFT."""
import argparse,copy,json,time,random
import numpy as np
import torch
from sklearn.metrics import average_precision_score
from common_v3 import ROOT,LAB,read,load,collate,forward,encoded,export_v3,sha
SEED=20261005
def auxiliary(train):
    normal=[r for r in train if not r['y'] and not r['provenance']['context_has_attack'] and len(r['state']['events'])>=2]
    if len(normal)<20:raise ValueError(f'Insufficient clean normal states: {len(normal)}')
    rng=random.Random(SEED);out=[]
    for i in range(len(train)):
        r=normal[i%len(normal)];events=copy.deepcopy(r['state']['events']);gold=events.pop();bad=i%2
        def differs(v):
            return any(v['state']['events'][-1][k]!=value for k,value in gold.items() if k not in ('age_s','peer'))
        candidates=[v for v in normal if v['group']!=r['group'] and v['state']['events'][-1]['proto']==gold['proto'] and differs(v)]
        if not candidates:candidates=[v for v in normal if v['group']!=r['group'] and differs(v)]
        if not candidates:raise ValueError('No distinguishable normal continuation donor')
        donor_row=rng.choice(candidates);donor=copy.deepcopy(donor_row['state']['events'][-1]);donor['age_s']=gold['age_s'];donor['peer']=gold['peer']
        if (i//2)%2==0:
            # Mask the final service tuple; contrast recovery with another real normal service.
            fields=('port','proto','service');target={k:gold[k] for k in fields}
            alternatives=[v for v in normal if any(v['state']['events'][-1][k]!=gold[k] for k in fields)]
            donor_row=rng.choice(alternatives) if bad else r
            candidate={k:donor_row['state']['events'][-1][k] for k in fields}
            masked={k:v for k,v in gold.items() if k not in fields}
            st={'earlier_events':events,'masked_final_flow':masked,'candidate_service':candidate}
            instruction='Was the proposed service tuple substituted for the original final flow? Infer the missing service from the normal sequence and remaining fields. True means a substituted candidate; false means the original.'
            task='masked_service_recovery'
        else:
            st={'earlier_events':events,'candidate_next_flow':donor if bad else gold}
            instruction='Was the candidate continuation substituted from another host-time sequence rather than the observed continuation of this normal sequence? Use traffic semantics and relationships. True means substituted; false means original.'
            task='normal_continuation'
        out.append({'id':f"aux-{i}-{r['id']}",'group':r['group'],'state':st,'y':bad,'question':{'t':'noul','crit':None,'ins':instruction},'aux_task':task,'source_case':r['id'],'donor_case':donor_row['id'] if bad else r['id']})
    rng.shuffle(out);return out,len(normal)
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['sft','normal_relation'],required=True);args=ap.parse_args()
    random.seed(SEED);np.random.seed(SEED);torch.manual_seed(SEED);torch.set_num_threads(4)
    train=read('train');valsets={k:read(k) for k in ['validation','proxy_validation']};model,tok,cfg=load(LAB/'models/base','cuda:0',1024)
    for p in model.act_head.parameters():p.requires_grad_(False)
    model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.head_checkpointing=True
    items=encoded(train,tok,cfg);vals={s:encoded(r,tok,cfg) for s,r in valsets.items()}
    aux,clean_count=auxiliary(train);warm=encoded(aux,tok,cfg) if args.mode=='normal_relation' else items
    out=ROOT/f'models/laya_v3_{args.mode}';history=[];started=time.time();step=0;best=-1
    weights=np.array([1/sum(z['y']==r['y'] for z in train) for r in train]);weights/=weights.sum()
    print(json.dumps({'mode':args.mode,'train':len(train),'clean_normal_for_aux':clean_count,'auxiliary':len(aux),'max_tokens':max(len(x['ids']) for x in items+warm)}),flush=True)
    (ROOT/'runs/auxiliary_provenance.json').write_text(json.dumps({'clean_normal_cases':clean_count,'training_sha256':sha(ROOT/'data/train.jsonl'),'cases':[{'id':r['id'],'source_case':r['source_case'],'donor_case':r['donor_case'],'task':r['aux_task'],'target':r['y']} for r in aux]}))
    for phase,epochs,pool in [('warmup',1,warm),('supervised',3,items)]:
        optimizer=torch.optim.AdamW([{'params':[p for n,p in model.named_parameters() if n.startswith('encoder.') and p.requires_grad],'lr':8e-6},
                                   {'params':[p for n,p in model.named_parameters() if not n.startswith('encoder.') and p.requires_grad],'lr':3e-5}],weight_decay=.01)
        scheduler=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,int(np.ceil(len(pool)/32))*epochs,eta_min=1e-6)
        for epoch in range(epochs):
            rng=np.random.default_rng(SEED+epoch+(100 if phase=='warmup' else 0))
            indices=rng.permutation(len(pool)) if phase=='warmup' and args.mode=='normal_relation' else rng.choice(len(pool),len(pool),p=weights)
            sample=[pool[i] for i in indices];model.train();optimizer.zero_grad(set_to_none=True);total=0
            for start in range(0,len(sample),8):
                chunk=sample[start:start+8];batch=collate(chunk,tok.pad_token_id,'cuda:0');z=forward(model,batch,'cuda:0')
                loss=-(batch[-1]*torch.log_softmax(z,-1)).sum(-1).mean();n=min(32,len(sample)-(start//32)*32)
                if not torch.isfinite(loss):raise ValueError('Nonfinite loss')
                (loss*len(chunk)/n).backward();total+=float(loss.detach())*len(chunk)
                if (start//8+1)%4==0 or start+8>=len(sample):
                    torch.nn.utils.clip_grad_norm_(model.parameters(),1,error_if_nonfinite=True);optimizer.step();scheduler.step();optimizer.zero_grad(set_to_none=True);step+=1
            entry={'phase':phase,'epoch':epoch+1,'loss':total/len(sample),'steps':step,'seconds':time.time()-started}
            if phase=='supervised':
                model.eval();aps={}
                with torch.inference_mode():
                    for split,x in vals.items():
                        scores=[]
                        for j in range(0,len(x),16):
                            z=forward(model,collate(x[j:j+16],tok.pad_token_id,'cuda:0'),'cuda:0');scores.extend((z[:,1]-z[:,0]).cpu().tolist())
                        aps[split]=float(average_precision_score([r['y'] for r in valsets[split]],scores))
                entry['validation_auprc']=aps;entry['selection_score']=float(np.mean(list(aps.values())))
                if entry['selection_score']>best:
                    best=entry['selection_score'];export_v3(model,tok,cfg,out,{'mode':args.mode,'seed':SEED,'selected_epoch':epoch+1,'steps':step,'training_sha256':sha(ROOT/'data/train.jsonl'),'normal_aux_count':clean_count,'task':'binary Zeek-window detection','equal_update_budget':True,'selection':entry})
            history.append(entry);print(json.dumps(entry),flush=True);(ROOT/f'runs/laya_{args.mode}_history.json').write_text(json.dumps(history,indent=2))
    result={'status':'complete','mode':args.mode,'steps':step,'seconds':time.time()-started,'weights_sha256':sha(out/'model.safetensors'),'best_validation_score':best}
    (ROOT/f'runs/laya_{args.mode}_complete.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
if __name__=='__main__':main()
