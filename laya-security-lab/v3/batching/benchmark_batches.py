"""Fixed-policy batch-size accuracy and throughput, plus bounded-wait load probes."""
import argparse,gc,json,math,random,time
import numpy as np
import torch
from batch_runtime import ROOT,V3,MODELS,load_model,scores,sha
from common_v3 import read,metric

SIZES=[1,4,8,16,32]

def percentiles(values,prefix):
    return {f'{prefix}_p{p}_ms':float(np.percentile(values,p)) for p in [50,95,99]}

def offline(detector,policy,rows,original,batch_size,folder,name):
    # Warm this shape and include all input serialization/tokenization in timed calls.
    for _ in range(3):scores(detector,policy,rows[:batch_size])
    torch.cuda.synchronize();torch.cuda.reset_peak_memory_stats()
    durations=[];records=[];started=time.perf_counter()
    for start in range(0,len(rows),batch_size):
        chunk=rows[start:start+batch_size];before=time.perf_counter();values=scores(detector,policy,chunk);elapsed=(time.perf_counter()-before)*1000
        durations.append(elapsed)
        records += [{'id':r['id'],'split':r['split'],'gold':r['y'],'probability':float(p),'predicted':bool(p>=policy['threshold']),
                     'batch_id':start//batch_size,'batch_size':len(chunk),'batch_ms':elapsed} for r,p in zip(chunk,values)]
    elapsed=time.perf_counter()-started
    result={'batch_size':batch_size,'cases':len(rows),'batches':len(durations),'seconds':elapsed,'windows_per_second':len(rows)/elapsed,
            'amortized_compute_ms':sum(durations)/len(rows),'peak_allocated_mib':torch.cuda.max_memory_allocated()/1048576,
            **percentiles(durations,'batch'),'different_decisions_from_v3_batch1':sum(r['predicted']!=original[r['id']]['predicted'] for r in records),'tests':{}}
    for split in ['known_test','unseen_test','external_known_test']:
        selected=[(r,p) for r,p in zip(rows,records) if r['split']==split]
        result['tests'][split]=metric([p['probability'] for r,p in selected],[r for r,p in selected],policy['threshold'])
    (folder/f'predictions_{name}_b{batch_size}.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
    return result

def load_probe(detector,policy,rows,batch_size,folder,name,rate=50,duration=10,max_wait_ms=10):
    # A single worker sees deterministic scheduled arrivals. The oldest queued request
    # can wait at most max_wait_ms for formation; worker busy time is additional.
    count=rate*duration;records=[];batches=[];i=0;started=time.perf_counter()
    while i<count:
        first_arrival=started+i/rate
        if time.perf_counter()<first_arrival:time.sleep(max(0,first_arrival-time.perf_counter()))
        full_arrival=started+(min(count-1,i+batch_size-1))/rate
        wake=min(first_arrival+max_wait_ms/1000,full_arrival)
        if time.perf_counter()<wake:time.sleep(max(0,wake-time.perf_counter()))
        before=time.perf_counter();available=min(count,math.floor((before-started)*rate)+1)
        take=min(batch_size,max(1,available-i));chunk=[rows[k%len(rows)] for k in range(i,i+take)]
        scores(detector,policy,chunk);end=time.perf_counter();service=(end-before)*1000
        for j in range(take):
            arrival=started+(i+j)/rate
            records.append({'request':i+j,'id':chunk[j]['id'],'batch_size':take,'arrival_offset_s':(i+j)/rate,
                            'queue_ms':(before-arrival)*1000,'service_ms':service,'response_ms':(end-arrival)*1000})
        batches.append(take);i+=take
    elapsed=time.perf_counter()-started
    (folder/f'load_{name}_b{batch_size}.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
    return {'max_batch_size':batch_size,'max_formation_wait_ms':max_wait_ms,'offered_rps':rate,'requests':count,'duration_seconds':duration,
            'seconds_including_drain':elapsed,'completion_rps':count/elapsed,'effective_mean_batch_size':float(np.mean(batches)),
            'actual_max_batch_size':max(batches),'within_200ms_fraction':sum(r['response_ms']<=200 for r in records)/count,
            **percentiles([r['response_ms'] for r in records],'response')}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--models',nargs='+',choices=MODELS,default=MODELS);args=ap.parse_args()
    torch.set_num_threads(4);folder=ROOT/'runs';folder.mkdir(exist_ok=True)
    rows=[r for s in ['known_test','unseen_test','external_known_test'] for r in read(s)]
    random.Random(20261005).shuffle(rows)
    report={'gpu':torch.cuda.get_device_name(0),'dtype':'existing BF16 inference','cases':len(rows),'data_sha256':{s:sha(V3/f'data/{s}.jsonl') for s in ['known_test','unseen_test','external_known_test']},
            'weights_and_thresholds_frozen':True,'offline_scope':'Prepared inputs, tokenization+inference, no queue or window wait. Batch latency is not divided by batch size.',
            'load_scope':'Single worker, 50 arrivals/s for 10 seconds, 10ms max formation wait; includes queue and inference, excludes sensor/window/network/actuator.',
            'models':{}}
    for name in args.models:
        detector,policy,identity=load_model(name)
        original={r['id']:r for r in [json.loads(s) for s in (V3/f'runs/online_predictions_{name}.jsonl').read_text().splitlines()]}
        entry={'identity':identity,'offline':[],'load':[]};report['models'][name]=entry
        for size in SIZES:
            try:r=offline(detector,policy,rows,original,size,folder,name)
            except torch.cuda.OutOfMemoryError:
                torch.cuda.empty_cache();r={'batch_size':size,'error':'CUDA out of memory; no automatic batch reduction'}
            entry['offline'].append(r);(folder/'comparison.json').write_text(json.dumps(report,indent=2))
            print(json.dumps({'model':name,'phase':'offline',**{k:v for k,v in r.items() if k!='tests'}}),flush=True)
        for size in SIZES:
            for _ in range(3):scores(detector,policy,rows[:min(size,4)])
            r=load_probe(detector,policy,rows,size,folder,name);entry['load'].append(r)
            (folder/'comparison.json').write_text(json.dumps(report,indent=2));print(json.dumps({'model':name,'phase':'load',**r}),flush=True)
        assert sha(V3/f'models/{name}_decision_policy.json')==identity['policy_sha256']
        del detector;gc.collect();torch.cuda.empty_cache()
    print('BATCH_BENCHMARK_COMPLETE',flush=True)

if __name__=='__main__':main()
