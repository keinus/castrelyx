"""Inference-only timing of an existing, hash-checked completed checkpoint."""
import argparse
import json
import os
import platform
import subprocess
import time
from pathlib import Path

os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL','2')
os.environ.setdefault('TOKENIZERS_PARALLELISM','false')
import numpy as np
from common import ROOT, LAB, Data, SEEDS, CONFIGS, digest, write_json


def gpu_snapshot():
    smi='/usr/lib/wsl/lib/nvidia-smi'
    fields='index,uuid,name,driver_version,memory.used,utilization.gpu,temperature.gpu,power.draw'
    return subprocess.check_output([smi,'--query-gpu='+fields,'--format=csv,noheader'],text=True).strip()


def select_folder(model,seed):
    if model=='laya': return ROOT/f'runs/laya/seed{seed}'
    grid=json.loads((ROOT/'runs/grid_comparison.json').read_text())
    rate=float(grid['models'][model]['best_mean_lr'])
    rows=[r for r in grid['rows'] if r['model']==model and r['seed']==seed and r['lr']==rate and r['status']=='complete']
    if len(rows)!=1: raise ValueError('Ambiguous or missing selected checkpoint')
    return ROOT/rows[0]['folder']


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--model',choices=list(CONFIGS)+['laya'],required=True)
    parser.add_argument('--seed',type=int,default=SEEDS[0])
    parser.add_argument('--latency-only',action='store_true')
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    output=Path(args.output).resolve()
    if not output.is_relative_to((ROOT/'speed').resolve()): raise ValueError('Output must be under v5/speed')
    if output.exists(): raise FileExistsError('Existing timing results are immutable')
    data=Data(); folder=select_folder(args.model,args.seed)
    meta=json.loads((folder/'training.json').read_text())
    assert meta['status']=='complete' and meta['source_sha256']==data.meta['source_csv_sha256']
    schedule=data.schedule(SEEDS[0]); timing_indices=np.asarray(schedule[0,:50]).copy()
    assert np.all(data.y[timing_indices[:,:64]]==1) and np.all(data.y[timing_indices[:,64:]]==0)
    before=gpu_snapshot(); load_start=time.perf_counter()
    if args.model=='laya':
        import torch
        from laya_adapter import load,collate,forward,Encoder,MAX_LEN
        if not torch.cuda.is_available(): raise RuntimeError('CUDA GPU required')
        torch.set_num_threads(4)
        path=folder/'model'; weights=path/'model.safetensors'
        assert digest(weights)==meta['weights_sha256']
        model,tok,cfg=load(path,'cuda:0',MAX_LEN); model.eval()
        assert not model.training
        encoder=Encoder(data,tok,cfg)
        def encode(indices): return encoder.items(indices)
        @torch.inference_mode()
        def predict(items):
            values=[]
            for k in range(0,len(items),16):
                z=forward(model,collate(items[k:k+16],tok.pad_token_id,'cuda:0'),'cuda:0')
                values.append(z.softmax(-1)[:,1].cpu().numpy())
            return np.concatenate(values)
        def sync(): torch.cuda.synchronize()
        def clear_input_cache(): encoder.row_text.cache_clear()
        hardware=torch.cuda.get_device_name(0)
        runtime=dict(framework='PyTorch',version=torch.__version__,precision='float32 weights with bfloat16 autocast',attention=model.encoder.config._attn_implementation)
    else:
        from train_flow import build,predictor,tf
        if len(tf.config.list_physical_devices('GPU'))!=1: raise RuntimeError('Exactly one visible CUDA GPU required')
        weights=folder/'model.weights.h5'
        assert digest(weights)==meta['weights_sha256']
        model,integers=build(args.model,data); model.load_weights(str(weights))
        predict=predictor(model)
        def encode(indices): return data.features(indices,integers)
        def sync(): pass # predictor materializes the GPU result with .numpy().
        def clear_input_cache(): pass
        hardware=tf.config.experimental.get_device_details(tf.config.list_physical_devices('GPU')[0]).get('device_name','unknown')
        runtime=dict(framework='TensorFlow',version=tf.__version__,precision=model.dtype_policy.name,execution='tf.function, training=False; no model-wide XLA')
    if '4090' not in hardware: raise RuntimeError('Selected device is not the planned RTX 4090: '+str(hardware))
    result=dict(status='running',model=args.model,seed=args.seed,checkpoint=str(folder.relative_to(ROOT)),
        checkpoint_lr=meta['lr'],weights_sha256=meta['weights_sha256'],started_at=time.time(),hardware=hardware,
        cuda_visible_devices=os.environ.get('CUDA_VISIBLE_DEVICES'),platform=platform.platform(),python=platform.python_version(),
        runtime=runtime,load_seconds=time.perf_counter()-load_start,source_csv_sha256=data.meta['source_csv_sha256'],
        protocol_sha256=digest(ROOT/'speed/protocol.json'),script_sha256=digest(__file__),
        input_schedule_sha256=digest(ROOT/f'data/prepared/schedule_{SEEDS[0]}.npy'),
        gpu_before=before,raw={},latency_only=args.latency_only,
        measurement_boundary='Prepared numeric/categorical window to CPU probability. Raw Zeek parsing and train-fitted normalization, window waiting, queues, network transport and policy actuation excluded.',
        throughput_boundary='Pre-encoded input to CPU probability, including CPU/GPU transfer. Laya logical batch 128 uses sequential microbatches of 16.',
        request_sampling='50 fixed random training windows, alternating 25 attack/25 benign, four warm repetitions per window; no test-result selection')
    sync()
    # Warm every batch size separately, including any TensorFlow tracing.
    for batch_size in ([1] if args.latency_only else [1,8,128]):
        indices=[row[[0 if i%2==0 else 64]] if batch_size==1 else np.r_[row[:batch_size//2],row[64:64+batch_size//2]]
                 for i,row in enumerate(timing_indices)]
        batches=[encode(x) for x in indices]
        for b in batches[:4]: predict(b)
        measured=[]
        for i,b in enumerate(batches):
            repetitions=[]
            for _ in range(4):
                sync(); start=time.perf_counter(); scores=predict(b); sync()
                repetitions.append(time.perf_counter()-start)
                assert scores.shape==(batch_size,) and np.isfinite(scores).all()
                assert ((scores>=0)&(scores<=1)).all()
            measured.append(repetitions)
            if (i+1)%10==0: print(json.dumps(dict(model=args.model,batch=batch_size,batches_done=i+1)),flush=True)
        medians=np.median(measured,axis=1)
        result['raw'][str(batch_size)]=measured
        result[f'batch{batch_size}']=dict(mean_median_ms=float(np.mean(medians)*1000),
            flows_per_second=float(batch_size/np.mean(medians)),n_batches=50,repeats=4,
            logical_batch=batch_size,physical_batch=min(16,batch_size) if args.model=='laya' else batch_size)
        print(json.dumps(dict(model=args.model,batch=batch_size,**result[f'batch{batch_size}'])),flush=True)
    online=[]; online_ids=[]; probabilities=[]
    for i,row in enumerate(timing_indices):
        indices=row[[0 if i%2==0 else 64]]; online_ids.append(int(indices[0]))
        repetitions=[]; values=[]
        for _ in range(4):
            clear_input_cache(); sync(); start=time.perf_counter()
            score=predict(encode(indices)); sync()
            repetitions.append(time.perf_counter()-start); values.append(float(score[0]))
        online.append(repetitions); probabilities.append(values)
    requests=np.asarray(online).ravel()
    result['batch1_serialization_inclusive']=dict(p50_ms=float(np.median(requests)*1000),
        p95_ms=float(np.quantile(requests,.95)*1000),mean_ms=float(np.mean(requests)*1000),
        max_ms=float(np.max(requests)*1000),fraction_within_200ms=float(np.mean(requests<=.2)),
        n_requests=200,n_unique_windows=len(set(online_ids)),raw_seconds=online,
        row_ids=online_ids,probabilities=probabilities,
        quantile_definition='Quantiles of all 200 individual warm requests, not quantiles of repeat medians')
    if args.model=='laya': result['maximum_tokens']=encoder.max_observed
    assert digest(weights)==meta['weights_sha256'], 'Checkpoint changed during measurement'
    result.update(status='complete',finished_at=time.time(),gpu_after=gpu_snapshot(),weights_unchanged=True)
    write_json(output,result)
    print(json.dumps(dict(completed=str(output),latency=result['batch1_serialization_inclusive']['p95_ms'])),flush=True)


if __name__=='__main__': main()
