"""pagerduty.py 계약 시험 — python3 infra/monitoring/alerting/tests_pagerduty.py (PagerDuty·AWS 호출 없음)"""
import json, os, sys, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pagerduty as pd


class PagerDutyTest(unittest.TestCase):

    def test_primary_first_then_teammate_after_ten_minutes(self):
        """나에게 먼저, 10분 안에 ACK 가 없으면 팀원에게 — 규칙 순서가 곧 호출 순서다."""
        reg = pd.load()['pagerduty']
        body = pd.escalation_policy('PRIMARY', 'TEAMMATE', reg['escalation_delay_minutes'], reg['repeat_loops'])
        rules = body['escalation_rules']
        self.assertEqual([r['targets'][0]['id'] for r in rules], ['PRIMARY', 'TEAMMATE'])
        self.assertEqual(rules[0]['escalation_delay_in_minutes'], 10)
        self.assertGreaterEqual(body['num_loops'], 1, '팀원도 안 받으면 처음부터 다시 울린다')

    def test_service_never_auto_resolves_and_always_high(self):
        body = pd.service('POLICY', 15)
        self.assertIsNone(body['auto_resolve_timeout'], '시간이 지났다고 사건을 닫지 않는다(노션 §2)')
        self.assertEqual(body['acknowledgement_timeout'], 900)
        self.assertEqual(body['incident_urgency_rule'], {'type': 'constant', 'urgency': 'high'})

    def test_test_alarm_follows_real_p0_path(self):
        """시험 경보는 실제 P0 와 같은 전달(Slack 운영·긴급 멘션·PagerDuty)을 타고, 다음 평가에서 저절로 OK 가 되지 않는다."""
        import p0_p1
        reg = pd.load()
        acts = p0_p1.actions(reg, reg['api_region'], 'P0')
        a = pd.test_alarm(reg, acts)
        self.assertTrue(a['AlarmName'].startswith('ruleup-prod-P0-'), 'EventBridge 긴급 채널 멘션이 이 접두어로 잡는다')
        self.assertEqual(a['TreatMissingData'], 'ignore')
        self.assertEqual(a['AlarmActions'], a['OKActions'])
        names = {x.split(':')[-1] for x in a['AlarmActions']}
        self.assertEqual(names, {reg['sns']['alerts'], reg['sns']['p0'], reg['sns']['pagerduty']})

    def test_ledger_keeps_no_secrets(self):
        text = json.dumps(pd.load(), ensure_ascii=False)
        self.assertNotIn('@', text, '원장에 이메일을 넣지 않는다')
        self.assertNotIn('integration_key', text)


if __name__ == '__main__':
    unittest.main()
