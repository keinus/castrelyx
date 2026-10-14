"""Build auditable domain-tuning data; distinguish publisher labels and policy labels."""
from pathlib import Path
from collections import Counter,defaultdict
from dataclasses import asdict
from datetime import timezone
import hashlib,json,random,math
from contract import ACTION_CRITERIA,QUESTIONS,VERSION,gold
from correlation import Event,Correlator,replay,host_event,zeek_event,read_rows,timestamp

ROOT=Path(__file__).resolve().parent
SPLITS=['train','validation','calibration','policy_validation','test']
ASSETS={'10.0.1.4':'scranton','10.0.1.6':'nashua','10.0.0.4':'newyork','10.0.1.5':'utica','10.0.1.10':'warehouse'}

def split_for(group):
    x=int(hashlib.sha256(group.encode()).hexdigest()[:8],16)%10
    return 'train' if x<6 else SPLITS[x-5]

def row(group,case,state,action,threat,urgency,source,notes):
    return {'schema_version':VERSION,'group_id':group,'case_id':case,'split':split_for(group),
        'state':state,'questions':QUESTIONS,'gold':gold(action,threat,urgency),
        'labels':{'response':action,'urgency':urgency,'threat':bool(threat)},
        'provenance':{'source':source,**notes}}

def synthetic_cases(n=1800):
    """Independent toy episodes, never represented as real attack observations."""
    result=[];fixtures=[]
    families=['normal','approved_admin','ambiguous','scan','auth_attack','critical_flood','redirect','compromise']
    for i in range(n):
        rng=random.Random(61004+i);family=families[i%len(families)];g=f'synthetic-episode-{i:05d}'
        t=1800000000+i*1000;ip=f'10.{1+i//600}.{1+(i//250)%200}.{1+i%250}';dst='203.0.113.30';entity='endpoint'
        context={'identity_verified':True,'approved_activity':family=='approved_admin','protected_asset':family in ('critical_flood','redirect'),
            'alternate_path_healthy':family=='redirect','service_degraded':family in ('critical_flood','redirect')}
        events=[]
        def add(source,kind,at,**kw):
            events.append(Event(g+f'-{len(events):04d}',t+at,t+at+0.1,source,entity,kind,origin='synthetic',**kw))
        num=rng.randint(2,8) if family in ('normal','ambiguous','compromise') else rng.randint(28,70)
        process=rng.choice(['powershell.exe','pwsh.exe','python.exe'])
        command={'normal':'browser.exe --new-window','approved_admin':'powershell.exe Get-Service',
            'ambiguous':'powershell.exe -NoProfile','compromise':'powershell.exe -enc ZQBjAGgAbwA=',
            'redirect':'python.exe monitor.py','critical_flood':'webserver.exe --serve'}.get(family,'python.exe client.py')
        add('host','process',1,image=process if family!='normal' else 'browser.exe',command=command,process_guid='proc-A')
        for j in range(num):
            peer=f'10.50.{j//250}.{1+j%250}' if family in ('scan','auth_attack','approved_admin') else dst
            port=rng.choice([22,445,3389,5985]) if family in ('scan','auth_attack','approved_admin') else 443
            at=2+j*.25; sport=40000+j
            size=rng.randint(100000,3000000) if family in ('critical_flood','redirect') else rng.randint(50,6000)
            add('zeek','conn',at+1,src=ip,dst=peer,sport=sport,dport=port,proto='tcp',start=t+at,end=t+at+1,bytes_out=size,failed=family in ('scan','auth_attack'))
            if j<8:
                add('host','host_network',at+.2,src=ip,dst=peer,sport=sport,dport=port,proto='tcp',image=process,process_guid='proc-A')
            if family=='auth_attack':add('host','auth_failure',at+.4,src=ip)
        if family=='compromise':
            add('host','process_access',25,image=process,target_image='lsass.exe',process_guid='proc-A')
            add('host','service_install',28,image='service.exe')
        if family=='auth_attack':add('host','auth_success',30,src=ip)
        final=t+32
        corr=Correlator()
        for e in sorted(events,key=lambda x:x.available):corr.add(e)
        action={'normal':'OBSERVE','approved_admin':'OBSERVE','ambiguous':'COLLECT_MORE','scan':'FLOW_DROP','auth_attack':'FLOW_DROP','critical_flood':'QOS_PROFILE','redirect':'ROUTE_PROFILE','compromise':'HOST_ISOLATE'}[family]
        r=row(g,g,corr.snapshot(entity,final,context),action,family not in ('normal','approved_admin','ambiguous'),0 if action=='OBSERVE' else 1 if action=='COLLECT_MORE' else 2,'synthetic',{'label_kind':'constructed_policy_scenario','family':family,'not_independent_real_world_validation':True})
        result.append(r)
        if r['split']=='test' and len(fixtures)<16:
            fixtures.append({'case_id':g,'context':context,'labels':r['labels'],'events':[asdict(e) for e in sorted(events,key=lambda x:x.available)],'snapshot_at':final})
    return result,fixtures

