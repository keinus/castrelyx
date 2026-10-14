import unittest,json
from pathlib import Path
from correlation import Event,Correlator,zeek_event,host_event
from contract import response_plan,decision_plan

class PipelineTests(unittest.TestCase):
    def network(self):return Event('net',100,103,'zeek','A','conn','10.0.0.1','8.8.8.8',40000,443,'tcp',100,102,120)
    def host(self,port=443):return Event('host',101,102,'host','A','host_network','10.0.0.1','8.8.8.8',40000,port,'tcp',image='browser.exe')
    def test_matches_tuple_and_time(self):
        c=Correlator();c.add(self.host());c.add(self.network());self.assertEqual(c.snapshot('A',103)['relations']['matched_process_flows'],1)
    def test_wrong_port_does_not_join(self):
        c=Correlator();c.add(self.host(445));c.add(self.network());self.assertEqual(c.snapshot('A',103)['relations']['matched_process_flows'],0)
    def test_future_availability_hidden(self):
        c=Correlator();c.add(self.host());c.add(self.network());self.assertEqual(c.snapshot('A',102)['network']['flows'],0)
    def test_duplicates_not_counted(self):
        c=Correlator();c.add(self.network());c.add(self.network());self.assertEqual(c.snapshot('A',103)['network']['flows'],1)
    def test_expired_evidence_removed(self):
        c=Correlator();c.add(self.network());self.assertEqual(c.snapshot('A',500)['network']['flows'],0)
    def test_wrong_entity_isolated(self):
        c=Correlator();c.add(self.network());self.assertEqual(c.snapshot('B',103)['network']['flows'],0)
    def test_conn_end_availability(self):
        e=zeek_event({'ts':100,'duration':25,'uid':'a','id.orig_h':'1.2.3.4','id.resp_h':'2.3.4.5','conn_state':'SF'})
        self.assertEqual(e.available,125);self.assertTrue(e.availability_estimated)
    def test_protected_target_cannot_isolate(self):
        p=response_plan('HOST_ISOLATE',.999,{'protected_asset':True,'identity_verified':True},'A')
        self.assertEqual(p['action'],'COLLECT_MORE');self.assertFalse(p['executed'])
    def test_unknown_identity_cannot_isolate(self):
        self.assertEqual(response_plan('HOST_ISOLATE',.99,{},'A')['action'],'COLLECT_MORE')
    def test_overflow_cannot_clear_or_restrict_host(self):
        for action in ('OBSERVE','HOST_ISOLATE'):
            answers={'response':{'choice':action,'answer_confidence':.999},'threat':{'threat':True}}
            p=decision_plan(answers,{'identity_verified':True},'A',{action:{'min_confidence':.9}},{'buffer_dropped':1})
            self.assertEqual(p['action'],'COLLECT_MORE');self.assertEqual(p['deferred_reason'],'incomplete_evidence_buffer_overflow')
    def test_conflicting_threat_and_response_deferred(self):
        a={'response':{'choice':'FLOW_DROP','answer_confidence':.999},'threat':{'threat':False}}
        self.assertEqual(decision_plan(a,{},'A',{'FLOW_DROP':{'min_confidence':.5}})['deferred_reason'],'threat_response_disagreement')
    def test_missing_thresholds_deferred(self):
        a={'response':{'choice':'HOST_ISOLATE','answer_confidence':.999},'threat':{'threat':True}}
        self.assertEqual(decision_plan(a,{'identity_verified':True},'A',{})['action'],'COLLECT_MORE')
    def test_no_cross_split_groups(self):
        root=Path(__file__).resolve().parent/'data/processed';seen={}
        for name in ('train','validation','calibration','policy_validation','test'):
            for line in (root/(name+'.jsonl')).read_text(encoding='utf8').splitlines():
                r=json.loads(line);g=r['group_id'];self.assertTrue(g not in seen or seen[g]==name);seen[g]=name
                self.assertFalse(any(k in r['state'] for k in ('gold','label','labels','group_id','case_id')))

if __name__=='__main__':unittest.main()
