"""Measured classification and decision latency; no claim of physical router timing."""
from pathlib import Path
from collections import Counter,defaultdict
from concurrent.futures import ThreadPoolExecutor
import argparse,copy,hashlib,json,math,time,platform,random
import numpy as np
import torch
from model_io import Predictor
from contract import ACTION_CRITERIA,response_plan
from correlation import Event,Correlator

ROOT=Path(__file__).resolve().parent

def read(split):return [json.loads(l) for l in (ROOT/f'data/processed/{split}.jsonl').read_text(encoding='utf8').splitlines()]

def percentiles(values):
    return {f'p{p}_ms':float(np.percentile(values,p)) for p in (50,95,99)} if values else {}

def measure(predictor,case,state=None):
    start=time.perf_counter()
    try:
        result=predictor.predict(case['state'] if state is None else state);answers=result['answers']
        return {'case_id':case['case_id'],'group_id':case['group_id'],'source':case['provenance']['source'],
            'gold':case['labels'],'action':answers['response']['choice'],'action_probability':answers['response']['answer_confidence'],
            'action_probabilities':answers['response']['probabilities'],'threat':answers['threat']['threat'],'threat_probability':answers['threat']['noul'],
            'urgency':answers['urgency']['score'],'latency_ms':(time.perf_counter()-start)*1000,'tokens':result['input_tokens']}
    except Exception as e:
        return {'case_id':case['case_id'],'group_id':case['group_id'],'source':case['provenance']['source'],'gold':case['labels'],
            'action':'ERROR','threat':not case['labels']['threat'],'urgency':-1,'error':str(e),'latency_ms':(time.perf_counter()-start)*1000}

def metrics(rows):
    n=len(rows);correct=[r['action']==r['gold']['response'] for r in rows]
    tp=sum(r['threat'] and r['gold']['threat'] for r in rows);fp=sum(r['threat'] and not r['gold']['threat'] for r in rows);fn=sum(not r['threat'] and r['gold']['threat'] for r in rows)
    per_action={a:{'n':sum(r['gold']['response']==a for r in rows),'correct':sum(r['gold']['response']==a and r['action']==a for r in rows)} for a in ACTION_CRITERIA}
    f1s=[]
    for a in ACTION_CRITERIA:
        atp=sum(r['action']==a and r['gold']['response']==a for r in rows);afp=sum(r['action']==a and r['gold']['response']!=a for r in rows);afn=sum(r['action']!=a and r['gold']['response']==a for r in rows)
        f1s.append(2*atp/max(1,2*atp+afp+afn))
    ece=0.
    for low in np.arange(0,1,.1):
        bucket=[r for r in rows if 'action_probability' in r and low<=r['action_probability'] and (r['action_probability']<low+.1 or low>.85 and r['action_probability']<=1)]
        if bucket:ece+=len(bucket)/max(n,1)*abs(np.mean([r['action_probability'] for r in bucket])-np.mean([r['action']==r['gold']['response'] for r in bucket]))
    valid=[r for r in rows if 'threat_probability' in r]
    brier=float(np.mean([(r['threat_probability']-int(r['gold']['threat']))**2 for r in valid])) if valid else None
    return {'cases':n,'independent_groups':len({r['group_id'] for r in rows}),'action_accuracy':sum(correct)/max(n,1),'action_macro_f1':float(np.mean(f1s)),
        'threat_accuracy':sum(r['threat']==r['gold']['threat'] for r in rows)/max(n,1),'threat_precision':tp/max(1,tp+fp),'threat_recall':tp/max(1,tp+fn),'threat_f1':2*tp/max(1,2*tp+fp+fn),
        'urgency_accuracy':sum(r['urgency']==r['gold']['urgency'] for r in rows)/max(n,1),'action_ece_10_bins':ece,'threat_brier':brier,'exceptions':sum('error' in r for r in rows),'per_action':per_action,
        'latency':{**percentiles([r['latency_ms'] for r in rows]),'within_200ms_fraction':sum(r['latency_ms']<=200 and 'error' not in r for r in rows)/max(n,1)}}

def ablated(state,mode):
    state=copy.deepcopy(state)
    if mode=='network_only':
        state['host']={k:0 for k in state['host']};state['sequence']=[];state['quality']['host_present']=False
    else:
        state['network']={k:0 for k in state['network']};state['quality']['network_present']=False
    state['relations']={'matched_process_flows':0,'auth_success_after_failures':False}
    return state