def cic_cases():
    import pandas as pd
    result=[];quality=[]
    cols=['Source IP','Source Port','Destination IP','Destination Port','Protocol','Timestamp','Flow Duration','Total Length of Fwd Packets','Total Backward Packets','Label']
    for path in sorted((ROOT/'data/raw/cicids2017/traffic_labels').glob('*.parquet')):
        df=pd.read_parquet(path,columns=cols);df['Timestamp']=pd.to_datetime(df['Timestamp'],utc=True,errors='coerce')
        initial=len(df);df=df.dropna(subset=['Timestamp','Source IP'])
        # Actual datetime conversion below is unit-independent; group first to avoid 3M Python objects.
        df['block']=df['Timestamp'].dt.floor('30min').astype(str)
        groups=0
        for (ip,block),part in df.groupby(['Source IP','block'],sort=True):
            if len(part)<3:continue
            groups+=1;group=f'cic:{path.name}:{ip}:{block}'
            # Keep a bounded 5-minute slice per group; reset state so no event crosses splits.
            part=part.sort_values('Timestamp');base=part['Timestamp'].iloc[0]
            part=part[(part['Timestamp']-base).dt.total_seconds()<=300]
            corr=Correlator(limit=max(10000,len(part)));labels=[];events=[]
            for idx,r in enumerate(part.to_dict('records')):
                ts=r['Timestamp'].timestamp();duration=max(0,float(r['Flow Duration'])/1e6)
                if not math.isfinite(duration):continue
                e=Event(group+f':{idx}',ts,ts+duration,'cicflow','endpoint','flow',str(ip),str(r['Destination IP']),int(r['Source Port']),int(r['Destination Port']),str({6:'tcp',17:'udp'}.get(r['Protocol'],r['Protocol'])),ts,ts+duration,float(r['Total Length of Fwd Packets']),float(r['Total Backward Packets'])==0,availability_estimated=True,origin=path.name)
                events.append((e,str(r['Label'])))
            for e,label in sorted(events,key=lambda x:x[0].available):corr.add(e);labels.append((e.ts,label))
            if not events:continue
            now=max(e.available for e,l in events)
            present=[l for t,l in labels if now-300<=t<=now]
            if not present:continue
            attacks=[l for l in present if l.upper()!='BENIGN'];counts=Counter(attacks)
            attack=counts.most_common(1)[0][0] if attacks else 'BENIGN'
            threat=bool(attacks)
            action='QOS_PROFILE' if 'dos' in attack.lower() else 'FLOW_DROP' if threat else 'OBSERVE'
            context={'identity_verified':False,'approved_activity':False}
            state=corr.snapshot('endpoint',now,context)
            result.append(row(group,group,state,action,threat,2 if threat else 0,'cicids2017',{'publisher_attack_labels':dict(Counter(present)),'label_kind':'publisher_threat_and_designer_response','response_effect_measured':False,'flow_format':'CICFlowMeter, not native Zeek','selection':'first 5 minutes per source and 30-minute group; no row sampling','path':path.name}))
        quality.append({'file':path.name,'input_rows':initial,'valid_rows':len(df),'groups':groups})
        print(path.name,'groups',groups,flush=True)
    return result,quality

