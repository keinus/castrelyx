"""Check a completed v2 experiment against source hashes and saved predictions."""
from pathlib import Path
import json,hashlib
ROOT=Path(__file__).resolve().parent
def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
def main():
    manifest=json.loads((ROOT/'data/manifest.json').read_text());comparison=json.loads((ROOT/'runs/comparison.json').read_text())
    for split,data in manifest['splits'].items():assert sha(ROOT/f'data/{split}.jsonl')==data['sha256']
    for name in ('training','benchmark','load'):assert (ROOT/f'runs/{name}.exit').read_text().strip()=='0',name
    for name in ('laya','qwen'):assert json.loads((ROOT/f'runs/{name}_complete.json').read_text())['status']=='complete'
    assert len(comparison['models'])==9
    test_names=['known_test','unseen_test','joint_known_test','joint_unseen_test']
    expected={r['id']:r for name in test_names for r in [json.loads(l) for l in (ROOT/f'data/{name}.jsonl').read_text().splitlines()]}
    for name,model in comparison['models'].items():
        rows=[json.loads(l) for l in (ROOT/f'runs/predictions_{name}.jsonl').read_text().splitlines()]
        assert len(rows)==len(expected) and {r['id'] for r in rows}==set(expected),name
        assert all(r['gold']==expected[r['id']]['y'] and r['split']==expected[r['id']]['split'] for r in rows),name
        assert all(r['predicted']==(r['probability']>=model['policy']['threshold']) for r in rows),name
        for test in test_names:
            r=[x for x in rows if x['split']==test];m=model['tests'][test]
            assert sum(x['predicted'] and x['gold'] for x in r)==m['tp']
            assert sum(x['predicted'] and not x['gold'] for x in r)==m['fp']
            assert sum(not x['predicted'] and x['gold'] for x in r)==m['fn']
            assert sum(not x['predicted'] and not x['gold'] for x in r)==m['tn']
        assert model['exceptions']==0
    trained=sha(ROOT/'models/laya-v2/model.safetensors')
    assert trained==json.loads((ROOT/'runs/laya_complete.json').read_text())['trained_sha256']
    assert trained!=sha(ROOT.parent/'models/base/model.safetensors')
    adapter=sha(ROOT/'models/qwen3-0.6b-lora/adapter_model.safetensors')
    assert adapter==json.loads((ROOT/'runs/qwen_complete.json').read_text())['adapter_sha256']
    assert adapter==comparison['models']['qwen3_06b_lora']['adapter_sha256']
    assert trained==comparison['models']['laya_v2']['weights_sha256']
    load=json.loads((ROOT/'runs/load_comparison.json').read_text())
    assert set(load['models'])=={'laya_v2','qwen3_06b_lora'}
    for name,results in load['models'].items():
        assert [r['offered_rps'] for r in results]==[10,25,50]
        for r in results:
            raw=[json.loads(s) for s in (ROOT/f"runs/load_{name}_{r['offered_rps']}.jsonl").read_text().splitlines()]
            assert len(raw)==r['requests']==r['offered_rps']*10
            assert all(abs(x['queue_ms']+x['service_ms']-x['response_ms'])<.001 for x in raw)
    paths=list(ROOT.glob('*.py'))+list(ROOT.glob('*.json'))+list(ROOT.glob('*.sh'))+list(ROOT.glob('*.md'))
    paths+=list((ROOT/'runs').glob('*.json'))+list((ROOT/'runs').glob('*.jsonl'))+list((ROOT/'runs').glob('*.log'))+list((ROOT/'runs').glob('*.txt'))+list((ROOT/'runs').glob('*.exit'))
    paths+=list((ROOT/'models').glob('*.json'))+list((ROOT/'models').glob('*.joblib'))
    for name in ('laya-v2','qwen3-0.6b-lora'):paths+=list(p for p in (ROOT/'models'/name).rglob('*') if p.is_file())
    paths=[p for p in paths if p.name!='audit.json']
    result={'verified':True,'models':len(comparison['models']),'test_cases_per_model':len(expected),'trained_weights_sha256':trained,
        'files':[{'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(paths)]}
    (ROOT/'runs/audit.json').write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k!='files'}))
if __name__=='__main__':main()
