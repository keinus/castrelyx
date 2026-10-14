"""Tests of scientific validity boundaries, plus audits of prepared real data."""
import argparse,copy,json,unittest,tempfile
from pathlib import Path
import pandas as pd
from prepare import state_for,split_blocks
from normalize import lspr_frame,LSPR_COLUMNS,zeek_rows,normalize_cic
from common_v4 import ROOT,view_state
from acquire import digest

class ContractTests(unittest.TestCase):
    def example(self):
        return {'end':100.,'src':'10.0.0.1','dst':'10.0.0.2','proto':'tcp','duration':.2,'sent':100,'received':20,'packets_out':2,'packets_in':1}
    def test_endpoint_renaming_cannot_change_model_input(self):
        target=self.example();older={**target,'end':98.,'dst':'10.0.0.3'}
        first=state_for(target,[older],6)
        renamed=state_for({**target,'src':'host-a','dst':'host-b'},[{**older,'src':'host-a','dst':'host-c'}],6)
        self.assertEqual(first,renamed)
        self.assertNotIn('10.0.0.',json.dumps(first))
    def test_single_flow_has_no_context_information(self):
        target=self.example();older={**target,'end':98.,'src':'other','dst':'fourth'}
        self.assertEqual(view_state(state_for(target,[older],6),'single'),view_state(state_for(target,[],6),'single'))
    def test_labels_and_alerts_do_not_change_input(self):
        target=self.example();self.assertEqual(state_for(target,[],6),state_for({**target,'Label':1,'Category':'malware','y':1},[],6))
    def test_time_blocks_are_ordered_and_disjoint(self):
        result=split_blocks(list(range(100)),{i:100 if i>70 else 1 for i in range(100)})
        previous=-1
        for split in ['train','validation','calibration','known_test']:
            blocks=[b for b,s in result.items() if s==split];self.assertGreater(min(blocks),previous);previous=max(blocks)
    def test_bad_timestamps_are_counted_not_repaired(self):
        row={k:0 for k in LSPR_COLUMNS};row.update(SrcIP='a',DstIP='b',Protocol=6,mTimestampStart=1700000000000000,mTimestampLast=1699999999999999,Label=1)
        good={**row,'mTimestampLast':1700000000000001,'Label':0};quality={}
        table=lspr_frame(pd.DataFrame([row,good]),'lspr24',50,quality)
        self.assertEqual(table.num_rows,1);self.assertEqual(table['row'].to_pylist(),[51]);self.assertEqual(quality['negative_duration_dropped_label_1'],1)
    def test_zeek_tsv_and_json_are_equivalent(self):
        record={'ts':'1700000000.1','id.orig_h':'10.0.0.1','id.resp_h':'10.0.0.2','proto':'tcp','duration':'0.2','orig_bytes':'100','resp_bytes':'20'}
        with tempfile.TemporaryDirectory() as temporary:
            tsv=Path(temporary)/'conn.log';js=Path(temporary)/'conn.json'
            tsv.write_text('#separator \\x09\n#fields\t'+'\t'.join(record)+'\n'+'\t'.join(record.values())+'\n#close\tend\n')
            js.write_text(json.dumps(record)+'\n')
            self.assertEqual(list(zeek_rows(tsv)),[record]);self.assertEqual(list(zeek_rows(js)),[record])
            tsv.write_text('#fields\tts\tproto\n1700000000\n')
            with self.assertRaisesRegex(ValueError,'field count'):list(zeek_rows(tsv))
    def test_cic_adapter_rejects_nonfinite_measurements(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);log=root/'conn.json'
            log.write_text(json.dumps({'ts':1700000000.,'duration':'NaN','id.orig_h':'a','id.resp_h':'b','proto':'tcp'})+'\n')
            manifest=root/'manifest.json'
            manifest.write_text(json.dumps({'captures':[{'capture':'fixture','conn_log':'conn.json','conn_log_sha256':digest(log),
                'pcap_sha256':'test-fixture-not-a-public-capture','family':'','label':0,'source_url':'test fixture',
                'zeek_version':'parser fixture only','extraction_command':'not executed'}]}))
            with self.assertRaisesRegex(ValueError,'finite nonnegative'):normalize_cic(manifest,root/'output.parquet')
    def test_withheld_family_is_excluded_from_entire_training_blocks(self):
        import prepare as module
        import pyarrow as pa
        import pyarrow.parquet as pq
        from normalize import SCHEMA
        original=module.ROOT
        with tempfile.TemporaryDirectory() as temporary:
            module.ROOT=Path(temporary)
            try:
                rows=[]
                for capture,family in [('known','family-A'),('unseen','family-B')]:
                    for block in range(20):
                        for label in (0,1):
                            t=1700000200.+block*300+label
                            r={**self.example(),'source':'ciciot2023','capture':capture,'row':block*2+label,'start':t-.2,'end':t,
                                'y':label,'family':family if label else '','syn':0.,'rst':0.}
                            rows.append(r)
                p=module.ROOT/'data/normalized/pilot/ciciot2023.parquet';p.parent.mkdir(parents=True)
                pq.write_table(pa.Table.from_pylist(rows,schema=SCHEMA),p)
                p.with_suffix('.json').write_text(json.dumps({'sha256':digest(p),'family_labels_available':True}))
                args=module.parser().parse_args(['--name','withheld-test','--source','ciciot2023','--external','','--heldout-family','family-B'])
                module.prepare(args);folder=module.ROOT/'data/experiments/withheld-test'
                fitted=[]
                for split in ['train','validation','calibration']:
                    fitted.extend(json.loads(line) for line in (folder/(split+'.jsonl')).read_text().splitlines())
                held=[json.loads(line) for line in (folder/'unseen_test.jsonl').read_text().splitlines()]
                self.assertTrue(fitted);self.assertTrue(any(r['y'] for r in held))
                self.assertTrue(all(r['capture']=='known' and r['family']!='family-B' for r in fitted))
                self.assertFalse({r['group'] for r in fitted}&{r['group'] for r in held})
            finally:module.ROOT=original

