"""Reject numerically indistinguishable substitutions before auxiliary training."""
from collections import Counter,defaultdict
import json
from train_laya import auxiliary
from common_v3 import ROOT,read

def main():
    train={r['id']:r for r in read('train')};rows,n=auxiliary(list(train.values()));counts=Counter();identical=Counter();states=defaultdict(set)
    for r in rows:
        task=r['aux_task'];counts[task]+=1
        if r['y'] and task=='normal_continuation' and r['state']['candidate_next_flow']==train[r['source_case']]['state']['events'][-1]:identical[task]+=1
        states[json.dumps({'task':task,'state':r['state']},sort_keys=True)].add(r['y'])
    report={'examples':len(rows),'task_counts':dict(counts),'substituted_continuations_numerically_equal_to_original':dict(identical),
            'identical_input_conflicting_target_count':sum(len(v)>1 for v in states.values()),'note':'Auxiliary data quality audit. Final test is not read.'}
    (ROOT/'runs/auxiliary_quality.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
    assert not sum(identical.values()) and report['identical_input_conflicting_target_count']==0

if __name__=='__main__':main()
