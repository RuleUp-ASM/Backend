"""pagerduty.py 계약 시험 — python3 infra/monitoring/alerting/tests_pagerduty.py (PagerDuty·AWS 호출 없음)"""
import json, os, sys, unittest
from datetime import datetime, timezone
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

    def test_test_message_looks_like_cloudwatch(self):
        m = pd.cloudwatch_message('123', 'ap-northeast-2', 'ALARM', datetime(2026, 10, 15, tzinfo=timezone.utc))
        self.assertTrue({'AlarmName', 'NewStateValue', 'AlarmArn', 'StateChangeTime', 'AWSAccountId', 'Trigger'} <= set(m))
        self.assertTrue(m['AlarmName'].startswith('ruleup-prod-P0-'), '긴급 채널 멘션과 같은 접두어')
        json.dumps(m)

    def test_ledger_keeps_no_secrets(self):
        text = json.dumps(pd.load(), ensure_ascii=False)
        self.assertNotIn('@', text, '원장에 이메일을 넣지 않는다')
        self.assertNotIn('integration_key', text)


if __name__ == '__main__':
    unittest.main()
