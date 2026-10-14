"""Post-hoc descriptive diagnosis of saved predictions; no inference or refitting."""
from pathlib import Path
from collections import Counter,defaultdict
import hashlib,json,statistics
ROOT=Path(__file__).resolve().parent;V3=ROOT.parent

def read(relative):return [json.loads(x) for x in (V3/relative).read_text(encoding='utf8').splitlines()]
def has_4975(row):return any(e['proto']=='tcp' and e['port']==4975 for e in row['state']['events'])
def describe(rows):
    return {'cases':len(rows),'with_tcp_4975_in_sampled_events':sum(has_4975(r) for r in rows),
            'prior_60s_at_least_20_flows':sum(r['state']['prior_60s']['flows']>=20 for r in rows),
            'median_prior_60s_flows':statistics.median(r['state']['prior_60s']['flows'] for r in rows),
            'context_contains_publisher_attack':sum(r['provenance']['context_has_attack'] for r in rows)}

def main():
    out={'scope':'Existing v3 single-window predictions. Descriptive associations, not causal feature attribution.',
         'weights_or_thresholds_modified':False,
         'diagnostic_cutoff_note':'20 prior flows is a post-hoc descriptive grouping, not a recovered model decision rule.',
         'distributions':{},'models':{}}
    for split in ['train','calibration','unseen_test']:
        rows=read(f'data/{split}.jsonl')
        for label in [0,1]:out['distributions'][f'{split}_{label}']=describe([r for r in rows if r['y']==label])
    rows=read('data/unseen_test.jsonl')
    for name in ['laya_v3_normal_relation_sft','qwen3_06b_v3_lora']:
        pred={r['id']:r for r in read(f'runs/online_predictions_{name}.jsonl') if r['split']=='unseen_test'}
        groups=defaultdict(Counter)
        for r in rows:
            p=pred[r['id']]['predicted'];key=('tp' if p else 'fn') if r['y'] else ('fp' if p else 'tn')
            groups[r['provenance']['capture']][key]+=1
        fp=[r for r in rows if not r['y'] and pred[r['id']]['predicted']]
        fp42=[r for r in fp if r['provenance']['capture']=='CTU-IoT-Malware-Capture-42-1']
        model={'by_capture':dict(groups),'false_positives':describe(fp),'false_positives_capture42':describe(fp42),
               'capture42_fraction_of_false_positives':len(fp42)/len(fp),
               'fp42_match_readme_infected_device':sum(r['provenance']['host_hash']==hashlib.sha256('192.168.1.197'.encode()).hexdigest()[:12] for r in fp42)}
        policy=json.loads((V3/f'models/{name}_decision_policy.json').read_text(encoding='utf8'));model['policy']=policy
        model['posthoc_threshold_comparison']=[]
        for threshold in [policy['threshold'],.5]:
            tp=sum(r['y'] and pred[r['id']]['probability']>=threshold for r in rows)
            fpn=sum(not r['y'] and pred[r['id']]['probability']>=threshold for r in rows)
            model['posthoc_threshold_comparison'].append({'threshold':threshold,'tp':tp,'fp':fpn,'recall':tp/274,'fpr':fpn/1110})
        examples=sorted(fp42,key=lambda r:pred[r['id']]['probability'],reverse=True)[:3]
        model['examples']=[{'id':r['id'],'score':pred[r['id']]['probability'],'state':r['state'],'context_has_attack':r['provenance']['context_has_attack']} for r in examples]
        out['models'][name]=model
    out['evidence_files']=['prepare_data.py','train_laya.py','data/manifest.json','data/raw/zeek/CTU-IoT-Malware-Capture-42-1/README.md',
                           'runs/online_predictions_laya_v3_normal_relation_sft.jsonl','runs/online_predictions_qwen3_06b_v3_lora.jsonl']
    out['limits']=['Public Benign labels are used as the reference; none were relabeled.',
                   'An infected host can also emit benign traffic. Infection alone does not prove a particular alert is correct.',
                   'Input sampling, normal auxiliary tasks, and host/flow label scope are hypotheses requiring controlled evaluation.']
    (ROOT/'false_positive_diagnosis.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps({'models':{n:{'fp':m['false_positives']['cases'],'fp42':m['false_positives_capture42']['cases']} for n,m in out['models'].items()}}))

if __name__=='__main__':main()
