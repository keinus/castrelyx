from pathlib import Path
import json,random,time,gc
import numpy as np
import torch
from sklearn.metrics import average_precision_score
from common_v2 import ROOT,LAB,load,encoded,collate,forward,export,read,LayaDetector,sha

def finalize_metadata(output):
    path=output/'rl_agent_config.json';cfg=json.loads(path.read_text());meta=json.loads((output/'training_metadata.json').read_text())
    if 'upstream_training' not in cfg:cfg['upstream_training']=cfg.get('training',{})
    cfg.update(model_name='laya-security-v2-detector',security_schema='security-detector-v2',
               training={'selected_epoch':meta['selected_epoch'],'updates_at_selected_checkpoint':meta['steps'],
                         'objective':'binary threat evidence','details':'training_metadata.json'})
    path.write_text(json.dumps(cfg,indent=2))

def main():
    seed=20261005;random.seed(seed);np.random.seed(seed);torch.manual_seed(seed);torch.set_num_threads(4)
    train=read('train');validation=read('validation');model,tok,cfg=load(LAB/'models/base','cuda:0',1024)
    for p in model.act_head.parameters():p.requires_grad_(False)
    model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.head_checkpointing=True
    items=encoded(train,tok,cfg);val=encoded(validation,tok,cfg)
    # Balance source/label strata by sampling with replacement, without touching held-out data.
    counts={key:sum((r['source'],r['y'])==key for r in train) for key in {(r['source'],r['y']) for r in train}}
    weights=np.array([1/counts[(r['source'],r['y'])] for r in train]);weights/=weights.sum();original_items=list(items)
    # Test states may be checked for length, but test labels/predictions do not enter training.
    print(json.dumps({'train':len(items),'validation':len(val),'max_tokens':max(len(x['ids']) for x in items)}),flush=True)
    optimizer=torch.optim.AdamW([{'params':[p for n,p in model.named_parameters() if n.startswith('encoder.') and p.requires_grad],'lr':8e-6},
        {'params':[p for n,p in model.named_parameters() if not n.startswith('encoder.') and p.requires_grad],'lr':3e-5}],weight_decay=.01)
    epochs=3;micro=8;accum=4;updates=int(np.ceil(len(items)/(micro*accum)))*epochs
    sched=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,updates,eta_min=1e-6);best=-1;history=[];step=0;started=time.time()
    output=ROOT/'models/laya-v2';initial=model.scorer[-1].weight.detach().cpu().clone()
    for epoch in range(epochs):
        indices=np.random.default_rng(seed+epoch).choice(len(original_items),len(original_items),p=weights)
        items=[original_items[i] for i in indices];model.train();optimizer.zero_grad(set_to_none=True);total=0
        for start in range(0,len(items),micro):
            chunk=items[start:start+micro];batch=collate(chunk,tok.pad_token_id,'cuda:0');logits=forward(model,batch,'cuda:0')
            loss=-(batch[-1]*torch.log_softmax(logits,-1)).sum(-1).mean()
            if not torch.isfinite(loss):raise RuntimeError('Nonfinite loss')
            size=min(micro*accum,len(items)-(start//(micro*accum))*micro*accum)
            (loss*len(chunk)/size).backward();total+=float(loss)*len(chunk)
            if (start//micro+1)%accum==0 or start+micro>=len(items):
                torch.nn.utils.clip_grad_norm_(model.parameters(),1,error_if_nonfinite=True);optimizer.step();sched.step();optimizer.zero_grad(set_to_none=True);step+=1
                if step%25==0:print(json.dumps({'epoch':epoch+1,'step':step,'loss':total/(start+len(chunk)),'seconds':time.time()-started}),flush=True)
        model.eval();pred=[]
        with torch.inference_mode():
            for start in range(0,len(val),16):
                batch=collate(val[start:start+16],tok.pad_token_id,'cuda:0');logits=forward(model,batch,'cuda:0');pred.extend((logits[:,1]-logits[:,0]).cpu().tolist())
        by_source={s:float(average_precision_score([r['y'] for r in validation if r['source']==s],[p for r,p in zip(validation,pred) if r['source']==s])) for s in sorted({r['source'] for r in validation})}
        ap=float(np.mean(list(by_source.values())));entry={'epoch':epoch+1,'loss':total/len(items),'validation_auprc':float(ap),'by_source':by_source,'seconds':time.time()-started};history.append(entry);print(json.dumps(entry),flush=True)
        if ap>best:
            best=ap;export(model,tok,cfg,output,{'seed':seed,'selected_epoch':epoch+1,'steps':step,'base':'original Laya, not v1','base_sha256':sha(LAB/'models/base/model.safetensors'),'training_data_sha256':sha(ROOT/'data/train.jsonl'),'history':history,'weight_probe_l2':float((model.scorer[-1].weight.detach().cpu()-initial).norm()),'task':'binary attack evidence; no response-head evaluation'})
            # Override the inherited v1 question contract in the exported metadata.
            from common_v2 import QUESTION
            (output/'questions.json').write_text(json.dumps({'threat':QUESTION},indent=2))
            finalize_metadata(output)
        (ROOT/'runs/laya_history.json').write_text(json.dumps(history,indent=2))
    result={'status':'complete','steps':step,'seconds':time.time()-started,'selected_validation_auprc':best,'trained_sha256':sha(output/'model.safetensors')}
    (ROOT/'runs/laya_complete.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)

if __name__=='__main__':main()