def queue_test(predictor,cases,rate,n=40):
    start=time.perf_counter();latencies=[];errors=0;timely_successes=0
    def work(case,arrival):
        r=measure(predictor,case)
        return (time.perf_counter()-arrival)*1000,'error' in r
    with ThreadPoolExecutor(max_workers=1) as executor:
        pending=[]
        for i in range(n):
            arrival=start+i/rate;time.sleep(max(0,arrival-time.perf_counter()))
            pending.append(executor.submit(work,cases[i%len(cases)],arrival))
        for future in pending:
            latency,error=future.result();latencies.append(latency);errors+=error;timely_successes+=latency<=200 and not error
    elapsed=time.perf_counter()-start
    return {'offered_requests_per_second':rate,'requests':n,'worker_count':1,'completed_requests_per_second':n/elapsed,
        **percentiles(latencies),'within_200ms_fraction':timely_successes/n,'exceptions':errors,'scope':'scheduled arrival through model decision; queue included, no actuation'}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--model',default='models/security-sft');ap.add_argument('--base',default='models/base');ap.add_argument('--device',default='cuda:0');args=ap.parse_args()
    torch.set_num_threads(4);cases=read('test');policy_cases=read('policy_validation');out=ROOT/'runs';out.mkdir(exist_ok=True)
    all_predictions={};summary={'gpu':torch.cuda.get_device_name(0),'torch':torch.__version__,'platform':platform.platform(),'question_count':3,
        'latency_scope':'Model service/application decision only. Sensor emission, transport and physical router application are NOT included.',
        'no_live_response_executed':True,'models':{},'dataset_manifest':json.loads((ROOT/'data/processed/dataset_manifest.json').read_text())}
    for name,path in [('base',args.base),('finetuned',args.model)]:
        start=time.perf_counter();predictor=Predictor(str(ROOT/path),args.device);predictor.cfg['max_len']=1024
        summary['models'][name]={'load_seconds':time.perf_counter()-start,'weights_sha256':hashlib.sha256((ROOT/path/'model.safetensors').read_bytes()).hexdigest()}
        cold=measure(predictor,cases[0]);summary['models'][name]['first_request_ms']=cold['latency_ms']
        for r in cases[:20]:predictor.predict(r['state'])
        results=[measure(predictor,r) for r in cases];all_predictions[name]=results
        summary['models'][name]['all']=metrics(results)
        summary['models'][name]['by_source']={s:metrics([r for r in results if r['source']==s]) for s in sorted({r['source'] for r in results})}
        print(name,json.dumps(summary['models'][name]['all']),flush=True)
        if name=='finetuned':
            # Policy selection never reads the final test labels.
            policy_results=[measure(predictor,r) for r in policy_cases];thresholds={}
            for action in ACTION_CRITERIA:
                candidates=[]
                for t in [.5,.6,.7,.8,.85,.9,.95,.99]:
                    accepted=[r for r in policy_results if r['action']==action and r.get('action_probability',0)>=t]
                    error=sum(r['gold']['response']!=action for r in accepted)/max(1,len(accepted))
                    if len(accepted)>=20 and error<=.1:candidates.append((len(accepted),t,error))
                chosen=max(candidates,default=(0,1.01,None))
                thresholds[action]={'min_confidence':chosen[1],'policy_validation_n':chosen[0],'empirical_error':chosen[2],'statistical_error_bound_guaranteed':False}
            summary['policy_thresholds']=thresholds
            accepted=[r for r in results if r.get('action_probability',0)>=thresholds[r['action']]['min_confidence']] if not any(r['action']=='ERROR' for r in results) else []
            summary['selective_test']={'coverage':len(accepted)/len(results),'accepted_n':len(accepted),'accepted_error':sum(r['action']!=r['gold']['response'] for r in accepted)/max(1,len(accepted))}
            for mode in ('network_only','host_only'):
                rows=[measure(predictor,r,ablated(r['state'],mode)) for r in cases if r['provenance']['source']=='synthetic']
                summary['models'][name][mode]=metrics(rows);all_predictions[mode]=rows
            rows=[]
            for case in cases:
                if case['provenance']['source']!='synthetic':continue
                state=copy.deepcopy(case['state']);state['relations']={'matched_process_flows':0,'auth_success_after_failures':False}
                rows.append(measure(predictor,case,state))
            summary['models'][name]['relations_removed']=metrics(rows);all_predictions['relations_removed']=rows
            summary['queue_load']=[queue_test(predictor,cases,rate) for rate in (5,10,25,50)]
            fixtures=json.loads((ROOT/'data/processed/replay_fixtures.json').read_text());latencies=[];plans=[]
            for fixture in fixtures:
                start=time.perf_counter();correlator=Correlator()
                for raw in fixture['events']:correlator.add(Event(**raw))
                state=correlator.snapshot('endpoint',fixture['snapshot_at'],fixture['context']);r=predictor.predict(state);a=r['answers']['response']
                plan=response_plan(a['choice'],a['answer_confidence'],fixture['context'],'endpoint',thresholds[a['choice']]['min_confidence'])
                elapsed=(time.perf_counter()-start)*1000;latencies.append(elapsed);plans.append({'case_id':fixture['case_id'],'answers':r['answers'],'plan':plan,'latency_ms':elapsed,'relations':state['relations']})
            summary['correlation_to_plan']={'cases':len(plans),**percentiles(latencies),'scope':'Replay fixture parsing into Event + window update + model + plan; no live actuation','synthetic':True}
            (out/'replay_predictions.json').write_text(json.dumps(plans,indent=2))
            (ROOT/args.model/'policy_thresholds.json').write_text(json.dumps(thresholds,indent=2))
        del predictor;torch.cuda.empty_cache()
    for name,rows in all_predictions.items():
        with (out/f'predictions_{name}.jsonl').open('w') as f:
            for r in rows:f.write(json.dumps(r)+'\n')
    (out/'benchmark.json').write_text(json.dumps(summary,indent=2));print('BENCHMARK_COMPLETE',flush=True)

if __name__=='__main__':main()
