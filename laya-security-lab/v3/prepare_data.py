"""Causal, per-host Zeek states. No model/test scores participate in selection."""
from pathlib import Path
from collections import Counter,deque,defaultdict
import json,hashlib,math,random,ipaddress
import numpy as np
import pandas as pd
ROOT=Path(__file__).resolve().parent
P=json.loads((ROOT/'protocol.json').read_text());SEED=P['seed']
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def number(s):
    try:x=float(s);return max(0,x) if math.isfinite(x) else 0
    except (ValueError,TypeError):return 0
def local(ip):
    try:return ipaddress.ip_address(ip).is_private
    except ValueError:return False
def scalar(x):return round(float(x),3) if math.isfinite(float(x)) else 0
def parse(path):
    rows=[];quality=Counter();seen=set()
    with path.open(encoding='utf8') as src:
        for line in src:
            if line.startswith('#'):continue
            s=line.split();quality['input_rows']+=1
            if len(s)!=23:quality['wrong_field_count']+=1;continue
            label=s[21].lower()
            if label not in ('benign','malicious'):quality['unknown_label']+=1;continue
            if s[1] in seen:quality['duplicate_uid']+=1;continue
            seen.add(s[1]);duration=number(s[8]);ts=number(s[0])
            rows.append({'uid':s[1],'src':s[2],'dst':s[4],'port':int(number(s[5])),'proto':s[6],
                'service':s[7] if s[7]!='-' else 'unknown','status':s[11],'duration':duration,
                'out_bytes':number(s[9]),'in_bytes':number(s[10]),'out_packets':number(s[16]),'in_packets':number(s[18]),
                'available':ts+duration,'start':ts,'y':int(label=='malicious'),'attack':s[22],'peer_local':local(s[4])})
    return rows,dict(quality)
def stats(rows):
    n=len(rows)
    if not n:return {'flows':0,'peers':0,'ports':0,'out_bytes':0,'in_bytes':0,'failed_fraction':0,'repeat_peer_fraction':0}
    peers=Counter(r['dst'] for r in rows)
    return {'flows':n,'peers':len(peers),'ports':len({r['port'] for r in rows}),
            'out_bytes':scalar(sum(r['out_bytes'] for r in rows)),'in_bytes':scalar(sum(r['in_bytes'] for r in rows)),
            'failed_fraction':scalar(sum(r['status'] in ('S0','REJ','RSTOS0') for r in rows)/n),
            'repeat_peer_fraction':scalar(max(peers.values())/n)}
def state(current,past,end):
    # At most three current and three earlier flow records. Full window aggregates are retained.
    def spread(x,n):
        return [x[i] for i in np.unique(np.linspace(0,len(x)-1,min(n,len(x))).astype(int))] if x else []
    chosen=spread(past,3)+spread(current,3);peers={};events=[]
    for r in chosen:
        peer=peers.setdefault(r['dst'],f'peer{len(peers)+1}')
        events.append({'age_s':scalar(end-r['available']),'peer':peer,'local':r['peer_local'],'port':r['port'],
            'proto':r['proto'],'service':r['service'],'status':r['status'],'duration_s':scalar(r['duration']),
            'sent':scalar(r['out_bytes']),'received':scalar(r['in_bytes'])})
    return {'window_seconds':5,'recent':stats(current),'prior_60s':stats(past),'events':events}
def main():
    source=json.loads((ROOT/'data/source_manifest.json').read_text());cases=[];quality=[]
    for cap in source['captures']:
        name=cap['capture'];rows,q=parse(ROOT/f'data/raw/zeek/{name}/bro/conn.log.labeled')
        if not rows:raise ValueError(f'No valid rows: {name}, {q}')
        rows.sort(key=lambda r:(r['available'],r['uid']));origin=rows[0]['available']
        blocks=sorted({int((r['available']-origin)//1800) for r in rows});rank={b:i/max(1,len(blocks)) for i,b in enumerate(blocks)}
        groups=defaultdict(list)
        for r in rows:groups[(r['src'],int((r['available']-origin)//1800))].append(r)
        count=Counter()
        for (host,block),seq in groups.items():
            if name in P['unseen_capture_ids']:split='unseen_test'
            elif name in P['external_known_capture_ids']:split='external_known_test'
            elif name in P['proxy_validation_capture_ids']:split='proxy_validation'
            else:
                f=rank[block];split='train' if f<.60 else 'validation' if f<.75 else 'calibration' if f<.85 else 'known_test'
            by_bin=defaultdict(list)
            for r in seq:by_bin[int((r['available']-origin)//5)].append(r)
            history=deque();group=digest(f'{name}|{host}|{block}')[:24]
            for bin_id,current in sorted(by_bin.items()):
                end=origin+(bin_id+1)*5;start=end-5
                while history and history[0]['available']<start-60:history.popleft()
                past=list(history)
                if start>=origin+block*1800+60:
                    y=int(any(r['y'] for r in current));families=sorted({r['attack'] for r in current if r['y']})
                    case={'id':digest(f'{group}|{bin_id}')[:24],'group':group,'split':split,'source':'iot23_zeek',
                          'family':cap['family'] if y else 'Benign','y':y,'state':state(current,past,end),
                          'provenance':{'capture':name,'host_hash':digest(host)[:12],'window_start':start,'window_end':end,
                              'context_start':start-60,'current_uids':[r['uid'] for r in current],'context_uids':[r['uid'] for r in past],
                              'max_available':max(r['available'] for r in current+past),'publisher_attack_labels':families,
                              'context_has_attack':bool(any(r['y'] for r in past)),
                              'label_kind':'publisher malicious flow in current five-second window','availability':'flow end lower bound; sensor delivery unmeasured'}}
                    cases.append(case);count[(split,y)]+=1
                history.extend(current)
        quality.append({'capture':name,**q,'parsed_rows':len(rows),'blocks':len(blocks),'windows':{f'{s}:{y}':n for (s,y),n in count.items()}})
        print(json.dumps(quality[-1]),flush=True)
    # Stable random selection, cap each capture and class. Selection never depends on model scores.
    rng=random.Random(SEED);rng.shuffle(cases);counter=Counter();kept=[]
    for r in cases:
        key=(r['split'],r['provenance']['capture'],r['y']);cap=P['caps'][r['split']+'_per_capture_label']
        if counter[key]<cap:kept.append(r);counter[key]+=1
    manifest={'protocol_sha256':sha(ROOT/'protocol.json'),'source_manifest_sha256':sha(ROOT/'data/source_manifest.json'),'quality':quality,'splits':{}}
    for split in sorted({r['split'] for r in kept}):
        rows=sorted((r for r in kept if r['split']==split),key=lambda r:r['id']);rng.shuffle(rows)
        path=ROOT/f'data/{split}.jsonl';path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        manifest['splits'][split]={'cases':len(rows),'groups':len({r['group'] for r in rows}),'labels':dict(Counter(r['y'] for r in rows)),
            'families':dict(Counter(r['family'] for r in rows)),'sha256':sha(path)}
    (ROOT/'data/manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest['splits'],indent=2),flush=True)
if __name__=='__main__':main()
