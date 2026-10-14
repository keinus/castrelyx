"""Fixed post-evaluation summaries; no changes to models or thresholds."""
from pathlib import Path
from collections import defaultdict
import json,numpy as np
ROOT=Path(__file__).resolve().parent
def main():
    c=json.loads((ROOT/'runs/comparison.json').read_text());out={'models':{},'paired_bootstrap':{}}
    gold={r['id']:r for split in ['known_test','unseen_test','external_known_test'] for r in [json.loads(s) for s in (ROOT/f'data/{split}.jsonl').read_text().splitlines()]}
    for name,model in c['models'].items():
        f=model['tests']['unseen_test']['attack_families'];out['models'][name]={'unseen_macro_family_recall':float(np.mean([r['detected']/r['n'] for r in f.values()])),
            'known':model['tests']['known_test'],'unseen':model['tests']['unseen_test'],'external_known':model['tests']['external_known_test']}
        buckets=defaultdict(lambda:{'n':0,'tp':0,'fp':0,'fn':0,'tn':0})
        for p in [json.loads(s) for s in (ROOT/f'runs/predictions_{name}.jsonl').read_text().splitlines()]:
            r=gold[p['id']];correct=('tp' if p['predicted'] else 'fn') if p['gold'] else ('fp' if p['predicted'] else 'tn')
            keys=[f"{p['split']}/capture/{r['provenance']['capture']}",f"{p['split']}/current_flows/{'one' if r['state']['recent']['flows']==1 else 'multiple'}"]
            if p['gold']:keys += [f"{p['split']}/publisher_behavior/{'+'.join(r['provenance']['publisher_attack_labels'])}"]
            for key in keys:buckets[key]['n']+=1;buckets[key][correct]+=1
        for b in buckets.values():
            b['recall']=b['tp']/(b['tp']+b['fn']) if b['tp']+b['fn'] else None
            b['fpr']=b['fp']/(b['fp']+b['tn']) if b['fp']+b['tn'] else None
        out['models'][name]['descriptive_subgroups']=dict(buckets)
    names=['laya_v3_sft','laya_v3_normal_relation_sft']
    predictions={name:{r['id']:r for r in [json.loads(s) for s in (ROOT/f'runs/predictions_{name}.jsonl').read_text().splitlines()]} for name in names}
    for split in ['known_test','unseen_test','external_known_test']:
        groups=defaultdict(lambda:np.zeros(4))
        for key,a in predictions[names[0]].items():
            if a['split']!=split:continue
            b=predictions[names[1]][key];delta=int(b['predicted'])-int(a['predicted'])
            groups[a['group']]+=np.array([delta*a['gold'],a['gold'],delta*(1-a['gold']),1-a['gold']])
        x=np.array(list(groups.values()));rng=np.random.default_rng(20261005);rec=[];fp=[]
        for i in range(2000):
            dr,n,df,m=x[rng.integers(len(x),size=len(x))].sum(0)
            if n:rec.append(dr/n)
            if m:fp.append(df/m)
        dr,n,df,m=x.sum(0);out['paired_bootstrap'][split]={'enhanced_minus_sft_recall':dr/n if n else None,'recall_difference_95':np.percentile(rec,[2.5,97.5]).tolist(),
            'enhanced_minus_sft_fpr':df/m if m else None,'fpr_difference_95':np.percentile(fp,[2.5,97.5]).tolist(),
            'unit':'30-minute host blocks; not independent campaigns; one seed only'}
    (ROOT/'runs/analysis.json').write_text(json.dumps(out,indent=2));print(json.dumps(out['paired_bootstrap'],indent=2))
if __name__=='__main__':main()
