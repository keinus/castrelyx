"""Full-parameter Laya adaptation on exactly the baseline target/window schedule."""
import argparse, json, time, random, os
os.environ.setdefault('TOKENIZERS_PARALLELISM','false')
import numpy as np
import torch
from common import ROOT, LAB, Data, SEEDS, BATCH, STEPS, EPOCHS, PATIENCE, digest, write_json, group_metrics
from laya_adapter import load, collate, forward, Encoder, save_model, MAX_LEN

def evaluate(model,tok,encoder,data,folder,epoch,device,batch_size=8,context='sequence'):
    model.eval(); scores=[]; started=time.perf_counter()
    with torch.inference_mode():
        for start in range(0,len(data.test),batch_size):
            indices=data.test[start:start+batch_size]
            items=encoder.items(indices,context)
            logits=forward(model,collate(items,tok.pad_token_id,device),device)
            scores.append(logits.softmax(-1)[:,1].cpu().numpy())
            if start and start%(batch_size*500)==0:
                print(json.dumps(dict(phase='eval',rows=start,total=len(data.test),seconds=time.perf_counter()-started)),flush=True)
    scores=np.concatenate(scores).astype(np.float32)
    np.savez_compressed(folder/f'predictions_epoch{epoch}_{context}.npz',row=data.test,y=data.y[data.test],probability=scores)
    result=group_metrics(data,data.test,scores)
    result.update(epoch=epoch,context=context,seconds=time.perf_counter()-started,maximum_tokens=encoder.max_observed)
    write_json(folder/f'evaluation_epoch{epoch}_{context}.json',result)
    return result

def profile(args):
    data=Data();device=args.device
    torch.set_num_threads(4)
    model,tok,cfg=load(LAB/'models/base',device,MAX_LEN)
    encoder=Encoder(data,tok,cfg)
    # Only training rows, not labels/test outcomes, decide memory batch size.
    indices=np.r_[data.schedule(SEEDS[0])[0,0],data.schedule(SEEDS[0])[1,0]]
    items=encoder.items(indices)
    lengths=[len(x['ids']) for x in items]
    print(json.dumps(dict(tokens_min=min(lengths),tokens_median=float(np.median(lengths)),tokens_max=max(lengths),parameters=sum(p.numel() for p in model.parameters()),attention=model.encoder.config._attn_implementation)),flush=True)
    model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.head_checkpointing=True
    optimizer=torch.optim.Adam([p for p in model.parameters() if p.requires_grad],lr=1e-5)
    for micro in [2,4,8,16,32]:
        try:
            torch.cuda.reset_peak_memory_stats(); durations=[]
            sample=sorted(items,key=lambda x:len(x['ids']),reverse=True)[:micro]
            for rep in range(3):
                model.train();optimizer.zero_grad(set_to_none=True)
                batch=collate(sample,tok.pad_token_id,device)
                torch.cuda.synchronize(); t=time.perf_counter()
                z=forward(model,batch,device)
                torch.nn.functional.cross_entropy(z,torch.zeros(micro,dtype=torch.long,device=device)).backward()
                optimizer.step();torch.cuda.synchronize();durations.append(time.perf_counter()-t)
            print(json.dumps(dict(micro_batch=micro,warm_step_seconds=float(np.median(durations[1:])),peak_gb=torch.cuda.max_memory_allocated()/1e9)),flush=True)
        except torch.cuda.OutOfMemoryError:
            optimizer.zero_grad(set_to_none=True);torch.cuda.empty_cache();print(json.dumps(dict(micro_batch=micro,oom=True)),flush=True);break
    # Profile weights are deliberately discarded, never used for experiments.

