"""Normalize public flows. Alert/role/label columns never become model features."""
import argparse, csv, io, json, zipfile, hashlib, time
from pathlib import Path
import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
from acquire import ROOT, digest
from range_source import public_file

LSPR_COLUMNS = ['SrcIP','DstIP','Protocol','mTimestampStart','mTimestampLast',
                'Tot Fwd Pkts','Tot Bwd Pkts','Total Length of Fwd Packet',
                'Total Length of Bwd Packet','SYN Flag Cnt','RST Flag Cnt','Label']
SCHEMA = pa.schema([('source',pa.string()),('capture',pa.string()),('row',pa.int64()),
    ('start',pa.float64()),('end',pa.float64()),('src',pa.string()),('dst',pa.string()),
    ('proto',pa.string()),('duration',pa.float64()),('sent',pa.float64()),('received',pa.float64()),
    ('packets_out',pa.float64()),('packets_in',pa.float64()),('syn',pa.float64()),('rst',pa.float64()),
    ('y',pa.int64()),('family',pa.string())])

def lspr_frame(frame, source, offset, quality=None):
    # mTimestampStart/Last are epoch microseconds in both official releases.
    missing=set(LSPR_COLUMNS)-set(frame)
    if missing: raise ValueError('Missing canonical source columns: '+str(missing))
    start=pd.to_numeric(frame['mTimestampStart'],errors='raise')/1e6
    end=pd.to_numeric(frame['mTimestampLast'],errors='raise')/1e6
    if not start.between(1.5e9,2e9).all(): raise ValueError('Unrecognized microsecond timestamp units')
    labels=pd.to_numeric(frame['Label'],errors='raise').astype(int)
    if not labels.isin([0,1]).all(): raise ValueError('Unrecognized publisher binary label')
    valid=end>=start
    if quality is not None:
        quality['input_rows']=quality.get('input_rows',0)+len(frame)
        for label in (0,1):
            key='negative_duration_dropped_label_'+str(label)
            quality[key]=quality.get(key,0)+int(((~valid)&(labels==label)).sum())
    row_ids=np.arange(offset,offset+len(frame))[valid.to_numpy()]
    frame=frame.loc[valid].reset_index(drop=True);start=start[valid].reset_index(drop=True);end=end[valid].reset_index(drop=True);labels=labels[valid].reset_index(drop=True)
    out=pd.DataFrame({'source':source,'capture':source,'row':row_ids,
        'start':start,'end':end,'src':frame['SrcIP'].astype(str),'dst':frame['DstIP'].astype(str),
        'proto':frame['Protocol'].map({6:'tcp',17:'udp',1:'icmp',58:'icmp6'}).fillna('other'),
        'duration':end-start,'y':labels,'family':['']*len(frame)})
    for new,old in [('sent','Total Length of Fwd Packet'),('received','Total Length of Bwd Packet'),
                    ('packets_out','Tot Fwd Pkts'),('packets_in','Tot Bwd Pkts'),('syn','SYN Flag Cnt'),('rst','RST Flag Cnt')]:
        out[new]=pd.to_numeric(frame[old],errors='raise')
        if not np.isfinite(out[new]).all() or (out[new]<0).any(): raise ValueError('Invalid nonnegative measurement: '+old)
    return pa.Table.from_pandas(out,schema=SCHEMA,preserve_index=False)

def zeek_rows(path):
    fields=None
    with Path(path).open(encoding='utf8') as stream:
        for line in stream:
            if line.startswith('#fields'): fields=line.rstrip('\n').split('\t')[1:]
            elif line.startswith('#') or not line.strip(): continue
            elif line.startswith('{'): yield json.loads(line)
            else:
                if fields is None: raise ValueError('Zeek TSV is missing #fields')
                values=line.rstrip('\n').split('\t')
                if len(values)!=len(fields): raise ValueError('Zeek field count mismatch')
                yield dict(zip(fields,values))

