import unittest,json,hashlib
from pathlib import Path
from collections import defaultdict
ROOT=Path(__file__).resolve().parent
class ProtocolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows={p.stem:[json.loads(l) for l in p.read_text(encoding='utf8').splitlines()] for p in (ROOT/'data').glob('*.jsonl')}
    def test_no_group_overlap(self):
        seen={}
        for name,rows in self.rows.items():
            for r in rows:
                self.assertTrue(r['group'] not in seen or seen[r['group']]==name);seen[r['group']]=name
    def test_holdout_capture_exclusion(self):
        held={r['provenance']['file'] for r in self.rows['unseen_test']}
        for split in ('train','validation','calibration'):
            self.assertFalse(held & {r['provenance'].get('file') for r in self.rows[split]})
            self.assertFalse(any(r['family'].startswith(('Bot','Web Attack','Infiltration','staging_transfer')) for r in self.rows[split]))
    def test_labels_and_identity_not_in_network_input(self):
        for rows in self.rows.values():
            for r in rows:
                self.assertNotIn('y',r['state']);self.assertNotIn('family',r['state'])
                if r['source']=='cicids2017':self.assertNotIn(r['provenance']['source_host'],json.dumps(r['state']))
    def test_relation_pairs_share_nonbinding_content(self):
        pairs=defaultdict(list)
        for rows in self.rows.values():
            for r in rows:
                if r['source']=='constructed_joint':pairs[r['group']].append(r)
        for pair in pairs.values():
            self.assertEqual(len(pair),2);self.assertEqual({r['y'] for r in pair},{0,1})
            states=[]
            for r in pair:
                s=json.loads(json.dumps(r['state']))
                for event in s['events']:
                    for key in ('process','source','session'):
                        if key in event:self.assertEqual(len(event[key]),7);event[key]='ACTOR'
                states.append(s)
            self.assertEqual(states[0],states[1])
    def test_manifest_hashes(self):
        m=json.loads((ROOT/'data/manifest.json').read_text())
        for split,x in m['splits'].items():self.assertEqual(hashlib.sha256((ROOT/f'data/{split}.jsonl').read_bytes()).hexdigest(),x['sha256'])
if __name__=='__main__':unittest.main()
