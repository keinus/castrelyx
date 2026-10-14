"""Resolve short-burst load ambiguity and compare the custom adapter to upstream."""
from pathlib import Path
import argparse,gc,json,random,time,sys
ROOT=Path(__file__).resolve().parent
# The pinned upstream wheel omits its new backends subpackage; use its full source tree.
sys.path.insert(0,str(ROOT/'vendor/laya'))
import torch
from model_io import Predictor
from contract import QUESTIONS
from benchmark import read,queue_test


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--parity-only',action='store_true');args=ap.parse_args()
    torch.set_num_threads(4);cases=read('test');random.Random(20261004).shuffle(cases)
    if not args.parity_only:
        predictor=Predictor(str(ROOT/'models/security-sft'),'cuda:0')
        for case in cases[:20]:predictor.predict(case['state'])
        results=[]
        for rate in (10,25,50):
            result=queue_test(predictor,cases,rate,n=20*rate)
            result['offered_duration_seconds']=20;result['cases_order']='deterministically shuffled mixed CIC and synthetic test cases, cycled'
            results.append(result);print(json.dumps(result),flush=True)
        (ROOT/'runs/sustained_load.json').write_text(json.dumps(results,indent=2))
        del predictor;gc.collect();torch.cuda.empty_cache()
    # This checks numerical/label parity, not general security model quality.
    from laya.agent import Agent
    official=Agent(str(ROOT/'models/base'),device='cuda:0',backend='eager')
    predictor=Predictor(str(ROOT/'models/base'),'cuda:0');predictor.cfg['max_len']=1024
    upstream_compile_default=official.model.encoder.config.reference_compile
    # Match the training/benchmark kernel setting before checking decoder equivalence.
    # The upstream eager constructor forces False; our saved config uses None/automatic.
    official.model.encoder.config.reference_compile=predictor.model.encoder.config.reference_compile
    comparisons=[]
    for case in cases[:8]:
        a=official.predict(case['state'],QUESTIONS,max_len=1024,head_max_len=192)['answers'];b=predictor.predict(case['state'])['answers']
        delta=max(abs(a['response']['probabilities'][k]-b['response']['probabilities'][k]) for k in b['response']['probabilities'])
        item={'case_id':case['case_id'],'response_choice_equal':a['response']['choice']==b['response']['choice'],
            'max_response_probability_delta':delta,'threat_probability_delta':abs(a['threat']['noul']-b['threat']['noul']),
            'urgency_class_equal':max(a['urgency']['probabilities'],key=a['urgency']['probabilities'].get)==str(b['urgency']['score'])}
        comparisons.append(item)
    result={'cases':len(comparisons),'checks':comparisons,'passed':all(r['response_choice_equal'] and r['urgency_class_equal'] and r['max_response_probability_delta']<.000051 and r['threat_probability_delta']<.000051 for r in comparisons),
        'upstream_default_reference_compile':upstream_compile_default,'matched_reference_compile':predictor.model.encoder.config.reference_compile,
        'urgency_contract':'Our score is argmax class 0..2; upstream score is expected value. Compare class probability argmax.'}
    (ROOT/'runs/upstream_parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
    if not result['passed']:raise RuntimeError('Upstream adapter parity failed')

if __name__=='__main__':main()
