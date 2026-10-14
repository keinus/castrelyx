"""Binary evidence classifier for v2 normalized state JSONL; never executes a control."""
import argparse,json,time,joblib
import torch
from pathlib import Path
from common_v2 import ROOT,LAB,LayaDetector,QwenDetector,sigmoid
from benchmark_v2 import TreeDetector

def main():
    torch.set_num_threads(4)
    ap=argparse.ArgumentParser();ap.add_argument('--model',default='laya_v2',choices=['laya_v2','qwen3_06b_lora','qwen3_17b_pretrained','extra_trees','hist_gradient_boosting','isolation_forest']);ap.add_argument('--input',required=True);ap.add_argument('--output',required=True);args=ap.parse_args()
    policy=json.loads((ROOT/f'models/{args.model}_decision_policy.json').read_text())
    if args.model=='laya_v2':model=LayaDetector(ROOT/'models/laya-v2')
    elif args.model=='qwen3_06b_lora':model=QwenDetector(ROOT/'models/qwen3-0.6b',adapter=ROOT/'models/qwen3-0.6b-lora')
    elif args.model=='qwen3_17b_pretrained':model=QwenDetector(ROOT/'models/qwen3-1.7b')
    else:
        saved=joblib.load(ROOT/f'models/{args.model}.joblib');model=TreeDetector(**saved)
    path=Path(args.output);path.parent.mkdir(parents=True,exist_ok=True);count=0
    with Path(args.input).open(encoding='utf8') as src,path.open('w',encoding='utf8') as out:
        for line in src:
            raw=json.loads(line);state=raw.get('state',raw);start=time.perf_counter()
            score=model.score([{'id':raw.get('id','inference'),'state':state,'y':0}],batch_size=1)[0]
            p=float(score if isinstance(model,TreeDetector) else sigmoid(score/policy['temperature']))
            kind='empirical_normal_anomaly_rank' if args.model=='isolation_forest' else 'model_binary_probability_estimate'
            out.write(json.dumps({'id':raw.get('id'),'threat':p>=policy['threshold'],'score':p,'score_kind':kind,'threshold':policy['threshold'],'decision_ms':(time.perf_counter()-start)*1000,'mode':'detector_only','control_executed':False})+'\n');count+=1
    print(json.dumps({'decisions':count,'output':str(path)}))

if __name__=='__main__':main()
