import contextlib, io, os, sys, unittest
from datetime import datetime, timezone
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.dirname(__file__))
import fake_aws

REG = os.path.join(os.path.dirname(__file__), '..', '..', 'env', 'stg.json')


class HandlerSmokeTest(unittest.TestCase):

    def setUp(self):
        self.put = fake_aws.install()
        quiet = contextlib.redirect_stdout(io.StringIO())   # 구조화 로그는 시험 출력에서 뺀다
        quiet.__enter__()
        self.addCleanup(quiet.__exit__, None, None, None)
        os.environ['RULEUP_REGISTRY'] = REG
        for m in ('slo_calculator', 'resource_monitor', 'detectors', 'edge_probe'):
            sys.modules.pop(m, None)

    def names(self, namespace):
        return {d['MetricName'] for _, ns, data in self.put if ns == namespace for d in data}

    def test_slo_calculator_publishes_flags_budget_and_heartbeat(self):
        import slo_calculator
        slo_calculator.handler({'time': '2026-10-11T10:15:00Z'}, None)
        self.assertIn('AlarmFlag', self.names('RuleUp/SLO'))
        self.assertIn('BudgetFreeze', self.names('RuleUp/SLO'))
        self.assertEqual(self.names('RuleUp/Observe'), {'CollectorHeartbeat'})
        regions = {r for r, ns, _ in self.put if ns == 'RuleUp/SLO'}
        self.assertEqual(regions, {'us-east-1'}, 'Flag 는 중앙 감시 리전으로')

    def test_resource_monitor_publishes_ecs_db_backup_and_flags(self):
        import resource_monitor
        out = resource_monitor.handler({'time': datetime.now(timezone.utc).replace(minute=0).isoformat()}, None)
        self.assertTrue(out['complete'])
        self.assertTrue({'DesiredCount', 'TaskShortfall', 'OOMStopCount', 'ScaleOutBlocked'} <= self.names('RuleUp/Ops'))
        self.assertIn('FreeStoragePct', self.names('RuleUp/DB'))
        self.assertIn('BackupEnabled', self.names('RuleUp/Backup'))
        self.assertIn('CriticalCapacityFlag', self.names('RuleUp/Ops'))


if __name__ == '__main__':
    unittest.main()
