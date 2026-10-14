"""Record local dependency identity without authentication or environment secrets."""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parent;LAB=ROOT.parent

def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest()

def main():
    paths=[LAB/'model_io.py',LAB/'contract.py',LAB/'v2/common_v2.py']
    paths += sorted((LAB/'vendor/laya/laya').rglob('*.py'))
    for folder in ['models/base','v2/models/laya-v2','v2/models/qwen3-0.6b']:
        paths += [p for p in (LAB/folder).rglob('*') if p.is_file() and '.cache' not in p.parts]
    result={'paths_relative_to':'laya-security-lab','original_laya_revision':'7b928d828b7b0e022f929d9bd2e44165aa270148',
            'original_qwen3_06b_revision':'c1899de289a04d12100db370d81485cdf75e47ca',
            'pinned_laya_runtime_commit':'2e4d9c87e8b1621deb344eac7de5c7258f32f849',
            'files':[{'path':p.relative_to(LAB).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in paths]}
    (ROOT/'runs/runtime_dependencies.json').write_text(json.dumps(result,indent=2));print(json.dumps({'dependency_files':len(paths)}))

if __name__=='__main__':main()
