"""Freeze calibration-only thresholds, then evaluate untouched test cohorts."""
import argparse,collections,json,math,time
from pathlib import Path
import numpy as np
import torch
from sklearn.metrics import average_precision_score,roc_auc_score
from common_v4 import Detector,ROOT,load_rows,output_dir
from acquire import digest

def sigmoid(x):return 1/(1+np.exp(-np.clip(np.asarray(x),-60,60)))
def calibrate(raw,rows,target):
    y=np.array([r['y'] for r in rows]);temperatures=np.geomspace(.25,8,121)
    if len(set(y))!=2:raise ValueError('Calibration requires both classes')
    losses=[]
    for temperature in temperatures:
        p=np.clip(sigmoid(raw/temperature),1e-7,1-1e-7);losses.append(-(y*np.log(p)+(1-y)*np.log(1-p)).mean())
    temperature=float(temperatures[np.argmin(losses)]);p=sigmoid(raw/temperature);best=(-1.,0.,0.)
    for threshold in np.unique(np.r_[p,1.000001]):
        z=p>=threshold;tp=np.sum(z&(y==1));fp=np.sum(z&(y==0));fn=np.sum(~z&(y==1));fpr=fp/np.sum(y==0)
        if fpr<=target:
            candidate=(2*tp/max(1,2*tp+fp+fn),-float(fpr),float(threshold))
            if candidate>best:best=candidate
    return {'temperature':temperature,'threshold':best[2],'calibration_f1':float(best[0]),'calibration_fpr':-best[1],
        'fpr_target':target,'normal_calibration_n':int(sum(y==0)),'selection_split':'calibration',
        'guarantees_test_fpr':False,'score_is_deployment_probability':False}

def metrics(p,rows,threshold):
    y=np.array([r['y'] for r in rows]);z=p>=threshold
    tp=int(sum(z&(y==1)));fp=int(sum(z&(y==0)));fn=int(sum(~z&(y==1)));tn=int(sum(~z&(y==0)))
    return {'n':len(rows),'positive':int(sum(y)),'tp':tp,'fp':fp,'fn':fn,'tn':tn,'precision':tp/max(1,tp+fp),
        'recall':tp/(tp+fn) if tp+fn else None,'fpr':fp/(fp+tn) if fp+tn else None,'f1':2*tp/max(1,2*tp+fp+fn),
        'ap':float(average_precision_score(y,p)) if sum(y) else None,'auroc':float(roc_auc_score(y,p)) if len(set(y))==2 else None}

def cluster_interval(p,rows,threshold,seed):
    groups=collections.defaultdict(lambda:np.zeros(4))
    for score,row in zip(p,rows):
        z=score>=threshold;y=bool(row['y']);groups[row['group']]+=np.array([z and y,z and not y,not z and y,not z and not y])
    if len(groups)<2:return {'groups':len(groups),'recall_95':None,'fpr_95':None}
    values=np.array(list(groups.values()));rng=np.random.default_rng(seed);rec=[];fpr=[]
    for _ in range(500):
        tp,fp,fn,tn=values[rng.integers(len(values),size=len(values))].sum(0)
        if tp+fn:rec.append(tp/(tp+fn))
        if fp+tn:fpr.append(fp/(fp+tn))
    return {'groups':len(groups),'recall_95':np.percentile(rec,[2.5,97.5]).tolist() if rec else None,
        'fpr_95':np.percentile(fpr,[2.5,97.5]).tolist() if fpr else None,'unit':'capture/time block; few campaigns, descriptive bootstrap'}

def latency(detector,rows,batch_size,n=24):
    rng=np.random.default_rng(7);indices=rng.integers(len(rows),size=(n+4)*batch_size)
    parts=[[rows[i] for i in indices[j:j+batch_size]] for j in range(0,len(indices),batch_size)];times=[]
    for i,part in enumerate(parts):
        t=time.perf_counter();detector.score(part,batch_size);elapsed=(time.perf_counter()-t)*1000
        if i>=4:times.append(elapsed)
    return {'batch_size':batch_size,'batches':n,'batch_p50_ms':float(np.percentile(times,50)),
        'batch_p95_ms':float(np.percentile(times,95)),'batch_p99_ms':float(np.percentile(times,99)),
        'throughput_flows_s':batch_size*1000/float(np.mean(times)),
        'scope':'input serialization/tokenization + scoring; excludes sensor/export, queue and actuator'}

