"""Predict normalized Zeek states; produces no control commands."""
import argparse,json,time,joblib
from pathlib import Path
import torch
from common_v3 import ROOT,LAB,LayaDetector,QwenDetector,sigmoid
from benchmark_v3 import TreeDetector
MODELS=['laya_v3_sft','laya_v3_normal_relation_sft','qwen3_06b_v3_lora','hist_gradient_boosting','extra_trees','isolation_forest']
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--model',choices=MODELS,default=MODELS[1]);ap.add_argument('--input',required=True);ap.add_argument('--output',required=True);args=ap.parse_args();torch.set_num_threads(4)
    policy=json.loads((ROOT/f'models/{args.model}_decision_policy.json').read_text())
    if args.model.startswith('laya'):
        folder='laya_v3_normal_relation' if args.model==MODELS[1] else args.model;detector=LayaDetector(ROOT/'models'/folder)
    elif args.model=='qwen3_06b_v3_lora':detector=QwenDetector(LAB/'v2/models/qwen3-0.6b',adapter=ROOT/'models/qwen3_06b_v3_lora')
    else:detector=TreeDetector(**joblib.load(ROOT/f'models/{args.model}.joblib'))
    out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);n=0
    with Path(args.input).open() as src,out.open('w') as dst:
        for line in src:
            row=json.loads(line);row={'id':row.get('id','inference'),'state':row.get('state',row),'y':0};start=time.perf_counter()
            raw=detector.score([row],batch_size=1)[0];score=float(raw if isinstance(detector,TreeDetector) else sigmoid(raw/policy['temperature']))
            dst.write(json.dumps({'id':row['id'],'threat':score>=policy['threshold'],'score':score,'threshold':policy['threshold'],
                'score_kind':'normal_training_anomaly_percentile' if args.model=='isolation_forest' else 'binary_model_probability_estimate',
                'decision_ms':(time.perf_counter()-start)*1000,'control_executed':False})+'\n');n+=1
    print(json.dumps({'decisions':n,'model':args.model,'output':str(out)}))
if __name__=='__main__':main()
