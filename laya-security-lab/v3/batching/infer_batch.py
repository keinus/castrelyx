"""Process normalized state JSONL in explicit batches. This is an offline file CLI."""
from pathlib import Path
from itertools import islice
import argparse,json,time
import torch
from batch_runtime import MODELS,positive_int,load_model,scores

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--model',choices=MODELS,default=MODELS[0])
    ap.add_argument('--batch-size',type=positive_int,default=1)
    ap.add_argument('--input',required=True);ap.add_argument('--output',required=True)
    args=ap.parse_args();torch.set_num_threads(4)
    src_path=Path(args.input);dest=Path(args.output)
    if src_path.resolve()==dest.resolve():ap.error('Input and output paths must differ')
    detector,policy,identity=load_model(args.model)
    dest.parent.mkdir(parents=True,exist_ok=True);n=0;batch_id=0
    with src_path.open(encoding='utf8') as src,dest.open('w',encoding='utf8') as out:
        while lines:=list(islice(src,args.batch_size)):
            rows=[]
            for line in lines:
                row=json.loads(line);rows.append({'id':row.get('id',f'inference-{n+len(rows)}'),'state':row.get('state',row),'y':0})
            started=time.perf_counter();values=scores(detector,policy,rows);elapsed=(time.perf_counter()-started)*1000
            assert len(values)==len(rows)
            for row,p in zip(rows,values):
                out.write(json.dumps({'id':row['id'],'threat':bool(p>=policy['threshold']),'score':float(p),'threshold':policy['threshold'],
                                      'batch_id':batch_id,'batch_size':len(rows),'model_batch_ms':elapsed,'amortized_compute_ms':elapsed/len(rows),
                                      'score_kind':'binary_model_probability_estimate','control_executed':False})+'\n')
            n+=len(rows);batch_id+=1
    print(json.dumps({'model':args.model,'decisions':n,'batches':batch_id,'max_batch_size':args.batch_size,'identity':identity,
                      'scope':'File input already available. model_batch_ms excludes waiting for incoming traffic; amortized time is not request latency.'}))

if __name__=='__main__':main()
