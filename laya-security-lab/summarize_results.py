"""Derive inspectable counts and latency from saved predictions, without model calls."""
from pathlib import Path
from collections import Counter
import json,math
import numpy as np

ROOT=Path(__file__).resolve().parent

def rows(name):return [json.loads(l) for l in (ROOT/'runs'/name).read_text().splitlines()]

def wilson(k,n):
    if not n:return None
    z=1.959963984540054;p=k/n;den=1+z*z/n
    center=(p+z*z/(2*n))/den;delta=z*math.sqrt(p*(1-p)/n+z*z/(4*n*n))/den
    return [center-delta,center+delta]

def main():
    b=json.loads((ROOT/'runs/benchmark.json').read_text());sources={}
    for model in ('base','finetuned'):
        r=rows(f'predictions_{model}.jsonl');sources[model]={}
        for source in sorted({x['source'] for x in r}):
            group=[x for x in r if x['source']==source];pos=sum(x['gold']['threat'] for x in group);neg=len(group)-pos
            tp=sum(x['threat'] and x['gold']['threat'] for x in group);fp=sum(x['threat'] and not x['gold']['threat'] for x in group)
            correct=sum(x['action']==x['gold']['response'] for x in group)
            sources[model][source]={'cases':len(group),'positive':pos,'negative':neg,'tp':tp,'fp':fp,'fn':pos-tp,'tn':neg-fp,
                'action_correct':correct,'action_total':len(group),'action_wilson95':wilson(correct,len(group)),
                'threat_recall_wilson95':wilson(tp,pos),'false_positive_rate':fp/max(1,neg),
                'always_no_threat_accuracy':neg/len(group),'always_observe_action_accuracy':sum(x['gold']['response']=='OBSERVE' for x in group)/len(group),
                'action_confusion':dict(Counter(x['gold']['response']+' -> '+x['action'] for x in group)),
                'errors':[{'case_id':x['case_id'],'expected':x['gold'],'predicted_action':x['action'],'predicted_threat':x['threat'],'confidence':x.get('action_probability')} for x in group if x['action']!=x['gold']['response'] or x['threat']!=x['gold']['threat']]}
    replay=rows('otrf_raw_decisions.jsonl');times=[x['application_decision_ms'] for x in replay];steady=times[1:]
    out={'sources':sources,'confidence_interval_scope':'Descriptive case-level Wilson intervals; capture dependence and synthetic distribution are not addressed.',
        'raw_replay':{'cases':len(replay),'both_sources':sum(x['quality']['host_present'] and x['quality']['network_present'] for x in replay),
            'tuple_time_join':sum(x['relations']['matched_process_flows']>0 for x in replay),'threat_true':sum(x['threat']['threat'] for x in replay),
            'raw_actions':dict(Counter(x['raw_response']['choice'] for x in replay)),'gated_actions':dict(Counter(x['response_plan']['action'] for x in replay)),
            'defer_reasons':dict(Counter(x['response_plan']['deferred_reason'] for x in replay)),
            'latency_all':{f'p{p}_ms':float(np.percentile(times,p)) for p in (50,95,99)},
            'latency_excluding_first':{f'p{p}_ms':float(np.percentile(steady,p)) for p in (50,95,99)},
            'first_request_ms':times[0],'within_200ms_fraction':sum(t<=200 for t in times)/len(times),
            'max_buffer_dropped':max(x['quality']['buffer_dropped'] for x in replay),
            'executed_actions':sum(x['response_plan']['executed'] for x in replay),'in_training_capture':True}}
    (ROOT/'runs/results_summary.json').write_text(json.dumps(out,indent=2));print(json.dumps({k:v for k,v in out.items() if k!='sources'},indent=2))

if __name__=='__main__':main()
