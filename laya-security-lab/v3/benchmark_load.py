"""Short open-loop load probes of a single resident worker, including its queue."""
import gc,json,random,time
import numpy as np
import torch
from common_v3 import ROOT,read,LayaDetector,QwenDetector

def main():
    torch.set_num_threads(4)
    rows=read('known_test')+read('unseen_test')
    random.Random(20261005).shuffle(rows)
    report={'duration_seconds_per_rate':10,'rates_per_second':[10,25,50],
            'scope':'one resident sequential worker, scheduled arrival to completed score; no sensor/window/actuator',
            'batch_size':1,'limitations':'Short synthetic load, not sustained production capacity. Rates run after previous queue drains.',
            'models':{}}
    for name in ('laya_v3_normal_relation_sft','qwen3_06b_v3_lora'):
        detector=LayaDetector(ROOT/'models/laya_v3_normal_relation') if name=='laya_v3_normal_relation_sft' else QwenDetector(ROOT.parent/'v2/models/qwen3-0.6b',adapter=ROOT/'models/qwen3_06b_v3_lora')
        for r in rows[:10]:detector.score([r],batch_size=1)
        output=[]
        for rate in report['rates_per_second']:
            count=rate*10;records=[];started=time.perf_counter()
            for i in range(count):
                arrival=started+i/rate
                delay=arrival-time.perf_counter()
                if delay>0:time.sleep(delay)
                before=time.perf_counter();detector.score([rows[i%len(rows)]],batch_size=1);end=time.perf_counter()
                records.append({'arrival_offset_s':i/rate,'queue_ms':(before-arrival)*1000,'service_ms':(end-before)*1000,'response_ms':(end-arrival)*1000})
            elapsed=time.perf_counter()-started
            response=np.array([r['response_ms'] for r in records]);service=np.array([r['service_ms'] for r in records])
            result={'offered_rps':rate,'requests':count,'wall_seconds':elapsed,'completion_rps':count/elapsed,
                    'response_p50_ms':float(np.percentile(response,50)),'response_p95_ms':float(np.percentile(response,95)),
                    'response_p99_ms':float(np.percentile(response,99)),'service_p95_ms':float(np.percentile(service,95)),
                    'within_200ms_fraction':float(np.mean(response<=200)),'queue_max_ms':max(r['queue_ms'] for r in records)}
            output.append(result)
            (ROOT/f'runs/load_{name}_{rate}.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
            print(json.dumps({'model':name,**result}),flush=True)
        report['models'][name]=output
        (ROOT/'runs/load_comparison.json').write_text(json.dumps(report,indent=2))
        del detector;gc.collect();torch.cuda.empty_cache()
    print('LOAD_COMPLETE',flush=True)

if __name__=='__main__':main()