def train(args):
    seed=args.seed;device=args.device;data=Data()
    random.seed(seed);np.random.seed(seed);torch.manual_seed(seed);torch.set_num_threads(4)
    folder=ROOT/f'runs/laya/seed{seed}'
    if (folder/'training.json').exists(): raise FileExistsError('Completed run is immutable')
    if folder.exists() and not args.resume: raise FileExistsError('Use --resume; refusing overwrite')
    folder.mkdir(parents=True,exist_ok=True)
    checkpoint=folder/'resume.pt'
    model_path=folder/'model' if args.resume else LAB/'models/base'
    model,tok,cfg=load(model_path,device,MAX_LEN)
    for p in model.act_head.parameters(): p.requires_grad_(False)
    model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.head_checkpointing=True
    encoder=Encoder(data,tok,cfg)
    optimizer=torch.optim.Adam([p for p in model.parameters() if p.requires_grad],lr=args.lr)
    metadata=dict(model='laya',seed=seed,parameter_count=sum(p.numel() for p in model.parameters()),trainable_parameter_count=sum(p.numel() for p in model.parameters() if p.requires_grad),
        lr=args.lr,optimizer='Adam',weight_decay=0.,max_epochs=EPOCHS,batch_size=BATCH,micro_batch_size=args.micro_batch,steps_per_epoch=STEPS,patience=PATIENCE,
        schedule_sha256=digest(ROOT/f'data/prepared/schedule_{seed}.npy'),source_sha256=data.meta['source_csv_sha256'],
        base_weights_sha256=digest(LAB/'models/base/model.safetensors'),max_length=MAX_LEN,precision='float32 weights, bfloat16 autocast',
        attention=model.encoder.config._attn_implementation,torch=torch.__version__,device=torch.cuda.get_device_name(),
        status='training',history=[],head='original Laya noul false/true; softmax probability > 0.5; no post-hoc calibration')
    schedule=data.schedule(seed); minimum=100.; stale=0; first=0; elapsed_before=0.; train_seconds=0.; evaluations=[]
    if args.resume:
        ck=torch.load(checkpoint,map_location='cpu',weights_only=False)
        metadata=json.loads((folder/'running.json').read_text())
        if not metadata['history'] or metadata['history'][-1]['epoch']!=ck['epoch']:
            raise ValueError('Interrupted checkpoint transaction: epoch metadata and optimizer differ; refusing an inexact resume')
        if 'weights_sha256' in ck and digest(folder/'model/model.safetensors')!=ck['weights_sha256']:
            raise ValueError('Checkpoint weights/optimizer checksum mismatch')
        optimizer.load_state_dict(ck['optimizer']); minimum=ck['minimum'];stale=ck['stale'];first=ck['epoch']
        elapsed_before=ck['elapsed'];train_seconds=ck['train_seconds']
        torch.set_rng_state(ck['torch_rng']);torch.cuda.set_rng_state(ck['cuda_rng']);random.setstate(ck['random_rng']);np.random.set_state(ck['numpy_rng'])
    write_json(folder/'running.json',metadata)
    started=time.perf_counter()
    for epoch in range(first,EPOCHS):
        model.train();reduced=False;losses=[]; epoch_start=time.perf_counter()
        for step,indices in enumerate(schedule[epoch]):
            items=encoder.items(indices);optimizer.zero_grad(set_to_none=True)
            torch.cuda.synchronize();t=time.perf_counter();batch_loss=0.
            for k in range(0,BATCH,args.micro_batch):
                subset=items[k:k+args.micro_batch]
                batch=collate(subset,tok.pad_token_id,device)
                logits=forward(model,batch,device)
                target=torch.tensor(np.asarray(data.y[indices[k:k+len(subset)]]),dtype=torch.long,device=device)
                loss=torch.nn.functional.cross_entropy(logits,target)
                if not torch.isfinite(loss): raise FloatingPointError(f'Nonfinite loss {epoch}/{step}')
                (loss*len(subset)/BATCH).backward();batch_loss+=float(loss.detach())*len(subset)/BATCH
            optimizer.step();torch.cuda.synchronize();train_seconds+=time.perf_counter()-t
            losses.append(batch_loss)
            if batch_loss<minimum:minimum=batch_loss;reduced=True
            if step%8==0: print(json.dumps(dict(model='laya',seed=seed,epoch=epoch+1,step=step+1,loss=batch_loss,maximum_tokens=encoder.max_observed,seconds=time.perf_counter()-started+elapsed_before)),flush=True)
        stale=0 if reduced else stale+1
        entry=dict(epoch=epoch+1,mean_loss=float(np.mean(losses)),minimum_batch_loss=minimum,stale_epochs=stale,seconds=time.perf_counter()-epoch_start)
        metadata['history'].append(entry);metadata['maximum_tokens']=encoder.max_observed;write_json(folder/'running.json',metadata)
        save_model(model,tok,cfg,folder/'model')
        # Resume at epoch boundaries, preserving optimizer and RNG streams.
        torch.save(dict(optimizer=optimizer.state_dict(),minimum=minimum,stale=stale,epoch=epoch+1,
            weights_sha256=digest(folder/'model/model.safetensors'),
            elapsed=time.perf_counter()-started+elapsed_before,train_seconds=train_seconds,torch_rng=torch.get_rng_state(),
            cuda_rng=torch.cuda.get_rng_state(),random_rng=random.getstate(),numpy_rng=np.random.get_state()),checkpoint)
        stop=stale>PATIENCE
        if epoch==6 or epoch==EPOCHS-1 or stop:
            evaluations.append(evaluate(model,tok,encoder,data,folder,epoch+1,device,args.eval_batch))
        if stop: break
    metadata.update(status='complete',epochs_completed=epoch+1,steps_completed=(epoch+1)*STEPS,
        elapsed_seconds=time.perf_counter()-started+elapsed_before,warm_training_seconds=train_seconds,
        training_flows_per_second=((epoch+1)*STEPS*BATCH)/train_seconds,
        weights_sha256=digest(folder/'model/model.safetensors'),primary_evaluation=f'evaluation_epoch{epoch+1}_sequence.json',maximum_tokens=encoder.max_observed)
    write_json(folder/'training.json',metadata)
    print(json.dumps(dict(completed=str(folder),metrics=evaluations[-1]['overall'])),flush=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('--seed',type=int,default=SEEDS[0]);p.add_argument('--lr',type=float,default=1e-5)
    p.add_argument('--micro-batch',type=int,default=2);p.add_argument('--eval-batch',type=int,default=8);p.add_argument('--device',default='cuda:0');p.add_argument('--resume',action='store_true');p.add_argument('--profile',action='store_true')
    args=p.parse_args()
    if BATCH%args.micro_batch: raise ValueError('Micro batch must divide 128')
    if args.profile: profile(args)
    else: train(args)
if __name__=='__main__': main()