def normalize_cic(manifest_path, output):
    """Manifest pins each offline Zeek conn.log, capture and publisher PCAP class.

    This is capture-class supervision, not independently relabelled individual packets.
    The manifest must record the PCAP SHA256, extraction command and Zeek version.
    """
    manifest=json.loads(Path(manifest_path).read_text(encoding='utf8')); items=manifest['captures']
    if len({item['capture'] for item in items})!=len(items): raise ValueError('Capture IDs must be unique')
    rows=[]; summaries=[]
    output.parent.mkdir(parents=True,exist_ok=True)
    with pq.ParquetWriter(output,SCHEMA,compression='zstd') as writer:
        for item in items:
            for required in ['capture','conn_log','conn_log_sha256','pcap_sha256','family','label','source_url','zeek_version','extraction_command']:
                if required not in item: raise ValueError('Missing CIC provenance: '+required)
            path=Path(item['conn_log'])
            if not path.is_absolute(): path=Path(manifest_path).parent/path
            if digest(path)!=item['conn_log_sha256']: raise ValueError('CIC log checksum mismatch')
            if item['label'] not in (0,1) or (item['label']==1 and not item['family']): raise ValueError('Invalid capture label/family')
            kept=0; loss_dropped=0
            def number(record,key):
                value=record.get(key,0)
                parsed=0. if value in (None,'-','(empty)','') else float(value)
                if not np.isfinite(parsed) or parsed<0:raise ValueError('Expected finite nonnegative Zeek measurement: '+key)
                return parsed
            for index,record in enumerate(zeek_rows(path)):
                start=number(record,'ts');duration=number(record,'duration');sent=number(record,'orig_bytes');received=number(record,'resp_bytes')
                missed=number(record,'missed_bytes')
                if missed/max(1.,sent+received+missed)>.01: loss_dropped+=1;continue
                history=str(record.get('history',''))
                rows.append({'source':'ciciot2023','capture':item['capture'],'row':index,'start':start,'end':start+duration,
                    'src':str(record['id.orig_h']),'dst':str(record['id.resp_h']),'proto':record['proto'],'duration':duration,
                    'sent':sent,'received':received,'packets_out':number(record,'orig_pkts'),'packets_in':number(record,'resp_pkts'),
                    # Zeek history indicates presence, unlike CICFlowMeter flag counts. These fields are NOT used in the shared contract.
                    'syn':int('S' in history),'rst':int('R' in history or 'r' in history),'y':item['label'],
                    'family':item['family'] if item['label'] else ''})
                kept+=1
                if len(rows)>=10000: writer.write_table(pa.Table.from_pylist(rows,schema=SCHEMA));rows=[]
            summaries.append({'capture':item['capture'],'kept':kept,'missed_byte_ratio_dropped':loss_dropped})
        if rows: writer.write_table(pa.Table.from_pylist(rows,schema=SCHEMA))
    return {'source':'ciciot2023','manifest_sha256':digest(manifest_path),'supervision':'capture-level PCAP class inherited by flow',
            'availability_time':'flow end lower bound; actual Zeek emission unavailable','captures':summaries,
            'loss_filter':'missed_bytes / max(1, orig_bytes + resp_bytes + missed_bytes) > 0.01; byte-loss proxy, not packet-loss ground truth'}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--source',choices=['lspr23','lspr24','ciciot2023'],required=True)
    ap.add_argument('--mode',choices=['pilot','full'],default='pilot');ap.add_argument('--rows',type=int,default=300000)
    ap.add_argument('--cic-manifest',type=Path);args=ap.parse_args()
    if args.rows<1: raise ValueError('rows must be positive')
    output=ROOT/f'data/normalized/{args.mode}/{args.source}.parquet';output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists(): raise FileExistsError('Preserve existing normalized evidence: '+str(output))
    started=time.time();count=0;positives=0;provenance={'mode':args.mode,'source':args.source,'selection':[],
        'feature_allowlist':LSPR_COLUMNS[:-1],'excluded':'ALL unlisted columns, including IDS Category/Severity and endpoint roles',
        'family_labels_available':args.source=='ciciot2023','availability_time':'flow last-packet time; export delay unmeasured','quality':{}}
    partial=output.with_suffix('.partial.parquet')
    if args.source=='ciciot2023':
        if not args.cic_manifest: raise ValueError('--cic-manifest required; no fabricated source labels')
        provenance.update(normalize_cic(args.cic_manifest,partial)); count=pq.ParquetFile(partial).metadata.num_rows
    else:
        source=args.source;filename='ls23pr_flows.zip' if source=='lspr23' else 'lspr24_v2.parquet'
        remote=None
        if args.mode=='full':
            source_path=ROOT/'data/raw'/source/filename
            manifest=json.loads((ROOT/f'data/raw/{source}_manifest.json').read_text())
            entry=next(x for x in manifest if Path(x['path']).name==filename)
            if digest(source_path)!=entry['sha256']: raise ValueError('Full source SHA256 mismatch')
            handle=source_path;provenance['full_source_sha256']=entry['sha256']
        else:
            remote,entry=public_file('8042347' if source=='lspr23' else '14900873',filename,ROOT/'data/range_cache'/source)
            handle=remote;provenance['publisher_checksum']=entry['checksum']
        with pq.ParquetWriter(partial,SCHEMA,compression='zstd') as writer:
            if source=='lspr23':
                with zipfile.ZipFile(handle) as archive:
                    members=[n for n in archive.namelist() if n.endswith('.csv')]
                    if len(members)!=1: raise ValueError('Unexpected LSPR23 ZIP layout')
                    with archive.open(members[0]) as stream:
                        for chunk in pd.read_csv(stream,usecols=LSPR_COLUMNS,chunksize=50000):
                            if args.mode=='pilot': chunk=chunk.iloc[:max(0,args.rows-count)]
                            if chunk.empty: break
                            table=lspr_frame(chunk.reset_index(drop=True),source,count,provenance['quality']);writer.write_table(table)
                            count+=len(chunk);positives+=int(table['y'].to_numpy().sum())
                            print(json.dumps({'source':source,'rows':count,'positive':positives,'seconds':time.time()-started}),flush=True)
                            if args.mode=='pilot' and count>=args.rows: break
                provenance['selection']=[{'csv_row_start':0,'csv_row_stop':count,'rule':'fixed prefix; pilot is NOT representative of full exercise' if args.mode=='pilot' else 'complete source'}]
            else:
                file=pq.ParquetFile(handle);groups=sorted({0,file.num_row_groups//2,file.num_row_groups-1}) if args.mode=='pilot' else range(file.num_row_groups)
                quota=max(1,args.rows//len(groups))
                for group in groups:
                    frame=file.read_row_group(group,columns=LSPR_COLUMNS).to_pandas()
                    if args.mode=='pilot': frame=frame.iloc[:quota]
                    offset=sum(file.metadata.row_group(i).num_rows for i in range(group))
                    table=lspr_frame(frame.reset_index(drop=True),source,offset,provenance['quality']);writer.write_table(table)
                    count+=len(frame);positives+=int(table['y'].to_numpy().sum())
                    provenance['selection'].append({'row_group':group,'prefix_rows':len(frame),'source_row_offset':offset})
                    print(json.dumps({'source':source,'rows':count,'positive':positives,'seconds':time.time()-started}),flush=True)
        if remote: provenance['range_manifest']=remote.manifest();remote.close()
    partial.replace(output);provenance.update(rows=pq.ParquetFile(output).metadata.num_rows,source_rows_selected=count,sha256=digest(output),seconds=time.time()-started)
    output.with_suffix('.json').write_text(json.dumps(provenance,indent=2),encoding='utf8')
    print(json.dumps({'complete':str(output),'rows':count,'sha256':provenance['sha256']}),flush=True)

if __name__=='__main__': main()
