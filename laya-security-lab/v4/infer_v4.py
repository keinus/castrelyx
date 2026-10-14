"""Bounded offline inference of prepared network states; never applies router actions."""
import argparse,json,sys
from pathlib import Path
from common_v4 import Detector
from benchmark_v4 import sigmoid

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--folder',type=Path,required=True);ap.add_argument('--input',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True);ap.add_argument('--batch-size',type=int,default=1);ap.add_argument('--device',default='cuda:0')
    ap.add_argument('--fpr-target',choices=['0.01','0.001'],default='0.01');args=ap.parse_args()
    if args.batch_size<1:raise ValueError('Positive batch size required')
    if args.output.exists():raise FileExistsError(args.output)
    detector=Detector(args.folder,args.device);policy=json.loads((args.folder/'policies.json').read_text())[args.fpr_target]
    if args.batch_size!=policy['inference_batch_size']:
        print('Batch size differs from calibration; floating-point boundary decisions may change. See batch_stability.',file=sys.stderr)
    with args.input.open(encoding='utf8') as source,args.output.open('w',encoding='utf8') as output:
        batch=[]
        def flush():
            scores=sigmoid(detector.score(batch,args.batch_size)/policy['temperature'])
            for row,score in zip(batch,scores):
                output.write(json.dumps({'id':row.get('id'),'threat':bool(score>=policy['threshold']),'score':float(score),
                    'threshold':policy['threshold'],'response':None,'response_status':'not trained: dataset has no verified optimal-action labels'})+'\n')
            batch.clear()
        for line in source:
            if not line.strip():continue
            row=json.loads(line)
            if not isinstance(row.get('state'),dict):raise ValueError('Expected v4 canonical state')
            # Public inference requires no labels. Targets are ignored by every detector at inference.
            row.setdefault('y',0);row.setdefault('id','input');batch.append(row)
            if len(batch)==args.batch_size:flush()
        if batch:flush()
if __name__=='__main__':main()
