"""Verify completed batch results and preservation of the previous v3 artifacts."""
from pathlib import Path
from collections import Counter
import json,math,hashlib
ROOT=Path(__file__).resolve().parent;V3=ROOT.parent

def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest()

def read(p):return [json.loads(s) for s in p.read_text().splitlines()]

def main():
    assert 'BATCH_BENCHMARK_COMPLETE' in (ROOT/'runs/benchmark.log').read_text()
    data=json.loads((ROOT/'runs/comparison.json').read_text());assert len(data['models'])==2
    expected={r['id']:r for split in data['data_sha256'] for r in read(V3/f'data/{split}.jsonl')}
    for split,h in data['data_sha256'].items():assert h==sha(V3/f'data/{split}.jsonl')
    for name,model in data['models'].items():
        assert len(model['offline'])==5 and len(model['load'])==5
        policy=V3/f'models/{name}_decision_policy.json';threshold=json.loads(policy.read_text())['threshold']
        assert sha(policy)==model['identity']['policy_sha256']
        weight=V3/('models/laya_v3_normal_relation/model.safetensors' if name.startswith('laya') else 'models/qwen3_06b_v3_lora/adapter_model.safetensors')
        assert sha(weight)==model['identity']['weights_sha256']
        for config in model['offline']:
            if 'error' in config:continue
            rows=read(ROOT/f"runs/predictions_{name}_b{config['batch_size']}.jsonl")
            assert len(rows)==len(expected) and {r['id'] for r in rows}==set(expected)
            assert all(math.isfinite(r['probability']) and 0<=r['probability']<=1 and r['gold']==expected[r['id']]['y'] and r['predicted']==(r['probability']>=threshold) for r in rows)
            assert all(1<=r['batch_size']<=config['batch_size'] for r in rows)
            for split,m in config['tests'].items():
                selected=[r for r in rows if r['split']==split]
                for key,pred,gold in [('tp',1,1),('fp',1,0),('fn',0,1),('tn',0,0)]:assert sum(r['predicted']==pred and r['gold']==gold for r in selected)==m[key]
        for config in model['load']:
            rows=read(ROOT/f"runs/load_{name}_b{config['max_batch_size']}.jsonl")
            assert len(rows)==config['requests']==500 and [r['request'] for r in rows]==list(range(500))
            assert all(0<=r['queue_ms'] and 1<=r['batch_size']<=config['max_batch_size'] and abs(r['queue_ms']+r['service_ms']-r['response_ms'])<.001 for r in rows)
    source=read(V3/'runs/cli_smoke_input.jsonl');actual=read(ROOT/'runs/inference_b16.jsonl')
    assert len(source)==len(actual)==36 and [r['id'] for r in source]==[r['id'] for r in actual]
    assert Counter(r['batch_id'] for r in actual)=={0:16,1:16,2:4}
    assert all(r['control_executed'] is False and r['model_batch_ms']>=r['amortized_compute_ms'] for r in actual)
    old=json.loads((V3/'runs/audit.json').read_text())
    for f in old['files']:assert sha(V3/f['path'])==f['sha256'],f['path']
    paths=[p for p in ROOT.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.name not in ['verification.json','verification.log'] and p.suffix!='.pid']
    result={'verified':True,'models':2,'batch_sizes':[1,4,8,16,32],'cases_per_configuration':len(expected),
            'previous_v3_artifacts_unchanged':len(old['files']),'cli_cases':len(actual),'cli_batch_lengths':[16,16,4],
            'files':[{'path':p.relative_to(ROOT).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(paths)]}
    (ROOT/'runs/verification.json').write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k!='files'}))

if __name__=='__main__':main()
