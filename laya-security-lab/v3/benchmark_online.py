"""Replay all final cases one at a time with frozen, previously calibrated policies."""
from pathlib import Path
import gc,json,time
import numpy as np
import torch
from common_v3 import ROOT,LAB,read,LayaDetector,QwenDetector,sigmoid,metric,sha
from benchmark_v3 import interval,TESTS

def main():
    torch.set_num_threads(4)
    report={'batch_size':1,'policy_refitted':False,'reason':'BF16 batch-versus-single decision discrepancy in CLI verification. Report both; do not tune on test.', 'models':{}}
    for name in ['laya_v3_sft','laya_v3_normal_relation_sft','qwen3_06b_v3_lora']:
        policy_path=ROOT/f'models/{name}_decision_policy.json';policy=json.loads(policy_path.read_text());before=sha(policy_path)
        if name=='qwen3_06b_v3_lora':detector=QwenDetector(LAB/'v2/models/qwen3-0.6b',adapter=ROOT/'models/qwen3_06b_v3_lora')
        else:detector=LayaDetector(ROOT/'models'/('laya_v3_normal_relation' if name.endswith('normal_relation_sft') else name))
        previous={r['id']:r for r in [json.loads(s) for s in (ROOT/f'runs/predictions_{name}.jsonl').read_text().splitlines()]}
        predictions=[];entry={'policy_sha256':before,'tests':{}};started=time.time()
        for split in TESTS:
            rows=read(split);scores=[]
            for r in rows:scores.append(float(sigmoid(detector.score([r],batch_size=1)[0]/policy['temperature'])))
            result=metric(scores,rows,policy['threshold']);result.update(interval(scores,rows,policy['threshold']))
            result['attack_families']={f:{'n':int(sum(r['y'] and r['family']==f for r in rows)), 'detected':int(sum(p>=policy['threshold'] and r['y'] and r['family']==f for r,p in zip(rows,scores)))} for f in sorted({r['family'] for r in rows if r['y']})}
            result['different_decisions_from_batch']=sum(bool(p>=policy['threshold'])!=previous[r['id']]['predicted'] for r,p in zip(rows,scores))
            result['max_absolute_score_difference_from_batch']=max(abs(float(p)-previous[r['id']]['probability']) for r,p in zip(rows,scores))
            entry['tests'][split]=result
            predictions += [{'id':r['id'],'group':r['group'],'split':split,'family':r['family'],'gold':r['y'],'probability':float(p),'predicted':bool(p>=policy['threshold'])} for r,p in zip(rows,scores)]
            print(json.dumps({'model':name,'split':split,'recall':result['recall'],'fpr':result['fpr'],'different_decisions':result['different_decisions_from_batch']}),flush=True)
        assert before==sha(policy_path)
        entry['seconds']=time.time()-started;report['models'][name]=entry
        (ROOT/f'runs/online_predictions_{name}.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in predictions))
        (ROOT/'runs/online_comparison.json').write_text(json.dumps(report,indent=2))
        del detector;gc.collect();torch.cuda.empty_cache()
    print('ONLINE_REPLAY_COMPLETE',flush=True)

if __name__=='__main__':main()
