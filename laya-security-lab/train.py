"""Full encoder+head supervised fine-tuning; independent validation and calibration."""
from pathlib import Path
import argparse,hashlib,json,math,random,time,os
import numpy as np
import torch
from model_io import load,encode,collate,forward,export

ROOT=Path(__file__).resolve().parent

def read(split):return [json.loads(l) for l in (ROOT/f'data/processed/{split}.jsonl').open(encoding='utf8')]

@torch.inference_mode()
def evaluate(model,items,tokenizer,device,batch_size):
    model.eval();loss=0.;correct=0;n=0
    for at in range(0,len(items),batch_size):
        chunk=items[at:at+batch_size];batch=collate(chunk,tokenizer.pad_token_id,device);logits=forward(model,batch,device)
        loss+=float((-(batch[-1]*torch.log_softmax(logits,-1)).sum(-1)).sum());correct+=int((logits.argmax(-1)==batch[-1].argmax(-1)).sum());n+=len(chunk)
    return {'loss':loss/max(n,1),'accuracy':correct/max(n,1),'questions':n}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--base',default='models/base');ap.add_argument('--output',default='models/security-sft');ap.add_argument('--epochs',type=int,default=3);ap.add_argument('--micro-batch',type=int,default=4);ap.add_argument('--grad-accum',type=int,default=8);ap.add_argument('--max-len',type=int,default=1024);ap.add_argument('--seed',type=int,default=20261004);ap.add_argument('--device',default='cuda:0');args=ap.parse_args()
    torch.set_num_threads(4);random.seed(args.seed);np.random.seed(args.seed);torch.manual_seed(args.seed)
    if args.device.startswith('cuda') and not torch.cuda.is_available():raise RuntimeError('CUDA required; refusing an unnoticed CPU fallback')
    started=time.time();device=args.device;base=ROOT/args.base;out=ROOT/args.output
    model,tok,cfg=load(base,device,args.max_len)
    # Export keeps this auxiliary routing head; it is not the security response classifier.
    for p in model.act_head.parameters():p.requires_grad_(False)
    model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.head_checkpointing=True
    train_rows=read('train');val_rows=read('validation');cal_rows=read('calibration')
    for a,b in [(train_rows,val_rows),(train_rows,cal_rows),(val_rows,cal_rows)]:assert not {x['group_id'] for x in a}&{x['group_id'] for x in b}
    train=[x for r in train_rows for x in encode(r,tok,cfg)];validation=[x for r in val_rows for x in encode(r,tok,cfg)]
    print(json.dumps({'phase':'prepared','train_cases':len(train_rows),'train_questions':len(train),'validation_questions':len(validation),'device':torch.cuda.get_device_name(0),'max_input_tokens':max(len(x['ids']) for x in train),'trainable_params':sum(p.numel() for p in model.parameters() if p.requires_grad)}),flush=True)
    encoder=[p for n,p in model.named_parameters() if n.startswith('encoder.') and p.requires_grad]
    head=[p for n,p in model.named_parameters() if not n.startswith('encoder.') and p.requires_grad]
    optimizer=torch.optim.AdamW([{'params':encoder,'lr':1e-5},{'params':head,'lr':5e-5}],weight_decay=.01)
    updates=math.ceil(len(train)/args.micro_batch/args.grad_accum)*args.epochs
    scheduler=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,max(1,updates),eta_min=1e-6)
    baseline=evaluate(model,validation,tok,device,args.micro_batch)
    best=float('inf');history=[];step=0
    (ROOT/'runs').mkdir(exist_ok=True)
    initial_probe=model.scorer[-1].weight.detach().float().cpu().clone()
    for epoch in range(args.epochs):
        random.Random(args.seed+epoch).shuffle(train);model.train();optimizer.zero_grad(set_to_none=True)
        micro=0;loss_sum=0.;seen=0;epoch_start=time.time()
        for start in range(0,len(train),args.micro_batch):
            chunk=train[start:start+args.micro_batch];batch=collate(chunk,tok.pad_token_id,device)
            logits=forward(model,batch,device);loss=-(batch[-1]*torch.log_softmax(logits,-1)).sum(-1).mean()
            if not torch.isfinite(loss):raise RuntimeError('Non-finite training loss')
            # Normalize the final partial accumulation group by its actual item count.
            block_start=(start//(args.micro_batch*args.grad_accum))*(args.micro_batch*args.grad_accum)
            block_size=min(args.micro_batch*args.grad_accum,len(train)-block_start)
            (loss*len(chunk)/block_size).backward();micro+=1;loss_sum+=loss.item()*len(chunk);seen+=len(chunk)
            if micro%args.grad_accum==0 or start+args.micro_batch>=len(train):
                norm=torch.nn.utils.clip_grad_norm_(model.parameters(),1.0,error_if_nonfinite=True);optimizer.step();scheduler.step();optimizer.zero_grad(set_to_none=True);step+=1
                if step%20==0:print(json.dumps({'epoch':epoch+1,'step':step,'loss':loss_sum/seen,'seconds':round(time.time()-started,1),'gpu_peak_gb':round(torch.cuda.max_memory_allocated()/1e9,2)}),flush=True)
        metrics=evaluate(model,validation,tok,device,args.micro_batch)
        entry={'epoch':epoch+1,'train_loss':loss_sum/seen,'validation':metrics,'seconds':time.time()-epoch_start};history.append(entry);print(json.dumps(entry),flush=True)
        if metrics['loss']<best:
            best=metrics['loss'];export(model,tok,cfg,out,{'args':vars(args),'selected_epoch':epoch+1,'step':step,'history':history,'baseline_validation':baseline,'weight_probe_l2':float((model.scorer[-1].weight.detach().float().cpu()-initial_probe).norm()),'training_seconds':time.time()-started,'base_sha256':hashlib.sha256((base/'model.safetensors').read_bytes()).hexdigest()})
        (ROOT/'runs/training_history.json').write_text(json.dumps({'history':history,'baseline':baseline},indent=2))
    del optimizer,model;torch.cuda.empty_cache()
    # Reload the exported half-precision weights, then calibrate this exact artifact.
    model,tok,cfg=load(out,device);model.eval();samples=[[],[],[]]
    cal=[x for r in cal_rows for x in encode(r,tok,cfg)]
    with torch.inference_mode():
        for start in range(0,len(cal),args.micro_batch):
            chunk=cal[start:start+args.micro_batch];batch=collate(chunk,tok.pad_token_id,device);logits=forward(model,batch,device).cpu()
            for i,x in enumerate(chunk):samples[x['qtype']].append((logits[i,:len(x['markers'])].tolist(),x['target']))
    temperatures=[]
    for group in samples:
        logits=torch.tensor([x for x,y in group]);targets=torch.tensor([y for x,y in group])
        candidates=torch.linspace(.5,5,181)
        losses=[float(-(targets*torch.log_softmax(logits/t,-1)).sum(-1).mean()) for t in candidates]
        temperatures.append(float(candidates[int(np.argmin(losses))]))
    cfg['temperature']=temperatures;cfg.pop('temperature_by_options',None)
    (out/'rl_agent_config.json').write_text(json.dumps(cfg,indent=2))
    result={'status':'complete','steps':step,'temperatures':temperatures,'seconds':time.time()-started,'output':str(out),'calibration_cases':len(cal_rows),'best_validation_loss':best}
    (ROOT/'runs/training_complete.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)

if __name__=='__main__':main()
