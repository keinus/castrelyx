"""Correlate logs, detect a threat and emit a bounded response plan. No actuation."""
from pathlib import Path
from dataclasses import asdict
import argparse,json,time
from contract import decision_plan
from correlation import Event,Correlator,read_rows,zeek_event,host_event
from model_io import Predictor

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--model',default='models/security-sft');ap.add_argument('--events');ap.add_argument('--zeek',nargs='*',default=[]);ap.add_argument('--host',nargs='*',default=[]);ap.add_argument('--assets');ap.add_argument('--context');ap.add_argument('--device',default='cuda:0');ap.add_argument('--step-seconds',type=float,default=15);ap.add_argument('--limit',type=int,default=100);ap.add_argument('--output',default='runs/decisions.jsonl');args=ap.parse_args()
    assets=json.loads(Path(args.assets).read_text()) if args.assets else {}
    context=json.loads(Path(args.context).read_text()) if args.context else {'identity_verified':False}
    events=[]
    if args.events:
        events.extend(Event(**r) for _,r in read_rows(args.events))
    for path in args.zeek:
        events.extend(e for _,r in read_rows(path) if (e:=zeek_event(r,path,assets)))
    for path in args.host:
        events.extend(e for _,r in read_rows(path) if (e:=host_event(r,path,assets)))
    if not events:raise ValueError('No supported events; supply --events or --zeek and --host')
    events.sort(key=lambda e:(e.available,e.event_id))
    threshold_path=Path(args.model)/'policy_thresholds.json'
    thresholds=json.loads(threshold_path.read_text()) if threshold_path.exists() else {}
    predictor=Predictor(args.model,args.device);correlator=Correlator();last={};n=0
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('w',encoding='utf8') as out:
        for e in events:
            start=time.perf_counter();fresh=correlator.add(e)
            if not fresh or e.available-last.get(e.entity,-float('inf'))<args.step_seconds:continue
            last[e.entity]=e.available;state=correlator.snapshot(e.entity,e.available,context)
            result=predictor.predict(state);a=result['answers']['response']
            plan=decision_plan(result['answers'],context,e.entity,thresholds,state['quality'])
            row={'entity_ref':e.entity,'as_of':e.available,'threat':result['answers']['threat'],'urgency':result['answers']['urgency'],
                'raw_response':a,'response_plan':plan,'application_decision_ms':(time.perf_counter()-start)*1000,
                'quality':state['quality'],'relations':state['relations'],'input_tokens':result['input_tokens'],'actuation_measured':False}
            out.write(json.dumps(row,ensure_ascii=False)+'\n');out.flush();n+=1
            if args.limit and n>=args.limit:break
    print(json.dumps({'decisions':n,'output':str(output),'mode':'plan_only'}))

if __name__=='__main__':main()