def queued(detector,rows,policy,rate,max_batch,wait_ms,n=200):
    if rate<=0 or max_batch<1 or wait_ms<0:raise ValueError('Invalid queue configuration')
    begin=time.perf_counter();next_request=0;delays=[];sizes=[];predictions=[]
    while next_request<n:
        now=time.perf_counter()-begin;arrival=next_request/rate
        if now<arrival:time.sleep(arrival-now)
        now=time.perf_counter()-begin;ready=min(n,int(now*rate)+1)-next_request
        if ready<max_batch:
            deadline=arrival+wait_ms/1000
            # Wait only up to oldest request's deadline, never to fill a fixed batch.
            next_fill=(next_request+max_batch-1)/rate
            target=min(deadline,next_fill)
            if now<target:time.sleep(target-now)
        now=time.perf_counter()-begin;count=min(max_batch,n-next_request,max(1,int(now*rate)+1-next_request))
        part=[rows[i%len(rows)] for i in range(next_request,next_request+count)]
        raw=detector.score(part,count);finish=time.perf_counter()-begin;sizes.append(count)
        for i,p in enumerate(sigmoid(raw/policy['temperature'])):
            delays.append((finish-(next_request+i)/rate)*1000);predictions.append(bool(p>=policy['threshold']))
        next_request+=count
    return {'requests':n,'offered_rate':rate,'max_batch':max_batch,'max_formation_wait_ms':wait_ms,'mean_batch_size':float(np.mean(sizes)),
        'p95_ms':float(np.percentile(delays,95)),'p99_ms':float(np.percentile(delays,99)),
        'within_200ms_fraction':float(np.mean(np.array(delays)<=200)),
        'scope':'synthetic regular arrival replay of real held-out states; queue + inference, no actuation',
        'per_request_latency_ms':delays,'batch_sizes':sizes,'predictions':predictions}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--folder',type=Path,required=True);ap.add_argument('--batch-size',type=int,default=1)
    ap.add_argument('--device',default='cuda:0');ap.add_argument('--queue',action='store_true');args=ap.parse_args()
    if args.batch_size<1:raise ValueError('Batch size must be positive')
    if (args.folder/'evaluation.json').exists():raise FileExistsError('Evaluation is frozen; create another experiment')
    torch.set_num_threads(4);detector=Detector(args.folder,args.device);meta=detector.meta;exp=meta['experiment'];started=time.time()
    manifest=json.loads((ROOT/'data/experiments'/exp/'manifest.json').read_text())
    if meta['train_sha256']!=manifest['counts']['train']['sha256']:raise ValueError('Model/data contract mismatch')
    cal=load_rows(exp,'calibration');raw=detector.score(cal,args.batch_size)
    policies={str(target):calibrate(raw,cal,target) for target in (.01,.001)}
    for policy in policies.values():policy['inference_batch_size']=args.batch_size
    (args.folder/'policies.json').write_text(json.dumps(policies,indent=2))
    result={'model':meta['model'],'view':meta['view'],'seed':meta['seed'],'pilot':manifest['pilot'],'policies':policies,
        'weights_sha256':meta['weights_sha256'],'tests':{},'data_manifest_sha256':digest(ROOT/'data/experiments'/exp/'manifest.json'),
        'unknown_attack_evaluated':bool(manifest['heldout_family']),'sampling':manifest['sampling']}
    for split in ('known_test','external_test','unseen_test'):
        rows=load_rows(exp,split)
        if not rows:continue
        raw=detector.score(rows,args.batch_size);p=sigmoid(raw/policies['0.01']['temperature']);result['tests'][split]={}
        for target,policy in policies.items():
            m=metrics(p,rows,policy['threshold']);m['cluster_bootstrap']=cluster_interval(p,rows,policy['threshold'],meta['seed']);result['tests'][split][target]=m
        (args.folder/(split+'_predictions.jsonl')).write_text(''.join(json.dumps({'id':r['id'],'group':r['group'],'y':r['y'],'family':r['family'],
            'raw_score':float(z),'score':float(s),'predicted':bool(s>=policies['0.01']['threshold'])})+'\n' for r,z,s in zip(rows,raw,p)))
        print(json.dumps({'model':meta['model'],'view':meta['view'],'split':split,**result['tests'][split]['0.01']}),flush=True)
    probe=load_rows(exp,'known_test');result['latency']=[latency(detector,probe,b) for b in (1,8)]
    probe=probe[:128];one=detector.score(probe,1);eight=detector.score(probe,8);policy=policies['0.01']
    result['batch_stability']={'n':len(probe),'decision_changes':int(sum((sigmoid(one/policy['temperature'])>=policy['threshold'])!=(sigmoid(eight/policy['temperature'])>=policy['threshold']))),
        'max_raw_score_difference':float(np.max(np.abs(one-eight))),'scope':'sampled diagnostic; not full batch-equivalence proof'}
    if args.queue:
        queue=queued(detector,load_rows(exp,'known_test'),policy,50,8,10)
        (args.folder/'queue.json').write_text(json.dumps(queue,indent=2));result['queue']={k:v for k,v in queue.items() if k not in ('per_request_latency_ms','batch_sizes','predictions')}
    result['seconds']=time.time()-started;(args.folder/'evaluation.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({'complete':str(args.folder),'seconds':result['seconds']}),flush=True)
if __name__=='__main__':main()
