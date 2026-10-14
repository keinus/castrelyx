"""Exercise saved-model CLI after benchmarking, without changing decision policies."""
from pathlib import Path
import json,subprocess,sys,hashlib
ROOT=Path(__file__).resolve().parent

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    selected=[]
    for split in ['known_test','unseen_test','external_known_test']:
        rows=[json.loads(s) for s in (ROOT/f'data/{split}.jsonl').read_text().splitlines()]
        for label in [0,1]:selected += [r for r in rows if r['y']==label][:6]
    sample=ROOT/'runs/cli_smoke_input.jsonl';sample.write_text(''.join(json.dumps({'id':r['id'],'state':r['state']})+'\n' for r in selected))
    report={'cases':len(selected),'models':{},'purpose':'Reload saved models and compare CLI binary decisions with the batch benchmark; no threshold tuning.'}
    for name in ['laya_v3_sft','laya_v3_normal_relation_sft','qwen3_06b_v3_lora','extra_trees','hist_gradient_boosting','isolation_forest']:
        policy=ROOT/f'models/{name}_decision_policy.json';before=sha(policy);dest=ROOT/f'runs/cli_smoke_{name}.jsonl'
        subprocess.run([sys.executable,str(ROOT/'infer_v3.py'),'--model',name,'--input',str(sample),'--output',str(dest)],check=True)
        reference_path=ROOT/f'runs/online_predictions_{name}.jsonl'
        if not reference_path.exists():reference_path=ROOT/f'runs/predictions_{name}.jsonl'
        reference={r['id']:r for r in [json.loads(s) for s in reference_path.read_text().splitlines()]}
        actual=[json.loads(s) for s in dest.read_text().splitlines()]
        assert len(actual)==len(selected) and [r['id'] for r in actual]==[r['id'] for r in selected]
        assert all(r['control_executed'] is False for r in actual)
        assert sha(policy)==before
        differences=[{'id':r['id'],'cli':r['threat'],'benchmark':reference[r['id']]['predicted']} for r in actual if r['threat']!=reference[r['id']]['predicted']]
        report['models'][name]={'reference_file':reference_path.name,'decision_mismatches':differences,'max_absolute_score_difference':max(abs(r['score']-reference[r['id']]['probability']) for r in actual),'unchanged_policy_sha256':before}
    report['all_decisions_match']=all(not r['decision_mismatches'] for r in report['models'].values())
    (ROOT/'runs/cli_smoke.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
    if not report['all_decisions_match']:raise RuntimeError('CLI/batch decisions differ; inspect numerical margins before use')

if __name__=='__main__':main()
