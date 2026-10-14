"""Event-time correlation with availability cutoffs and explicit data provenance."""
from dataclasses import dataclass, asdict
from collections import deque, Counter
from datetime import datetime, timezone
from pathlib import Path
import hashlib, ipaddress, json, math

def timestamp(value):
    if isinstance(value,(int,float)):return float(value)
    if isinstance(value,datetime):d=value
    else:d=datetime.fromisoformat(str(value).strip().replace('Z','+00:00'))
    if d.tzinfo is None:d=d.replace(tzinfo=timezone.utc)
    return d.timestamp()

def number(x,default=0.0):
    try:
        n=float(x)
        return n if math.isfinite(n) else default
    except (ValueError,TypeError):return default

def external(ip):
    try:return not ipaddress.ip_address(ip).is_private
    except ValueError:return False

@dataclass
class Event:
    event_id:str
    ts:float
    available:float
    source:str
    entity:str
    kind:str
    src:str=''
    dst:str=''
    sport:int=0
    dport:int=0
    proto:str=''
    start:float=0
    end:float=0
    bytes_out:float=0
    failed:bool=False
    image:str=''
    command:str=''
    process_guid:str=''
    parent_guid:str=''
    target_image:str=''
    availability_estimated:bool=False
    origin:str=''

def read_rows(path):
    """Zeek JSONL or TSV with #fields, and host JSONL. Inputs remain inert data."""
    fields=None
    with Path(path).open(encoding='utf-8-sig',errors='replace') as f:
        for index,line in enumerate(f,1):
            if line.startswith('#fields'):fields=line.rstrip('\n').split('\t')[1:];continue
            if not line.strip() or line.startswith('#'):continue
            if line.lstrip().startswith('{'):yield index,json.loads(line)
            elif fields:yield index,dict(zip(fields,line.rstrip('\n').split('\t')))
            else:raise ValueError(f'{path}:{index}: unsupported format')

def zeek_event(row,origin='',assets=None):
    assets=assets or {}
    r={k.replace('id.','id_').replace('.','_'):v for k,v in row.items()}
    src=str(r.get('id_orig_h','')); dst=str(r.get('id_resp_h',''))
    if not src or 'ts' not in r:return None
    ts=timestamp(r['ts']); duration=max(0,number(r.get('duration')))
    stream=row.get('@stream',row.get('_path', 'conn' if 'conn_state' in row else 'network'))
    # Offline conn.log does not contain sensor emission time; end time is only a lower bound.
    known=row.get('available_at',row.get('ingested_at'))
    available=max(ts, timestamp(known)) if known else ts+duration
    identity=f"{stream}|{row.get('uid','')}|{src}|{dst}|{ts}"
    return Event(hashlib.sha256(identity.encode()).hexdigest()[:24],ts,available,'zeek',assets.get(src,src),stream,
        src,dst,int(number(r.get('id_orig_p'))),int(number(r.get('id_resp_p'))),str(r.get('proto','tcp')),ts,ts+duration,
        number(r.get('orig_bytes')),str(r.get('conn_state','')) in ('S0','REJ','RSTOS0','RSTRH'),availability_estimated=known is None,origin=origin)

def host_event(row,origin='',assets=None):
    assets=assets or {}
    # Supports native normalized Linux audit/auth JSON and common Windows/Sysmon JSON.
    if row.get('source')=='host' and 'kind' in row and 'ts' in row:
        accepted={k:v for k,v in row.items() if k in Event.__dataclass_fields__}
        accepted.setdefault('available',timestamp(row['ts']));accepted.setdefault('event_id',hashlib.sha256(json.dumps(row,sort_keys=True).encode()).hexdigest()[:24])
        accepted['ts']=timestamp(accepted['ts']);accepted['available']=timestamp(accepted['available']);accepted['origin']=origin
        return Event(**accepted)
    eid=int(number(row.get('EventID',row.get('event_id'))))
    channel=str(row.get('Channel',row.get('SourceName',''))).lower()
    sysmon='sysmon' in channel
    kind=({1:'process',3:'host_network',10:'process_access',11:'file_create',13:'registry_set'}.get(eid) if sysmon else {4624:'auth_success',4625:'auth_failure',7045:'service_install',4698:'scheduled_task'}.get(eid))
    if kind is None:return None
    time_value=row.get('UtcTime',row.get('@timestamp'))
    if time_value is None:return None # Do not guess local EventTime time zones.
    ts=timestamp(time_value); available=max(ts,timestamp(row.get('@timestamp',time_value)))
    hostname=str(row.get('Hostname',row.get('Computer',''))).lower().split('.')[0]
    entity=assets.get(hostname,hostname)
    src=str(row.get('SourceIp',row.get('IpAddress','')));dst=str(row.get('DestinationIp',''))
    if not entity:entity=assets.get(src,src)
    if not entity:return None
    identity=f"{hostname}|{channel}|{row.get('RecordNumber',row.get('EventRecordID',''))}|{eid}|{ts}"
    return Event(hashlib.sha256(identity.encode()).hexdigest()[:24],ts,available,'host',entity,kind,src,dst,
        int(number(row.get('SourcePort',row.get('IpPort')))),int(number(row.get('DestinationPort'))),str(row.get('Protocol','tcp')),ts,ts,
        image=str(row.get('Image',row.get('SourceImage','')))[:180],command=str(row.get('CommandLine',''))[:180],
        process_guid=str(row.get('ProcessGuid',row.get('SourceProcessGUID',''))),parent_guid=str(row.get('ParentProcessGuid','')),
        target_image=str(row.get('TargetImage',''))[:180],origin=origin)

