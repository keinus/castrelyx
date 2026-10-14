"""Persistent, fail-closed benchmark worker; one model process at a time per GPU."""
import argparse, json, os, subprocess, time
from common import ROOT, LAB, SEEDS, CONFIGS, write_json, digest

def main():
    p=argparse.ArgumentParser();p.add_argument('worker',choices=['flow','laya']);p.add_argument('--gpu',required=True)
    p.add_argument('--micro-batch',type=int,default=2);p.add_argument('--eval-batch',type=int,default=8)
    args=p.parse_args()
    jobs=[(name,seed) for name in (list(CONFIGS) if args.worker=='flow' else ['laya']) for seed in SEEDS]
    env=dict(os.environ,CUDA_VISIBLE_DEVICES=args.gpu,PYTHONHASHSEED='0',OMP_NUM_THREADS='4',MKL_NUM_THREADS='4',TOKENIZERS_PARALLELISM='false')
    status_file=ROOT/f'{args.worker}_worker.json'
    state=dict(pid=os.getpid(),worker=args.worker,gpu=args.gpu,started=time.time(),protocol_sha256=digest(ROOT/'protocol.json'),status='running',completed=[])
    for name,seed in jobs:
        folder=ROOT/f'runs/{name}/seed{seed}'
        if (folder/'training.json').exists():
            state['completed'].append([name,seed]);continue
        command=[str(LAB/('.venv-flow/bin/python' if args.worker=='flow' else '.venv/bin/python')),'-u',str(ROOT/('train_flow.py' if args.worker=='flow' else 'train_laya.py')),'--seed',str(seed)]
        if args.worker=='flow':
            command+=['--model',name]
            if name.startswith('bert'):command+=['--no-jit']
        else:
            command+=['--micro-batch',str(args.micro_batch),'--eval-batch',str(args.eval_batch)]
            if (folder/'resume.pt').exists():command+=['--resume']
        state.update(current=[name,seed],command=command,updated=time.time());write_json(status_file,state)
        log=ROOT/f'runs/{name}_seed{seed}.log'
        with open(log,'a',buffering=1) as f:
            result=subprocess.run(command,cwd=LAB,env=env,stdout=f,stderr=subprocess.STDOUT)
        if result.returncode:
            state.update(status='failed',exit_code=result.returncode,log=str(log),updated=time.time());write_json(status_file,state)
            raise SystemExit(result.returncode)
        state['completed'].append([name,seed]);write_json(status_file,state)
    state.update(status='complete',current=None,updated=time.time());write_json(status_file,state)

if __name__=='__main__':main()
