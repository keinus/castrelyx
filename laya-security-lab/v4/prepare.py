"""Purged chronological splits and causal network-flow context, shared by all models."""
import argparse, collections, hashlib, json, math, random, sqlite3, itertools
from pathlib import Path
import numpy as np
import pyarrow.parquet as pq
from acquire import ROOT, digest

FIELDS=['age_s','src','dst','protocol','duration_s','sent_bytes','received_bytes','out_packets','in_packets']
SUMMARY_FIELDS=['flows','peers','sent_bytes','received_bytes','out_packets','in_packets','zero_reply_flows']
SPLITS=['train','validation','calibration','known_test','external_test','unseen_test']

class FlowWindow:
    """O(1) running summaries; dense windows do not rescan all past flows per decision."""
    def __init__(self):
        self.rows=collections.deque();self.peers=collections.Counter();self.totals=np.zeros(4,dtype=np.float64);self.zero_reply=0
    def __len__(self):return len(self.rows)
    def __iter__(self):return iter(self.rows)
    def __reversed__(self):return reversed(self.rows)
    def __getitem__(self,key):return self.rows[key]
    def _update(self,row,sign):
        for k in ('src','dst'):
            self.peers[row[k]]+=sign
            if self.peers[row[k]]==0:del self.peers[row[k]]
        self.totals+=sign*np.asarray([row[k] for k in ['sent','received','packets_out','packets_in']])
        self.zero_reply+=sign*int(row['packets_in']==0)
    def append(self,row):self.rows.append(row);self._update(row,1)
    def popleft(self):
        row=self.rows.popleft();self._update(row,-1);return row
    def summary(self):return [len(self.rows),len(self.peers),*[compact(x) for x in self.totals],self.zero_reply]

def stable(value): return hashlib.sha256(json.dumps(value,separators=(',',':'),sort_keys=True).encode()).hexdigest()
def compact(value): return float(f'{max(0.,float(value)):.5g}')
def split_blocks(blocks,counts=None):
    blocks=sorted(set(blocks))
    if len(blocks)<8: raise ValueError('At least eight independent time blocks required; refusing random-row fallback')
    cumulative=np.cumsum([counts[b] if counts else 1 for b in blocks]);total=cumulative[-1]
    a=min(len(blocks)-3,max(1,int(np.searchsorted(cumulative,total*.6))+1))
    b=min(len(blocks)-2,max(a+1,int(np.searchsorted(cumulative,total*.75))+1))
    c=min(len(blocks)-1,max(b+1,int(np.searchsorted(cumulative,total*.85))+1))
    return {block:'train' if i<a else 'validation' if i<b else 'calibration' if i<c else 'known_test' for i,block in enumerate(blocks)}

def state_for(target, history, context_events):
    context=history;chosen=list(reversed(list(itertools.islice(reversed(context),context_events)))) if context_events else []
    endpoint={}
    # Canonical window-local identifiers preserve relationships but cannot memorize addresses.
    def event(row):
        for key in ('src','dst'):
            if row[key] not in endpoint: endpoint[row[key]]=len(endpoint)
        return [compact(target['end']-row['end']),endpoint[row['src']],endpoint[row['dst']],row['proto'],
                compact(row['duration']),compact(row['sent']),compact(row['received']),compact(row['packets_out']),compact(row['packets_in'])]
    events=[event(row) for row in chosen];current=event(target)
    summary=context.summary() if isinstance(context,FlowWindow) else [len(context),len({r[k] for r in context for k in ('src','dst')}),
             *[compact(sum(r[k] for r in context)) for k in ['sent','received','packets_out','packets_in']],
             sum(r['packets_in']==0 for r in context)]
    return {'fields':FIELDS,'current':current,'history':events,'summary_fields':SUMMARY_FIELDS,'summary':summary}

class Reservoir:
    def __init__(self, cap, seed): self.cap=cap;self.rng=random.Random(seed);self.seen=0;self.rows=[]
    def add(self,row):
        self.seen+=1
        if len(self.rows)<self.cap:self.rows.append(row)
        else:
            index=self.rng.randrange(self.seen)
            if index<self.cap:self.rows[index]=row

