"""Refresh constructed rows only; network selection is unchanged."""
from prepare_data import ROOT,SEED,joint_cases
from collections import Counter
import json,hashlib,random
folder=ROOT/'data';manifest=json.loads((folder/'manifest.json').read_text());joint=joint_cases()
for name in manifest['splits']:
    path=folder/(name+'.jsonl')
    existing=[json.loads(l) for l in path.read_text(encoding='utf8').splitlines()]
    rows=[r for r in existing if r['source']!='constructed_joint']+[r for r in joint if r['split']==name]
    rows.sort(key=lambda r:r['id']);random.Random(str(SEED)+name).shuffle(rows)
    path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf8')
    manifest['splits'][name]={'cases':len(rows),'groups':len({r['group'] for r in rows}),'labels':dict(Counter(r['y'] for r in rows)),
        'families':dict(Counter(r['family'] for r in rows)),'sources':dict(Counter(r['source'] for r in rows)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
(folder/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
print(json.dumps(manifest['splits'],indent=2))
