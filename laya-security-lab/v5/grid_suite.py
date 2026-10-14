"""Additional paper-listed learning-rate search for all original architecture settings.

The fixed-setting experiment remains immutable. This is a disclosed post-hoc
reproduction sensitivity study, triggered by a non-convergent BERT run.
"""
import json, os, subprocess, time, shutil
from pathlib import Path
from common import ROOT, LAB, CONFIGS, SEEDS, write_json, digest

RATES=[.01,.0005,.0001,.00001] # .001 is the existing primary experiment.

def main():
    state=dict(pid=os.getpid(),status='waiting_for_primary_flow',started=time.time(),completed=[],diverged=[],total=96,
        protocol_sha256=digest(ROOT/'sensitivity_protocol.json'))
    status=ROOT/'grid_worker.json';write_json(status,state)
    while True:
        worker=json.loads((ROOT/'flow_worker.json').read_text())
        if worker['status']=='complete':break
        if worker['status']=='failed':
            state.update(status='failed',reason='primary flow worker failed',updated=time.time());write_json(status,state);return
        time.sleep(30)
    env=dict(os.environ,CUDA_VISIBLE_DEVICES='1',PYTHONHASHSEED='0',OMP_NUM_THREADS='4',MKL_NUM_THREADS='4')
    for lr in RATES:
        group=f'grid/lr_{lr:g}'
        for name in CONFIGS:
            for seed in SEEDS:
                key=[name,seed,lr];folder=ROOT/'runs'/group/name/f'seed{seed}'
                if (folder/'training.json').exists():state['completed'].append(key);continue
                if (folder/'diverged.json').exists():state['diverged'].append(key);continue
                if folder.exists():raise FileExistsError('Interrupted grid trial requires inspection: '+str(folder))
                command=[str(LAB/'.venv-flow/bin/python'),'-u',str(ROOT/'train_flow.py'),'--model',name,'--seed',str(seed),'--lr',str(lr),'--run-group',group]
                if name.startswith('bert'):command+=['--no-jit']
                state.update(status='running',current=key,updated=time.time());write_json(status,state)
                log=ROOT/'runs'/group/f'{name}_seed{seed}.log';log.parent.mkdir(parents=True,exist_ok=True)
                with log.open('w') as f:r=subprocess.run(command,cwd=LAB,env=env,stdout=f,stderr=subprocess.STDOUT)
                if r.returncode and '--no-jit' not in command and 'buffer_comparator.cc' in log.read_text(errors='replace'):
                    m=json.loads((folder/'running.json').read_text())
                    if m['history']:
                        state.update(status='failed',reason='compiler failure after completed epochs requires inspection',current=key,updated=time.time());write_json(status,state);return
                    diagnostic=ROOT/f'diagnostics/grid_lr_{lr:g}_{name}_seed{seed}_xla_failure'
                    diagnostic.parent.mkdir(exist_ok=True);folder.rename(diagnostic);shutil.copy2(log,diagnostic/'failure.log')
                    command+=['--no-jit']
                    with log.open('a') as f:r=subprocess.run(command,cwd=LAB,env=env,stdout=f,stderr=subprocess.STDOUT)
                if r.returncode:
                    tail=log.read_text(errors='replace')[-6000:]
                    if 'FloatingPointError: Nonfinite loss' in tail:
                        write_json(folder/'diverged.json',dict(model=name,seed=seed,lr=lr,status='numerically_diverged',log=str(log),exit_code=r.returncode))
                        state['diverged'].append(key)
                    else:
                        state.update(status='failed',reason='unhandled trial error',current=key,log=str(log),exit_code=r.returncode,updated=time.time());write_json(status,state);return
                else:state['completed'].append(key)
                write_json(status,state)
    state.update(status='complete',current=None,updated=time.time());write_json(status,state)

if __name__=='__main__':main()
