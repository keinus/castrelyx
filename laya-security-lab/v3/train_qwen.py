import json,random,time,gc
import numpy as np
import torch
from sklearn.metrics import average_precision_score
from transformers import AutoModelForCausalLM,AutoTokenizer
from peft import LoraConfig,get_peft_model
from common_v3 import ROOT,LAB,read,llm_text,sha

def main():
    seed=20261005;random.seed(seed);np.random.seed(seed);torch.manual_seed(seed);torch.set_num_threads(4)
    path=LAB/'v2/models/qwen3-0.6b';tok=AutoTokenizer.from_pretrained(path,local_files_only=True);tok.padding_side='left'
    model=AutoModelForCausalLM.from_pretrained(path,torch_dtype=torch.bfloat16,attn_implementation='sdpa',local_files_only=True).to('cuda:0')
    model=get_peft_model(model,LoraConfig(r=16,lora_alpha=32,lora_dropout=.05,target_modules=['q_proj','k_proj','v_proj','o_proj','gate_proj','up_proj','down_proj'],task_type='CAUSAL_LM'))
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.config.use_cache=False
    train=read('train');validation=[]
    for split in ['validation','proxy_validation']:
        for r in read(split):r['validation_group']=split;validation.append(r)
    for rows in (train,validation):
        for r in rows:
            r['ids']=tok.encode(llm_text(tok,r['state']),add_special_tokens=False)
            if len(r['ids'])>1536:raise ValueError('Input too long; no truncation permitted')
    letters=[tok.encode(x,add_special_tokens=False)[0] for x in ('A','B')]
    counts={key:sum((r['source'],r['y'])==key for r in train) for key in {(r['source'],r['y']) for r in train}}
    weights=np.array([1/counts[(r['source'],r['y'])] for r in train]);weights/=weights.sum()
    def batch(rows):
        x=tok.pad({'input_ids':[r['ids'] for r in rows]},padding=True,return_tensors='pt').to('cuda:0')
        return x,torch.tensor([r['y'] for r in rows],device='cuda:0')
    optimizer=torch.optim.AdamW([p for p in model.parameters() if p.requires_grad],lr=1e-4,weight_decay=.01)
    epochs=3;micro=4;accum=8;steps=0;started=time.time();best=-1;history=[]
    scheduler=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,int(np.ceil(len(train)/32))*epochs,eta_min=1e-5)
    print(json.dumps({'train':len(train),'trainable_params':sum(p.numel() for p in model.parameters() if p.requires_grad),'max_tokens':max(len(r['ids']) for r in train)}),flush=True)
    for epoch in range(epochs):
        idx=np.random.default_rng(seed+epoch).choice(len(train),len(train),p=weights);sample=[train[i] for i in idx]
        model.train();optimizer.zero_grad(set_to_none=True);total=0
        for start in range(0,len(sample),micro):
            chunk=sample[start:start+micro];x,y=batch(chunk)
            logits=model(**x,use_cache=False,logits_to_keep=1).logits[:,-1,letters].float();loss=torch.nn.functional.cross_entropy(logits,y)
            if not torch.isfinite(loss):raise RuntimeError('Non-finite Qwen loss')
            n=min(32,len(sample)-(start//32)*32);(loss*len(chunk)/n).backward();total+=float(loss)*len(chunk)
            if (start//micro+1)%accum==0 or start+micro>=len(sample):
                torch.nn.utils.clip_grad_norm_(model.parameters(),1,error_if_nonfinite=True);optimizer.step();scheduler.step();optimizer.zero_grad(set_to_none=True);steps+=1
                if steps%25==0:print(json.dumps({'epoch':epoch+1,'step':steps,'loss':total/(start+len(chunk)),'seconds':time.time()-started}),flush=True)
        model.eval();pred=[]
        with torch.inference_mode():
            for start in range(0,len(validation),8):
                x,y=batch(validation[start:start+8]);logits=model(**x,use_cache=False,logits_to_keep=1).logits[:,-1,letters].float();pred.extend((logits[:,1]-logits[:,0]).cpu().tolist())
        aps={s:float(average_precision_score([r['y'] for r in validation if r['validation_group']==s],[p for r,p in zip(validation,pred) if r['validation_group']==s])) for s in sorted({r['validation_group'] for r in validation})};score=float(np.mean(list(aps.values())))
        entry={'epoch':epoch+1,'loss':total/len(sample),'validation_auprc':score,'by_source':aps,'seconds':time.time()-started};history.append(entry);print(json.dumps(entry),flush=True)
        if score>best:
            best=score;output=ROOT/'models/qwen3_06b_v3_lora';model.save_pretrained(output);tok.save_pretrained(output)
            (output/'training_metadata.json').write_text(json.dumps({'seed':seed,'selected_epoch':epoch+1,'base_revision':'c1899de289a04d12100db370d81485cdf75e47ca','steps':steps,'training_data_sha256':sha(ROOT/'data/train.jsonl'),'history':history,'objective':'cross entropy of two constrained answer-token logits, no free-form rationale supervision'},indent=2))
        (ROOT/'runs/qwen_history.json').write_text(json.dumps(history,indent=2))
    result={'status':'complete','steps':steps,'seconds':time.time()-started,'best_validation_auprc':best,'adapter_sha256':sha(ROOT/'models/qwen3_06b_v3_lora/adapter_model.safetensors')}
    (ROOT/'runs/qwen_complete.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)

if __name__=='__main__':main()
