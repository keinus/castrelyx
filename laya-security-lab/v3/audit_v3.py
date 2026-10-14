from pathlib import Path
import json,hashlib
ROOT=Path(__file__).resolve().parent
def sha(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest()
def main():
    m=json.loads((ROOT/'data/manifest.json').read_text());c=json.loads((ROOT/'runs/comparison.json').read_text())
    assert m['protocol_sha256']==sha(ROOT/'protocol.json')
    for s,d in m['splits'].items():assert d['sha256']==sha(ROOT/f'data/{s}.jsonl')
    for capture in json.loads((ROOT/'data/source_manifest.json').read_text())['captures']:
        for f in capture['files']:assert sha(ROOT/f['path'])==f['sha256']
    for phase in ['training','benchmark','load']:assert (ROOT/f'runs/{phase}.exit').read_text().strip()=='0'
    steps=[]
    for mode in ['sft','normal_relation']:
        r=json.loads((ROOT/f'runs/laya_{mode}_complete.json').read_text());assert r['status']=='complete';steps.append(r['steps'])
        h=sha(ROOT/f'models/laya_v3_{mode}/model.safetensors');assert h==r['weights_sha256'] and h!=sha(ROOT.parent/'models/base/model.safetensors')
        name='laya_v3_sft' if mode=='sft' else 'laya_v3_normal_relation_sft';assert h==c['models'][name]['weights_sha256']
    assert len(set(steps))==1
    q=json.loads((ROOT/'runs/qwen_complete.json').read_text());assert q['status']=='complete'
    assert sha(ROOT/'models/qwen3_06b_v3_lora/adapter_model.safetensors')==q['adapter_sha256']==c['models']['qwen3_06b_v3_lora']['adapter_sha256']
    expected={r['id']:r for s in ['known_test','unseen_test','external_known_test'] for r in [json.loads(x) for x in (ROOT/f'data/{s}.jsonl').read_text().splitlines()]}
    assert len(c['models'])==7
    for name,model in c['models'].items():
        rows=[json.loads(s) for s in (ROOT/f'runs/predictions_{name}.jsonl').read_text().splitlines()]
        assert len(rows)==len(expected) and {r['id'] for r in rows}==set(expected)
        for r in rows:
            assert r['gold']==expected[r['id']]['y'] and r['split']==expected[r['id']]['split']
            assert r['predicted']==(r['probability']>=model['policy']['threshold'])
        for split,metric in model['tests'].items():
            subset=[r for r in rows if r['split']==split]
            for key,pred,gold in [('tp',1,1),('fp',1,0),('fn',0,1),('tn',0,0)]:assert sum(r['predicted']==pred and r['gold']==gold for r in subset)==metric[key]
    train={r['id']:r for r in [json.loads(s) for s in (ROOT/'data/train.jsonl').read_text().splitlines()]}
    aux=json.loads((ROOT/'runs/auxiliary_provenance.json').read_text())
    for r in aux['cases']:
        for key in ['source_case','donor_case']:
            a=train[r[key]];assert not a['y'] and not a['provenance']['context_has_attack']
    quality=json.loads((ROOT/'runs/auxiliary_quality.json').read_text())
    assert quality['examples']==len(aux['cases']) and not sum(quality['substituted_continuations_numerically_equal_to_original'].values())
    assert quality['identical_input_conflicting_target_count']==0
    for name,rs in json.loads((ROOT/'runs/load_comparison.json').read_text())['models'].items():
        for r in rs:
            rows=[json.loads(s) for s in (ROOT/f"runs/load_{name}_{r['offered_rps']}.jsonl").read_text().splitlines()]
            assert len(rows)==r['requests']==r['offered_rps']*10
            assert all(abs(x['queue_ms']+x['service_ms']-x['response_ms'])<.001 for x in rows)
    online=json.loads((ROOT/'runs/online_comparison.json').read_text())
    assert online['batch_size']==1 and not online['policy_refitted'] and len(online['models'])==3
    for name,model in online['models'].items():
        assert model['policy_sha256']==sha(ROOT/f'models/{name}_decision_policy.json')
        rows=[json.loads(s) for s in (ROOT/f'runs/online_predictions_{name}.jsonl').read_text().splitlines()]
        assert len(rows)==len(expected) and {r['id'] for r in rows}==set(expected)
        for r in rows:assert r['gold']==expected[r['id']]['y'] and r['predicted']==(r['probability']>=c['models'][name]['policy']['threshold'])
        for split,metric in model['tests'].items():
            subset=[r for r in rows if r['split']==split]
            for key,pred,gold in [('tp',1,1),('fp',1,0),('fn',0,1),('tn',0,0)]:assert sum(r['predicted']==pred and r['gold']==gold for r in subset)==metric[key]
    assert json.loads((ROOT/'runs/cli_smoke.json').read_text())['all_decisions_match']
    paths=[p for p in ROOT.iterdir() if p.suffix in ['.py','.json','.sh','.md']]
    for directory in ['models','runs']:
        paths += [p for p in (ROOT/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.name not in ['audit.json','audit.log','finish-launcher.log','repair-launcher.log'] and p.suffix not in ['.pid']]
    paths += list((ROOT/'data').glob('*.json'))
    result={'verified':True,'models':7,'cases_per_model':len(expected),'matched_laya_steps':steps[0],
            'files':[{'path':p.relative_to(ROOT).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(paths)]}
    (ROOT/'runs/audit.json').write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k!='files'}))
if __name__=='__main__':main()
