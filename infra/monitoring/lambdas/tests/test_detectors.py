import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.modules.setdefault('boto3', type(sys)('boto3'))
import detectors as d


class DecideTest(unittest.TestCase):

    def test_flow_lower_band_needs_expected_floor(self):
        self.assertIsNone(d.decide('lower', 0, 2, 10, 5), '야간 기준선 2건은 판정하지 않는다')
        self.assertEqual(d.decide('lower', 3, 6, 20, 5), 1)
        self.assertEqual(d.decide('lower', 8, 6, 20, 5), 0)

    def test_http_upper_band_needs_real_count(self):
        self.assertEqual(d.decide('upper', 9, 0, 2, 10), 0, '실제 10건 미만은 밴드를 넘어도 경보 아님')
        self.assertEqual(d.decide('upper', 30, 0, 12, 10), 1)

    def test_missing_band_is_pending_not_zero(self):
        self.assertIsNone(d.decide('upper', 30, None, None, 10))
        self.assertIsNone(d.decide('lower', None, 6, 20, 5))

    def test_read_band_by_label(self):
        resp = {'MetricDataResults': [
            {'Id': 'm', 'Label': 'm', 'Values': [12.0]},
            {'Id': 'band', 'Label': 'band Upper', 'Values': [20.0]},
            {'Id': 'band', 'Label': 'band Lower', 'Values': [4.0]}]}
        self.assertEqual(d._read_band(resp, 'm'), (12.0, 4.0, 20.0))


if __name__ == '__main__':
    unittest.main()
