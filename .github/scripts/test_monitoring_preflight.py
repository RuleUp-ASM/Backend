import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import monitoring_preflight as m

OPS = list(m.slo_core.OPERATIONS)


def healthy():
    return {op: [0.0, 3.0, 1.0] for op in OPS}


class PreflightTest(unittest.TestCase):

    def test_alarm_blocks_and_insufficient_data_warns(self):
        block, warn = m.decide([('ruleup-prod-P0-API-01-sync', 'ALARM'), ('ruleup-prod-P0-SLO-01-join', 'INSUFFICIENT_DATA')],
                               healthy(), 100, {})
        self.assertEqual(len(block), 1)
        self.assertEqual(len(warn), 1)

    def test_explicit_zero_passes_but_missing_series_blocks(self):
        """(b) 원천 0 의 최신 데이터는 통과 · (c) 시계열 자체 누락은 차단."""
        raw = {op: [0.0, 0.0, 0.0] for op in OPS}
        self.assertEqual(m.decide([], raw, 0, {})[0], [])
        raw['sync'] = [None, None, 0.0]
        block, _ = m.decide([], raw, 0, {})
        self.assertTrue(any('Operation=sync' in b for b in block))

    def test_zero_counter_with_real_ingress_blocks(self):
        """(d) 원천은 0 인데 ALB 에 유효 요청이 있다 → 계측 누락."""
        raw = {op: [0.0, 0.0, 0.0] for op in OPS}
        block, _ = m.decide([], raw, 120, {})
        self.assertTrue(any('계측 불일치' in b for b in block))

    def test_budget_freeze_blocks_unless_incident_rollback(self):
        self.assertEqual(len(m.decide([], healthy(), 10, {'join': 1})[0]), 1)
        block, warn = m.decide([('x', 'ALARM')], healthy(), 10, {'join': 1}, incident='INC-1')
        self.assertEqual(block, [])
        self.assertEqual(len(warn), 2)


if __name__ == '__main__':
    unittest.main()
