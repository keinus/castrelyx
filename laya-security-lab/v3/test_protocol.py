from pathlib import Path
import unittest,json,hashlib
ROOT=Path(__file__).resolve().parent
class Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p=json.loads((ROOT/'protocol.json').read_text());cls.rows={p.stem:[json.loads(s) for s in p.read_text().splitlines()] for p in (ROOT/'data').glob('*.jsonl')}
    def test_manifest(self):
        m=json.loads((ROOT/'data/manifest.json').read_text())
        self.assertEqual(m['protocol_sha256'],hashlib.sha256((ROOT/'protocol.json').read_bytes()).hexdigest())
        for name,r in m['splits'].items():self.assertEqual(r['sha256'],hashlib.sha256((ROOT/f'data/{name}.jsonl').read_bytes()).hexdigest())
    def test_causal_context(self):
        for rows in self.rows.values():
            for r in rows:self.assertLessEqual(r['provenance']['max_available'],r['provenance']['window_end']);self.assertTrue(all(e['age_s']>=0 for e in r['state']['events']))
    def test_disjoint_groups_and_events(self):
        groups={};events={}
        for split,rows in self.rows.items():
            for r in rows:
                self.assertIn(groups.setdefault(r['group'],split),[split])
                for uid in r['provenance']['current_uids']+r['provenance']['context_uids']:
                    key=(r['provenance']['capture'],uid);self.assertEqual(events.setdefault(key,split),split)
    def test_unseen_capture_and_family(self):
        held=set(self.p['unseen_capture_ids'])
        for name in ['train','validation','proxy_validation','calibration']:
            self.assertFalse(held & {r['provenance']['capture'] for r in self.rows[name]})
            self.assertFalse(set(self.p['unseen_families']) & {r['family'] for r in self.rows[name]})
        self.assertFalse(set(self.p['proxy_validation_capture_ids']) & {r['provenance']['capture'] for r in self.rows['train']})
    def test_input_no_identity_labels(self):
        for rows in self.rows.values():
            for r in rows:
                text=json.dumps(r['state']);self.assertNotIn('Malicious',text);self.assertNotIn(r['provenance']['capture'],text)
                self.assertNotIn('label',text);self.assertNotIn('uid',text);self.assertNotIn('family',text)
                self.assertLessEqual(len(r['state']['events']),6)
    def test_both_classes_for_selection(self):
        for name in ['train','validation','proxy_validation','calibration']:self.assertEqual({r['y'] for r in self.rows[name]},{0,1})
if __name__=='__main__':unittest.main()
