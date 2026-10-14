"""Verify split/checkpoint provenance and produce a compact immutable run inventory."""
from pathlib import Path
import hashlib,json

ROOT=Path(__file__).resolve().parent

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def main():
    manifest=json.loads((ROOT/'data/processed/dataset_manifest.json').read_text())
    for split,spec in manifest['splits'].items():
        assert sha(ROOT/f'data/processed/{split}.jsonl')==spec['sha256'],split
    base=sha(ROOT/'models/base/model.safetensors');trained=sha(ROOT/'models/security-sft/model.safetensors')
    assert base!=trained,'Checkpoint unchanged'
    complete=json.loads((ROOT/'runs/training_complete.json').read_text());assert complete['status']=='complete'
    assert (ROOT/'runs/train.exit').read_text().strip()=='0'
    bench=json.loads((ROOT/'runs/benchmark.json').read_text())
    assert bench['models']['base']['weights_sha256']==base
    assert bench['models']['finetuned']['weights_sha256']==trained
    assert all(m['all']['exceptions']==0 for m in bench['models'].values())
    assert (ROOT/'runs/benchmark.exit').read_text().strip()=='0'
    assert (ROOT/'runs/extended.exit').read_text().strip()=='0'
    assert json.loads((ROOT/'runs/upstream_parity.json').read_text())['passed']
    assert all(r['exceptions']==0 for r in json.loads((ROOT/'runs/sustained_load.json').read_text()))
    rows=[json.loads(line) for line in (ROOT/'runs/otrf_raw_decisions.jsonl').read_text().splitlines()]
    assert rows and all(not r['response_plan']['executed'] for r in rows)
    assert any(r['relations']['matched_process_flows'] for r in rows)
    assert all(r['response_plan']['action']=='COLLECT_MORE' for r in rows if r['quality']['buffer_dropped']>0)
    paths=list(ROOT.glob('*.py'))+list(ROOT.glob('*.sh'))+list(ROOT.glob('*.json'))+list(ROOT.glob('*.md'))
    paths+=list((ROOT/'runs').glob('*.json'))+list((ROOT/'runs').glob('*.jsonl'))+list((ROOT/'runs').glob('*.log'))+list((ROOT/'models/security-sft').glob('*.json'))+list((ROOT/'models/security-sft').glob('*.md'))
    paths=[p for p in paths if p.name!='artifact_inventory.json']
    result={'verified':True,'base_weights_sha256':base,'trained_weights_sha256':trained,
        'actual_raw_log_decisions':len(rows),'decisions_with_tuple_time_join':sum(r['relations']['matched_process_flows']>0 for r in rows),
        'files':[{'path':str(p.relative_to(ROOT)).replace('\\','/'),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(paths)]}
    (ROOT/'runs/artifact_inventory.json').write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k!='files'}))

if __name__=='__main__':main()
