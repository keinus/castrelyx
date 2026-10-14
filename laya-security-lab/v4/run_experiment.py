"""Resumable experiment matrix. Completed artifacts are never silently replaced."""
import argparse,json,subprocess,sys
from pathlib import Path
from acquire import ROOT
from common_v4 import output_dir

def run(arguments):
    arguments=list(map(str,arguments))
    print('RUN',json.dumps(arguments),flush=True)
    subprocess.run([sys.executable,*arguments],cwd=ROOT,check=True)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--experiment',required=True);ap.add_argument('--seeds',type=int,nargs='+',default=[20261005])
    ap.add_argument('--epochs',type=int,default=3);ap.add_argument('--device',default='cuda:0');ap.add_argument('--queue',action='store_true')
    ap.add_argument('--views',nargs='+',choices=['single','aggregate','sequence','time_removed','unlinked'])
    args=ap.parse_args();run(['test_protocol.py','--experiment',args.experiment])
    matrix=[('xgboost','single'),('xgboost','sequence'),('rf','sequence'),('gru','sequence'),('laya','single'),('laya','sequence'),('qwen','sequence')]
    if args.views:matrix=[(model,view) for model in ['xgboost','rf','gru','laya','qwen'] for view in args.views]
    for seed in args.seeds:
        for model,view in matrix:
            folder=output_dir(args.experiment,view,model,seed)
            if not (folder/'training.json').exists():
                run(['train_v4.py','--experiment',args.experiment,'--model',model,'--view',view,'--seed',seed,'--epochs',args.epochs,'--device',args.device])
            if not (folder/'evaluation.json').exists():
                command=['benchmark_v4.py','--folder',folder,'--device',args.device]
                if args.queue and model in ('laya','qwen') and view=='sequence':command.append('--queue')
                run(command)
    run(['summarize.py','--experiment',args.experiment])
if __name__=='__main__':main()
