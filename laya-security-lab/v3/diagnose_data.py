"""Describe startup exclusions and identical normalized inputs; never rewrite splits."""
from pathlib import Path
from collections import Counter,defaultdict
import hashlib,json
from prepare_data import parse
ROOT=Path(__file__).resolve().parent

def main():
    out={}
    for cap in ['CTU-IoT-Malware-Capture-42-1','CTU-IoT-Malware-Capture-20-1','CTU-IoT-Malware-Capture-21-1']:
        rows,_=parse(ROOT/'data/raw/zeek'/cap/'bro/conn.log.labeled');origin=min(r['available'] for r in rows);attacks=[r for r in rows if r['y']]
        out[cap]={'rows':len(rows),'attack_flows':len(attacks),'publisher_details':dict(Counter(r['attack'] for r in rows)),
                  'attack_in_first_60_seconds_of_block':sum(((r['available']-origin)//5)*5-((r['available']-origin)//1800)*1800<60 for r in attacks)}
    (ROOT/'runs/source_label_diagnostics.json').write_text(json.dumps(out,indent=2))
    def read(s):return [json.loads(x) for x in (ROOT/f'data/{s}.jsonl').read_text().splitlines()]
    def sig(r):return hashlib.sha256(json.dumps(r['state'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
    train=defaultdict(set)
    for r in read('train'):train[sig(r)].add(r['y'])
    out={'definition':'Exact canonicalized normalized state equality, after removing identities and metadata; descriptive only, no post-hoc data changes. Same behavior across unseen malware families is possible.',
         'train_unique_states':len(train),'train_conflicting_label_states':sum(len(v)>1 for v in train.values()),'splits':{}}
    for split in ['validation','proxy_validation','calibration','known_test','unseen_test','external_known_test']:
        rows=read(split);matched=[r for r in rows if sig(r) in train]
        out['splits'][split]={'n':len(rows),'unique_states':len({sig(r) for r in rows}),'identical_to_train':len(matched),'identical_attack_states':sum(r['y'] for r in matched),'identical_by_family':dict(Counter(r['family'] for r in matched))}
    (ROOT/'runs/input_duplicate_diagnostics.json').write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))

if __name__=='__main__':main()
