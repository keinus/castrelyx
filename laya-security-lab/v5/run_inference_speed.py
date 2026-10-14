"""One-shot, sequential inference measurements. Does not call training or finishing workers."""
import argparse
import json
import os
import subprocess
import time
from common import ROOT, LAB, CONFIGS, write_json, digest


def main():
    p=argparse.ArgumentParser();p.add_argument('--gpu-uuid',required=True);args=p.parse_args()
    out=ROOT/'speed';out.mkdir(exist_ok=True)
    tasks=[(n,20261005,False) for n in CONFIGS]+[('laya',20261005,False),('laya',20261006,True)]
    state=dict(pid=os.getpid(),status='running',training=False,started=time.time(),completed=[],total=len(tasks),
               protocol_sha256=digest(out/'protocol.json'))
    write_json(out/'status.json',state)
    env=dict(os.environ,CUDA_VISIBLE_DEVICES=args.gpu_uuid,PYTHONHASHSEED='0',OMP_NUM_THREADS='4',MKL_NUM_THREADS='4',
             TOKENIZERS_PARALLELISM='false',V5_ATTENTION='sdpa')
    for model,seed,latency_only in tasks:
        target=out/f'{model}_seed{seed}.json'
        if target.exists():
            saved=json.loads(target.read_text())
            if saved['status']!='complete' or saved['protocol_sha256']!=state['protocol_sha256']:
                raise RuntimeError('Conflicting existing timing: '+str(target))
            state['completed'].append([model,seed]);continue
        state.update(current=[model,seed],updated=time.time());write_json(out/'status.json',state)
        python=LAB/('.venv/bin/python' if model=='laya' else '.venv-flow/bin/python')
        command=[str(python),'-u',str(ROOT/'inference_speed.py'),'--model',model,'--seed',str(seed),'--output',str(target)]
        if latency_only: command+=['--latency-only']
        with (out/f'{model}_seed{seed}.log').open('w') as f:
            r=subprocess.run(command,cwd=LAB,env=env,stdout=f,stderr=subprocess.STDOUT)
        if r.returncode:
            state.update(status='failed',returncode=r.returncode,updated=time.time());write_json(out/'status.json',state);return
        state['completed'].append([model,seed]);write_json(out/'status.json',state)
    state.update(status='complete',current=None,updated=time.time());write_json(out/'status.json',state)


if __name__=='__main__':main()