def otrf_cases():
    base=ROOT/'data/raw/otrf/datasets/compound/apt29/day1/zeek/individual_zeek_logs'
    host_path=next((ROOT/'data/raw/otrf_historical').glob('evals*.json'))
    events=[];host_count=0
    for n,r in read_rows(host_path):
        e=host_event(r,str(host_path.relative_to(ROOT)),ASSETS)
        if e:events.append(e);host_count+=1
    for path in base.glob('*_conn.log'):
        for n,r in read_rows(path):
            e=zeek_event(r,str(path.relative_to(ROOT)),ASSETS)
            if e:events.append(e)
    raw=ROOT/'data/processed/otrf_day1_events.jsonl'
    raw.parent.mkdir(parents=True,exist_ok=True)
    with raw.open('w',encoding='utf8') as f:
        for e in sorted(events,key=lambda x:(x.available,x.event_id)):f.write(json.dumps(asdict(e))+'\n')
    context={'identity_verified':True,'approved_activity':False}
    result=[];joins=0;both=0
    for i,(entity,at,state) in enumerate(replay(events,context,step=15)):
        if state['quality']['network_present'] and state['quality']['host_present']:both+=1
        matched=state['relations']['matched_process_flows'];joins+=matched>0
        # Weak policy teacher, not independent attack ground truth.
        h=state['host'];n=state['network']
        if h['lsass_access'] and matched:action='HOST_ISOLATE';threat=True
        elif (h['auth_failure']>=5 and n['flows']>=5):action='FLOW_DROP';threat=True
        elif h['service_install'] or h['lsass_access']:action='COLLECT_MORE';threat=False
        else:action='OBSERVE';threat=False
        item=row('otrf-apt29-historical-day1',f'otrf:{entity}:{at}',state,action,threat,2 if threat else 1 if action=='COLLECT_MORE' else 0,'otrf_apt29',{'label_kind':'weak_policy_teacher','independent_threat_ground_truth':False,'entity':entity,'snapshot_at':at})
        item['split']='train' # Entire capture stays together. Replay benchmark is explicitly in-sample.
        result.append(item)
    return result,{'selected_host_events':host_count,'zeek_events':len(events)-host_count,'snapshots':len(result),'both_sources_snapshots':both,'matched_5tuple_snapshots':joins,'all_in_train':True,'newer_may_logs_excluded':'No temporal overlap with April Zeek; no timestamp shifting.'}

def main():
    (ROOT/'data/processed').mkdir(parents=True,exist_ok=True)
    synthetic,fixtures=synthetic_cases();cic,cic_quality=cic_cases();otrf,otrf_quality=otrf_cases()
    all_rows=synthetic+cic+otrf
    # Bound training cost by a reproducible per-source/action cap, never by test results.
    buckets=defaultdict(list)
    for r in all_rows:buckets[(r['split'],r['provenance']['source'],r['labels']['response'])].append(r)
    selected=[]
    for key,rows in sorted(buckets.items()):
        random.Random(20261004).shuffle(rows)
        cap=350 if key[0]=='train' else 120
        selected.extend(rows[:cap])
    groups={s:{r['group_id'] for r in selected if r['split']==s} for s in SPLITS}
    for i,a in enumerate(SPLITS):
        for b in SPLITS[i+1:]:assert not groups[a]&groups[b],(a,b)
    summary={'schema':VERSION,'seed':20261004,'splits':{},'cic_files':cic_quality,'otrf':otrf_quality,
        'limitations':['Response labels are designer/weak labels, not measured treatment outcomes.','Synthetic joint-source holdout measures scenario learning, not real-world detection accuracy.','CIC groups share capture campaigns; this is not a leave-campaign-out evaluation.','All OTRF day1 correlated data is train-only; replay is not an independent test.']}
    for split in SPLITS:
        rows=[r for r in selected if r['split']==split]
        path=ROOT/f'data/processed/{split}.jsonl'
        with path.open('w',encoding='utf8') as f:
            for r in rows:f.write(json.dumps(r,ensure_ascii=False)+'\n')
        summary['splits'][split]={'cases':len(rows),'groups':len(groups[split]),'source':dict(Counter(r['provenance']['source'] for r in rows)),'action':dict(Counter(r['labels']['response'] for r in rows)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    (ROOT/'data/processed/replay_fixtures.json').write_text(json.dumps(fixtures,indent=2),encoding='utf8')
    (ROOT/'data/processed/dataset_manifest.json').write_text(json.dumps(summary,indent=2),encoding='utf8')
    print(json.dumps(summary,indent=2),flush=True)

if __name__=='__main__':main()
