"""Warm inference throughput and latency on one named GPU, using fixed train-only timing inputs."""
import argparse, json, os, time
os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL','2')
import numpy as np
from common import ROOT, LAB, CONFIGS, SEEDS, BATCH, Data, write_json, digest

def main():
    p=argparse.ArgumentParser();p.add_argument('--model',required=True,choices=list(CONFIGS)+['laya'])
    p.add_argument('--seed',type=int,default=SEEDS[0]);p.add_argument('--laya-micro',type=int,default=16)
    p.add_argument('--device',default='cuda:0');p.add_argument('--base-profile',action='store_true');p.add_argument('--smoke',action='store_true')
    args=p.parse_args();data=Data();folder=ROOT/f'runs/{args.model}/seed{args.seed}'
    meta=json.loads((folder/'training.json').read_text()) if not args.base_profile else {}
    if args.base_profile and args.model!='laya':raise ValueError('Base profile applies to Laya only')
    timing_indices=data.schedule(SEEDS[0])[0,:50].copy()
    if args.model=='laya':
        import torch
        from laya_adapter import load,collate,forward,Encoder,MAX_LEN
        torch.set_num_threads(4)
        path=LAB/'models/base' if args.base_profile else folder/'model'
        model,tok,cfg=load(path,args.device,MAX_LEN);model.eval();encoder=Encoder(data,tok,cfg)
        def encode(indices):return encoder.items(indices)
        @torch.inference_mode()
        def predict(items):
            out=[]
            for k in range(0,len(items),args.laya_micro):
                z=forward(model,collate(items[k:k+args.laya_micro],tok.pad_token_id,args.device),args.device)
                out.append(z.softmax(-1)[:,1].cpu().numpy())
            return np.concatenate(out)
        def sync():
            if args.device.startswith('cuda'):torch.cuda.synchronize()
        hardware=torch.cuda.get_device_name() if args.device.startswith('cuda') else 'CPU'
        weight_hash=digest(path/'model.safetensors')
    else:
        from train_flow import build,predictor,tf
        model,integers=build(args.model,data);model.load_weights(str(folder/'model.weights.h5'))
        predict=predictor(model)
        def encode(indices):return data.features(indices,integers)
        def sync():pass # predictor materializes results through .numpy()
        hardware=[tf.config.experimental.get_device_details(x) for x in tf.config.list_physical_devices('GPU')]
        weight_hash=digest(folder/'model.weights.h5')
    if not args.base_profile:assert weight_hash==meta['weights_sha256']
    if args.smoke:
        value=predict(encode(timing_indices[0][:1]))
        assert value.shape==(1,) and np.isfinite(value).all() and ((value>=0)&(value<=1)).all()
        write_json(ROOT/f'runs/benchmark_smoke_{args.model}.json',dict(model=args.model,smoke_only=True,probability=value.tolist(),hardware=hardware))
        print('SMOKE_OK',args.model,flush=True);return
    result=dict(model=args.model,seed=args.seed,hardware=hardware,weights_sha256=weight_hash,
        timing_input='50 pre-generated balanced random training batches; no test-label selection',
        method='per batch: four repeats, median; mean across 50 batches',
        includes='model + CPU/GPU transfer + output materialization; excludes disk/model load and window accumulation',
        base_profile=args.base_profile,logical_batch128_micro_batch=args.laya_micro if args.model=='laya' else 128,
        raw={})
    for batch in [1,8,128]:
        # Batch 1 samples alternate attack/benign across balanced schedule halves.
        indices=[row[([0] if i%2==0 else [64])] if batch==1 else np.r_[row[:batch//2],row[64:64+batch//2]] for i,row in enumerate(timing_indices)]
        batches=[encode(x) for x in indices]
        for b in batches[:4]:predict(b)
        measured=[]
        for b in batches:
            repetitions=[]
            for _ in range(4):
                sync();t=time.perf_counter();predict(b);sync();repetitions.append(time.perf_counter()-t)
            measured.append(repetitions)
        medians=np.median(measured,axis=1)
        result['raw'][str(batch)]=measured
        result[f'batch{batch}']=dict(mean_median_ms=float(np.mean(medians)*1000),p50_ms=float(np.median(medians)*1000),
            p95_ms=float(np.quantile(medians,.95)*1000),flows_per_second=float(batch/np.mean(medians)),n_batches=50,repeats=4)
        print(json.dumps(dict(model=args.model,batch=batch,**result[f'batch{batch}'])),flush=True)
    # Online path includes text serialization/tokenization for Laya, input assembly for TF.
    end_to_end=[]
    for i,row in enumerate(timing_indices):
        idx=row[[0 if i%2==0 else 64]]
        repetitions=[]
        for _ in range(4):
            if args.model=='laya':encoder.row_text.cache_clear()
            sync();t=time.perf_counter();predict(encode(idx));sync();repetitions.append(time.perf_counter()-t)
        end_to_end.append(repetitions)
    m=np.median(end_to_end,axis=1)
    requests=np.asarray(end_to_end).ravel()
    result['batch1_serialization_inclusive']=dict(
        p50_ms=float(np.median(requests)*1000),p95_ms=float(np.quantile(requests,.95)*1000),
        mean_ms=float(np.mean(requests)*1000),n_requests=len(requests),
        quantile_definition='Across all 200 individual warm requests; not across 50 four-repeat medians',
        paper_mean_of_batch_medians_ms=float(np.mean(m)*1000),raw_seconds=end_to_end)
    if args.model=='laya':result['maximum_tokens']=encoder.max_observed
    destination=ROOT/'runs/laya_base_timing.json' if args.base_profile else folder/'timing.json'
    write_json(destination,result)

if __name__=='__main__':main()
