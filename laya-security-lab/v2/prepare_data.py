"""Complete-flow temporal windows; publisher threat labels never enter model state."""
from pathlib import Path
from collections import Counter,defaultdict
import hashlib,json,random,math
import numpy as np
import pandas as pd

ROOT=Path(__file__).resolve().parent;LAB=ROOT.parent;SEED=20261005;WINDOW=5;GROUP=300
HELD=('Friday-WorkingHours-Morning','Thursday-WorkingHours')

def digest(value):return hashlib.sha256(value.encode()).hexdigest()
def split(group):
    v=int(digest(group)[:8],16)%20
    return 'train' if v<12 else 'validation' if v<15 else 'calibration' if v<17 else 'known_test'
def clean(v):
    x=float(v)
    if not math.isfinite(x):return 0
    return int(x) if x==int(x) else float(f'{x:.4g}')

def network():
    rows=[];quality=[]
    cols=['Source IP','Source Port','Destination IP','Destination Port','Protocol','Timestamp','Flow Duration','Total Fwd Packets','Total Backward Packets','Total Length of Fwd Packets','Total Length of Bwd Packets','Flow IAT Mean','Packet Length Mean','SYN Flag Count','RST Flag Count','Label']
    for path in sorted((LAB/'data/raw/cicids2017/traffic_labels').glob('*.parquet')):
        df=pd.read_parquet(path,columns=cols);original=len(df)
        df['Timestamp']=pd.to_datetime(df['Timestamp'],utc=True,errors='coerce')
        df=df.dropna(subset=['Timestamp','Source IP','Destination IP','Label']).copy()
        before=len(df);df=df.drop_duplicates(subset=cols[:-1]);dedup=before-len(df)
        for col in cols:
            if col not in ('Timestamp','Source IP','Destination IP','Label'):
                df[col]=pd.to_numeric(df[col],errors='coerce').replace([np.inf,-np.inf],np.nan).fillna(0).clip(lower=0)
        ts=df['Timestamp'].astype('int64').to_numpy()/ (1e9 if str(df['Timestamp'].dtype).startswith('datetime64[ns') else 1e6)
        # pandas versions preserve different datetime units; explicit timestamp conversion is authoritative.
        ts=np.array([x.timestamp() for x in df['Timestamp']],dtype=np.float64)
        duration=df['Flow Duration'].to_numpy()/1e6
        available=ts+duration;df['block']=(available//GROUP).astype('int64');df['bin']=(available//WINDOW).astype('int64')
        df['duration_s']=duration;df['failed']=(df['Total Backward Packets']==0).astype(int)
        df['web']=df['Destination Port'].isin([80,443,8080]).astype(int)
        df['admin']=df['Destination Port'].isin([21,22,23,445,3389,5900,5985]).astype(int)
        df['dns']=(df['Destination Port']==53).astype(int)
        df['is_tcp']=(df['Protocol']==6).astype(int);df['is_udp']=(df['Protocol']==17).astype(int)
        df['attack']=(df['Label']!='BENIGN').astype(int)
        key=['Source IP','block','bin'];g=df.groupby(key,sort=True)
        agg=g.agg(flows=('Label','size'),peers=('Destination IP','nunique'),ports=('Destination Port','nunique'),
            fwd_packets=('Total Fwd Packets','sum'),back_packets=('Total Backward Packets','sum'),
            fwd_bytes=('Total Length of Fwd Packets','sum'),back_bytes=('Total Length of Bwd Packets','sum'),
            duration_median_s=('duration_s','median'),duration_max_s=('duration_s','max'),duration_sum_s=('duration_s','sum'),
            failed_fraction=('failed','mean'),web_fraction=('web','mean'),admin_fraction=('admin','mean'),dns_fraction=('dns','mean'),
            tcp_fraction=('is_tcp','mean'),udp_fraction=('is_udp','mean'),packet_length_mean=('Packet Length Mean','mean'),
            flow_iat_mean_us=('Flow IAT Mean','mean'),syn_flags=('SYN Flag Count','sum'),rst_flags=('RST Flag Count','sum'),attacks=('attack','sum'))
        fam=df[df['attack']==1].groupby(key)['Label'].agg(lambda s:sorted(set(s))).to_dict()
        previous=defaultdict(list);file_rows=[];held=path.name.startswith(HELD)
        for (ip,block,bin_id),a in agg.iterrows():
            group=f'{path.name}:{ip}:{block}';history=previous[group];past=[(t,n) for t,n in history if bin_id-10<=t<bin_id]
            features={k:clean(v) for k,v in a.items() if k!='attacks'}
            features['fwd_byte_per_packet']=clean(a['fwd_bytes']/max(1,a['fwd_packets']))
            features['back_byte_per_packet']=clean(a['back_bytes']/max(1,a['back_packets']))
            features['back_to_fwd_packet_ratio']=clean(a['back_packets']/max(1,a['fwd_packets']))
            features['prior_flows_per_5s']=clean(sum(n for t,n in past)/max(1,min(10,bin_id-int(block)*(GROUP//WINDOW))))
            features['prior_bins_observed']=len(past)
            features['flow_burst_ratio']=clean(a['flows']/max(1,features['prior_flows_per_5s']))
            history.append((int(bin_id),int(a['flows'])))
            labels=fam.get((ip,block,bin_id),[])
            family=' + '.join(labels) if labels else 'BENIGN'
            # Too few examples for a meaningful train/holdout split; keep out of this protocol.
            if 'Heartbleed' in family:continue
            case={'id':digest(group+f':{bin_id}')[:24],'group':digest(group)[:24],'source':'cicids2017','family':family,
                'split':'unseen_test' if held else split(group),'y':int(a['attacks']>0),
                'state':{'window_seconds':WINDOW,'network':features,'host_evidence':'not_available','timing':'completed_flows_available_by_window_end'},
                'provenance':{'file':path.name,'source_host':ip,'availability_window_end':int((bin_id+1)*WINDOW),'publisher_threat_labels':labels,
                    'availability':'flow end lower bound, sensor delivery unmeasured','label_kind':'publisher_attack_in_window'}}
            file_rows.append(case)
        quality.append({'file':path.name,'original_rows':original,'valid_unique_rows':len(df),'duplicates_removed':dedup,'windows':len(file_rows),'unseen_file':held})
        rows+=file_rows;print(json.dumps(quality[-1]),flush=True)
    return rows,quality

def joint_cases():
    """Explicitly constructed evidence-binding task, not publisher attack ground truth."""
    rows=[]
    for i in range(680):
        rng=random.Random(SEED+i);unseen=i>=600;family='staging_transfer' if unseen else ['credential_network','auth_chain','remote_service'][i%3]
        group=f'joint-pair-{i}';part='joint_unseen_test' if unseen else split(group)
        if part=='known_test':part='joint_known_test'
        for y in (0,1):
            rng=random.Random(SEED+i)
            p=f'P{rng.randrange(100000,999999)}';q=f'P{rng.randrange(100000,999999)}'
            while q==p:q=f'P{rng.randrange(100000,999999)}'
            actor=p if y else q
            if family=='credential_network':
                events=[{'sec':0,'host':'endpoint','process':p,'event':'process_access','target':'lsass.exe'},
                    {'sec':4,'host':'endpoint','process':q,'event':'process_start','image':'browser.exe'},
                    {'sec':7,'host':'endpoint','process':actor,'event':'network_connect','peer':'external','dst_port':443,'sent_bytes':rng.randint(20000,50000)}]
            elif family=='auth_chain':
                events=[{'sec':0,'host':'server','source':p,'event':'authentication_failures','count':18},
                    {'sec':5,'host':'server','source':actor,'event':'authentication_success'},
                    {'sec':7,'host':'server','source':actor,'event':'new_service_installed'}]
            elif family=='remote_service':
                events=[{'sec':0,'host':'workstation','session':p,'event':'remote_admin_connection','peer':'server'},
                    {'sec':5,'host':'server','session':actor,'event':'new_service_installed'},
                    {'sec':8,'host':'server','session':actor,'event':'security_logging_disabled'}]
            else:
                events=[{'sec':0,'host':'endpoint','process':p,'event':'archive_created','contents':'many_sensitive_documents'},
                    {'sec':3,'host':'endpoint','process':q,'event':'process_start','image':'browser.exe'},
                    {'sec':8,'host':'endpoint','process':actor,'event':'network_upload','peer':'external','sent_bytes':rng.randint(1000000,2000000)}]
            # Pair shares all non-binding content; identifiers/bytes are redrawn per label but independently of label.
            state={'window_seconds':30,'events':events,'investigation':'Assess whether the events form a linked suspicious chain; disconnected events alone are insufficient.'}
            rows.append({'id':f'{group}-{y}','group':group,'split':part,'source':'constructed_joint','family':family,'y':y,'state':state,
                'provenance':{'label_kind':'designer_constructed_relation','not_real_world_ground_truth':True}})
    return rows

def main():
    raw,quality=network();rng=random.Random(SEED);rng.shuffle(raw)
    by_group=Counter();kept=[]
    for row in raw:
        key=(row['split'],row['group'],row['family'])
        if by_group[key]<(30 if row['y'] else 4):kept.append(row);by_group[key]+=1
    caps={'train':(1800,400),'validation':(500,90),'calibration':(400,65),'known_test':(500,90),'unseen_test':(600,240)}
    buckets=Counter();selected=[]
    for row in kept:
        cap=caps[row['split']][0 if not row['y'] else 1];key=(row['split'],row['family'])
        if buckets[key]<cap:selected.append(row);buckets[key]+=1
    selected+=joint_cases();out=ROOT/'data';out.mkdir(exist_ok=True)
    summary={'seed':SEED,'network_quality':quality,'splits':{},'limitations':['Known split shares capture campaigns across disjoint 5-minute host groups.','Unseen source files and attack families are excluded entirely from domain training/calibration/threshold selection.','Public LLM pretraining contamination cannot be excluded.','Flow-end availability is an estimate; no physical router latency measured.','Joint relations are constructed challenge data, not verified attack outcomes.']}
    all_groups={}
    for name in sorted({x['split'] for x in selected}):
        rows=sorted([x for x in selected if x['split']==name],key=lambda x:x['id']);random.Random(str(SEED)+name).shuffle(rows)
        for r in rows:
            if r['group'] in all_groups:assert all_groups[r['group']]==name
            all_groups[r['group']]=name
            assert 'family' not in r['state'] and 'y' not in r['state']
        path=out/f'{name}.jsonl';path.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf8')
        summary['splits'][name]={'cases':len(rows),'groups':len({r['group'] for r in rows}),'labels':dict(Counter(r['y'] for r in rows)),'families':dict(Counter(r['family'] for r in rows)),'sources':dict(Counter(r['source'] for r in rows)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    (out/'manifest.json').write_text(json.dumps(summary,indent=2),encoding='utf8');print(json.dumps(summary,indent=2),flush=True)

if __name__=='__main__':main()