def prepare(args):
    # DuckDB performs sorting/spilling out of core. Source paths are parameters, not SQL interpolation.
    import duckdb
    folder=ROOT/'data/experiments'/args.name
    if folder.exists(): raise FileExistsError('Experiment is immutable; choose another --name')
    folder.mkdir(parents=True)
    conn=duckdb.connect();conn.execute("SET memory_limit='2GB'");conn.execute('SET threads=4')
    (ROOT/'data/tmp').mkdir(exist_ok=True)
    conn.execute('SET temp_directory=?',[str(ROOT/'data/tmp')])
    caps={'train':args.train_cap,'validation':args.eval_cap,'calibration':args.cal_cap,'known_test':args.eval_cap,'external_test':args.eval_cap,'unseen_test':args.eval_cap}
    pools={(s,y):Reservoir(caps[s],args.seed+i*2+y) for i,s in enumerate(SPLITS) for y in (0,1)}
    uniform={s:Reservoir(2*caps[s],args.seed+100+i) for i,s in enumerate(SPLITS) if s!='train'}
    if args.eval_sampling=='uniform':
        for (s,y),pool in pools.items():
            if s!='train':pool.cap=0
    sources=[args.source]+([args.external] if args.external else [])
    meta={'schema':'public-network-v4','name':args.name,'seed':args.seed,'source':args.source,'external':args.external,
          'heldout_family':args.heldout_family,'context_seconds':args.context_seconds,'context_events':args.context_events,
          'block_seconds':args.block_seconds,'grouping':args.grouping,'sources':{},'counts':{},'dropped':{},
          'task':'classify the current completed flow using only prior completed network flows',
          'sampling':'class-stratified reservoir; report actual denominators, NOT population precision/AP' if args.eval_sampling=='stratified' else 'balanced training reservoir; uniform evaluation reservoir from each eligible split, no test class balancing',
          'latency_scope':'completed-flow arrival to decision; sensor/export and actuation not included',
          'within_source_split':'chronological whole blocks at cumulative flow-count fractions 60/75/85%; first context_seconds in every block are warmup only',
          'pilot':args.mode=='pilot','unknown_claim':'held-out supervised attack family only; no foundation-pretraining exclusion guarantee'}
    # Persistent raw-flow fingerprints prevent duplicate source records across any split.
    dedup=sqlite3.connect(folder/'fingerprints.sqlite');dedup.execute('CREATE TABLE fingerprints (value TEXT PRIMARY KEY)')
    for source in sources:
        path=ROOT/f'data/normalized/{args.mode}/{source}.parquet'
        source_meta=json.loads(path.with_suffix('.json').read_text(encoding='utf8'))
        if digest(path)!=source_meta['sha256']:raise ValueError('Normalized source SHA256 mismatch')
        if args.heldout_family and source==args.source and not source_meta['family_labels_available']:
            raise ValueError('This source has no verified attack-family labels; cross-year evaluation is not a zero-day test')
        conn.from_parquet(str(path)).create_view('flows',replace=True)
        families={row[0] for row in conn.execute('SELECT DISTINCT family FROM flows WHERE y=1').fetchall()}
        if args.heldout_family and source==args.source and args.heldout_family not in families: raise ValueError('Requested held-out family does not exist')
        captured=conn.execute('SELECT capture, list_sort(list(DISTINCT CAST(floor("end" / ?) AS BIGINT))) FROM flows GROUP BY capture',[args.block_seconds]).fetchall()
        block_counts={(capture,b):n for capture,b,n in conn.execute('SELECT capture, CAST(floor("end" / ?) AS BIGINT) b, count(*) FROM flows GROUP BY capture,b',[args.block_seconds]).fetchall()}
        unknown_blocks=set(conn.execute('SELECT DISTINCT capture, CAST(floor("end" / ?) AS BIGINT) FROM flows WHERE family=?',[args.block_seconds,args.heldout_family]).fetchall()) if args.heldout_family and source==args.source else set()
        assignments={}
        for capture,blocks in captured:
            known_blocks=[b for b in blocks if (capture,b) not in unknown_blocks]
            mapping=(split_blocks(known_blocks,{b:block_counts[(capture,b)] for b in known_blocks}) if known_blocks else {}) if source==args.source else {b:'external_test' for b in blocks}
            mapping.update({b:'unseen_test' for b in blocks if (capture,b) in unknown_blocks})
            assignments[capture]=mapping
        meta['sources'][source]={'sha256':source_meta['sha256'],'selection':source_meta.get('selection'),
            'families':sorted(families),'blocks':{k:{str(b):s for b,s in v.items()} for k,v in assignments.items()},
            'source_verification':source_meta.get('range_manifest',{}).get('verification','full source checksum verified')}
        dropped=collections.Counter();windows={};last_block=None;processed=0
        stream=conn.execute('SELECT * FROM flows ORDER BY capture, "end", row').fetch_record_batch(5000)
        for batch in stream:
            for row in batch.to_pylist():
                processed+=1
                block=int(row['end']//args.block_seconds); block_key=(row['capture'],block)
                if block_key!=last_block: windows={};last_block=block_key
                if row['end']-block*args.block_seconds<args.context_seconds:
                    split=None;dropped['purged_block_prefix']+=1
                else: split=assignments[row['capture']][block]
                if split=='unseen_test' and row['y'] and row['family']!=args.heldout_family:
                    split=None;dropped['other_attack_in_heldout_block']+=1
                fingerprint=stable([source,row['capture'],*[row[k] for k in ['start','end','src','dst','proto','sent','received','packets_out','packets_in']]])
                try: dedup.execute('INSERT INTO fingerprints VALUES (?)',(fingerprint,))
                except sqlite3.IntegrityError: dropped['duplicate_source_flow']+=1;continue
                key=row['src'] if args.grouping=='source' else tuple(sorted([row['src'],row['dst']])) if args.grouping=='pair' else 'network'
                if key not in windows:windows[key]=FlowWindow()
                history=windows[key]
                while history and history[0]['end']<row['end']-args.context_seconds:history.popleft()
                # Entire held-out blocks are excluded from fitting; context never crosses block boundaries.
                usable=history
                if split is not None:
                    state=state_for(row,usable,args.context_events)
                    case={'id':stable([source,row['capture'],row['row']])[:24],'group':stable([source,*block_key])[:24],
                          'source':source,'capture':row['capture'],'time':row['end'],'split':split,'y':row['y'],'family':row['family'],
                          'raw_fingerprint':fingerprint,'state':state,'state_sha256':stable(state),
                          'context':{'count':len(usable),'earliest':usable[0]['end'] if usable else None,'latest':usable[-1]['end'] if usable else None}}
                    pools[(split,row['y'])].add(case)
                    if args.eval_sampling=='uniform' and split!='train':uniform[split].add(case)
                history.append(row)
                if processed%50000==0:
                    dedup.commit();print(json.dumps({'source':source,'processed':processed,'retained':sum(len(p.rows) for p in pools.values())}),flush=True)
        meta['dropped'][source]=dict(dropped)
    dedup.commit();dedup.close();conn.close()
    output_rows={}
    for split in SPLITS:
        rows=uniform[split].rows if args.eval_sampling=='uniform' and split!='train' else pools[(split,0)].rows+pools[(split,1)].rows
        if split=='unseen_test' and args.heldout_family:
            # Same frozen benign controls as known test; shared IDs are intentional and documented.
            rows=rows+[{**r,'split':'unseen_test','shared_benign_control':True} for r in output_rows['known_test'] if not r['y']]
        rows.sort(key=lambda r:(r['capture'],r['time'],r['id']))
        output_rows[split]=rows
        if split in ['train','validation','calibration','known_test'] and len({r['y'] for r in rows})<2:
            raise ValueError(f'{split} does not contain both classes; increase source coverage, do not fabricate samples')
        target=folder/(split+'.jsonl');target.write_text(''.join(json.dumps(r,separators=(',',':'))+'\n' for r in rows),encoding='utf8')
        meta['counts'][split]={'rows':len(rows),'positive':sum(r['y'] for r in rows),'groups':len({r['group'] for r in rows}),
            'eligible_by_class':{str(y):pools[(split,y)].seen for y in (0,1)},'sha256':digest(target)}
    meta['status']='prepared';(folder/'manifest.json').write_text(json.dumps(meta,indent=2),encoding='utf8')
    return meta

def parser():
    ap=argparse.ArgumentParser();ap.add_argument('--name',required=True);ap.add_argument('--source',default='lspr23');ap.add_argument('--external',default='lspr24')
    ap.add_argument('--mode',choices=['pilot','full'],default='pilot');ap.add_argument('--heldout-family',default='')
    ap.add_argument('--grouping',choices=['source','pair','network'],default='source');ap.add_argument('--context-seconds',type=float,default=60.)
    ap.add_argument('--context-events',type=int,default=6);ap.add_argument('--block-seconds',type=int,default=300)
    ap.add_argument('--train-cap',type=int,default=512);ap.add_argument('--eval-cap',type=int,default=500);ap.add_argument('--cal-cap',type=int,default=1000)
    ap.add_argument('--eval-sampling',choices=['stratified','uniform'],default='stratified')
    ap.add_argument('--seed',type=int,default=20261005);return ap
if __name__=='__main__':
    args=parser().parse_args()
    if not 0<args.context_seconds<args.block_seconds or args.context_events<0 or min(args.train_cap,args.eval_cap,args.cal_cap)<1:raise ValueError('Invalid temporal bounds or sample caps')
    print(json.dumps(prepare(args),indent=2))