class Correlator:
    def __init__(self,window=300,limit=10000):
        self.window=window;self.limit=limit;self.events=deque();self.seen={};self.last_available=-float('inf');self.duplicates=0;self.dropped=0

    def add(self,event):
        if event.available<self.last_available:raise ValueError('Replay must be ordered by availability, not event time')
        self.last_available=event.available
        cutoff=event.available-self.window
        while self.events and self.events[0].available<cutoff:
            old=self.events.popleft();self.seen.pop(old.event_id,None)
        if event.event_id in self.seen:self.duplicates+=1;return False
        self.seen[event.event_id]=event.available;self.events.append(event)
        while len(self.events)>self.limit:
            old=self.events.popleft();self.seen.pop(old.event_id,None);self.dropped+=1
        return True

    def snapshot(self,entity,now,context=None):
        context=context or {}
        events=[e for e in self.events if e.entity==entity and e.available<=now and now-self.window<=e.ts<=now]
        net=[e for e in events if e.source in ('zeek','cicflow') and e.kind in ('conn','flow')]
        host=[e for e in events if e.source=='host'];counts=Counter(e.kind for e in host)
        # Match process network records against an observed 5-tuple and overlapping flow time.
        joined=[]
        for h in host:
            if h.kind!='host_network':continue
            match=any((n.src,n.dst,n.sport,n.dport,n.proto)==(h.src,h.dst,h.sport,h.dport,h.proto) and n.start-2<=h.ts<=n.end+2 for n in net)
            if match:joined.append(h)
        ordered=sorted(events,key=lambda e:(e.ts,e.event_id))
        failures=[e.ts for e in host if e.kind=='auth_failure']
        successes=[e.ts for e in host if e.kind=='auth_success']
        seq=[]
        for e in ordered:
            if e.kind in ('process','auth_failure','auth_success','process_access','service_install','scheduled_task'):
                item={'ago_s':round(now-e.ts,1),'event':e.kind}
                if e.image:item['image']=e.image.replace('\\','/').split('/')[-1]
                if e.command and e.kind=='process':item['command']=e.command[-110:]
                if e.target_image:item['target']=e.target_image.replace('\\','/').split('/')[-1]
                seq.append(item)
        recent=[n for n in net if n.ts>=now-30]
        state={
            'window_s':self.window,
            'network':{'flows':len(net),'peers':len({n.dst for n in net}),'ports':len({n.dport for n in net}),
                'failed':sum(n.failed for n in net),'bytes_out':round(sum(n.bytes_out for n in net)),
                'external_flows':sum(external(n.dst) for n in net),'flows_30s':len(recent)},
            'host':{'events':len(host),'auth_failure':counts['auth_failure'],'auth_success':counts['auth_success'],
                'process':counts['process'],'process_access':counts['process_access'],
                'lsass_access':sum('lsass.exe' in e.target_image.lower() for e in host),
                'service_install':counts['service_install'],'host_network':counts['host_network']},
            'relations':{'matched_process_flows':len(joined),'auth_success_after_failures':any(f<s for f in failures for s in successes)},
            'sequence':seq[-4:],
            'context':{k:context.get(k,False) for k in ('approved_activity','protected_asset','identity_verified','alternate_path_healthy','service_degraded')},
            'quality':{'network_present':bool(net),'host_present':bool(host),'availability_estimated':any(e.availability_estimated for e in events),'buffer_dropped':self.dropped},
        }
        return state

def replay(events,context=None,step=10):
    correlator=Correlator();last={}
    for e in sorted(events,key=lambda x:(x.available,x.event_id)):
        if not correlator.add(e):continue
        if e.available-last.get(e.entity,-float('inf'))<step:continue
        last[e.entity]=e.available
        yield e.entity,e.available,correlator.snapshot(e.entity,e.available,context)
