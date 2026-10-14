"""Finish this one experiment after its training processes complete; not a recurring task."""
import json, os, subprocess, time, zipfile
from pathlib import Path
from common import ROOT, LAB, CONFIGS, SEEDS, write_json, digest

def main():
    status=ROOT/'completion.json'
    state=dict(pid=os.getpid(),status='waiting_for_training',started=time.time())
    write_json(status,state)
    while True:
        workers={name:json.loads((ROOT/f'{name}_worker.json').read_text()) for name in ['flow','laya']}
        failed=[name for name,m in workers.items() if m['status']=='failed']
        if failed:
            state.update(status='failed',reason='training worker failed',workers=failed,updated=time.time());write_json(status,state);return
        for name,m in workers.items():
            if m['status']=='running':
                try:os.kill(m['pid'],0)
                except ProcessLookupError:
                    state.update(status='failed',reason='training worker process disappeared',worker=name,updated=time.time());write_json(status,state);return
        grid=json.loads((ROOT/'grid_worker.json').read_text()) if (ROOT/'grid_worker.json').exists() else None
        if grid and grid['status']=='failed':
            state.update(status='failed',reason='learning-rate grid worker failed',updated=time.time());write_json(status,state);return
        if grid and grid['status']!='complete':
            try:os.kill(grid['pid'],0)
            except ProcessLookupError:
                state.update(status='failed',reason='learning-rate grid worker process disappeared',updated=time.time());write_json(status,state);return
        grid_done=grid and grid['status']=='complete'
        if all(m['status']=='complete' for m in workers.values()) and grid_done:break
        state.update(completed_runs=sum(len(m['completed']) for m in workers.values()),grid_completed=len(grid['completed'])+len(grid['diverged']) if grid else 0,grid_total=96,updated=time.time());write_json(status,state)
        time.sleep(30)
    env=dict(os.environ,CUDA_VISIBLE_DEVICES='0',PYTHONHASHSEED='0',OMP_NUM_THREADS='4',MKL_NUM_THREADS='4',TOKENIZERS_PARALLELISM='false',V5_ATTENTION='sdpa')
    for name in list(CONFIGS)+['laya']:
        target=ROOT/f'runs/{name}/seed{SEEDS[0]}/timing.json'
        if target.exists():continue
        state.update(status='timing',current=name,updated=time.time());write_json(status,state)
        python=LAB/('.venv/bin/python' if name=='laya' else '.venv-flow/bin/python')
        with open(ROOT/f'runs/timing_{name}.log','w') as log:
            r=subprocess.run([str(python),'-u',str(ROOT/'benchmark.py'),'--model',name],cwd=LAB,env=env,stdout=log,stderr=subprocess.STDOUT)
        if r.returncode:
            state.update(status='failed',reason='timing failed',model=name,updated=time.time());write_json(status,state);return
    state.update(status='validating_and_reporting',updated=time.time());write_json(status,state)
    with open(ROOT/'summary.log','w') as log:
        r=subprocess.run([str(LAB/'.venv/bin/python'),str(ROOT/'summarize.py')],cwd=LAB,stdout=log,stderr=subprocess.STDOUT)
    if r.returncode:
        state.update(status='failed',reason='final validation/report failed',updated=time.time());write_json(status,state);return
    # Small, inspectable results bundle: reports, scripts, data manifests and predictions.
    # Full dataset, weights and optimizer checkpoints remain on the experiment server.
    files=[*ROOT.glob('*.py'),*ROOT.glob('requirements-*.txt'),ROOT/'README.md',ROOT/'protocol.json',ROOT/'sensitivity_protocol.json',ROOT/'grid_worker.json',ROOT/'execution.json',ROOT/'runtime_provenance.json',ROOT/'RESULTS.ko.md',ROOT/'data/prepared/manifest.json']
    files += list((ROOT/'runs').rglob('*.json'))+list((ROOT/'runs').rglob('*.npz'))+list((ROOT/'runs').glob('*.csv'))
    files=[p for p in files if p.is_file()]
    inventory={str(p.relative_to(ROOT)):digest(p) for p in files}
    write_json(ROOT/'artifact_hashes.json',inventory)
    with zipfile.ZipFile(ROOT/'results_bundle.zip','w',compression=zipfile.ZIP_DEFLATED) as z:
        for p in files+[ROOT/'artifact_hashes.json']:z.write(p,str(p.relative_to(ROOT)))
    state.update(status='complete',current=None,updated=time.time(),report='RESULTS.ko.md',bundle_sha256=digest(ROOT/'results_bundle.zip'))
    write_json(status,state)
    print(json.dumps(state),flush=True)

if __name__=='__main__':main()