def audit(experiment):
    folder=ROOT/'data/experiments'/experiment;manifest=json.loads((folder/'manifest.json').read_text())
    rows={split:[json.loads(line) for line in (folder/(split+'.jsonl')).read_text().splitlines()] for split in manifest['counts']}
    checks=[]
    for split,items in rows.items():
        if digest(folder/(split+'.jsonl'))!=manifest['counts'][split]['sha256']:raise AssertionError('Split hash mismatch')
        for row in items:
            if set(row['state'])!={'fields','current','history','summary_fields','summary'}:raise AssertionError('Unexpected model fields')
            for flow in row['state']['history']+[row['state']['current']]:
                if len(flow)!=9 or not isinstance(flow[1],int) or not isinstance(flow[2],int):raise AssertionError('Invalid relation identifiers')
                if flow[0]<0:raise AssertionError('Future event exposed')
            for key in ('earliest','latest'):
                t=row['context'][key]
                if t is not None and (t>row['time'] or int(t//manifest['block_seconds'])!=int(row['time']//manifest['block_seconds'])):raise AssertionError('Context crosses time boundary')
            if split in ['train','validation','calibration'] and manifest['heldout_family'] and row['family']==manifest['heldout_family']:raise AssertionError('Held-out attack leaked')
        checks.append({'split':split,'rows':len(items),'sha256':manifest['counts'][split]['sha256']})
    splits=list(rows)
    for i,left in enumerate(splits):
        for right in splits[i+1:]:
            a={r['group'] for r in rows[left] if not r.get('shared_benign_control')};b={r['group'] for r in rows[right] if not r.get('shared_benign_control')}
            if a&b:raise AssertionError('Shared time group between '+left+' and '+right)
            a={r['raw_fingerprint'] for r in rows[left] if not r.get('shared_benign_control')};b={r['raw_fingerprint'] for r in rows[right] if not r.get('shared_benign_control')}
            if a&b:raise AssertionError('Duplicate raw flow between splits')
    train_states={r['state_sha256'] for r in rows['train']}
    repeated={s:sum(r['state_sha256'] in train_states for r in items) for s,items in rows.items() if s!='train'}
    result={'status':'passed','checks':checks,'exact_feature_states_also_in_train':repeated,
        'limits':'Feature repetition can be legitimate; no claim of independent attacks or population representativeness.'}
    (folder/'audit.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2));return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--experiment');args=parser.parse_args()
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(ContractTests)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():raise SystemExit(1)
    if args.experiment:audit(args.experiment)
