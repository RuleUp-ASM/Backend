"""catalog.py 계약 시험 — python3 infra/monitoring/alerting/tests_catalog.py"""
import json, os, re, sys, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import catalog


def reg(env):
    with open(os.path.join(HERE, '..', 'env', f'{env}.json'), encoding='utf-8') as f:
        return json.load(f)


class CatalogTest(unittest.TestCase):

    def specs(self, env, **rules):
        r = reg(env)
        r['rules'].update(rules)
        return catalog.build(r, 'hc-123')

    def test_names_are_unique_and_fit(self):
        for env in ('prod', 'stg'):
            names = [s['name'] for _, s in self.specs(env, res_enabled=True, detectors_enabled=True)]
            self.assertEqual(len(names), len(set(names)), env)
            self.assertTrue(all(len(n) <= 255 for n in names))

    def test_p0_only_for_contract_ids(self):
        """CPU·메모리·DB·Redis·SQS 사용률 하나로 P0 를 만들지 않는다."""
        for _, s in self.specs('prod', res_enabled=True, detectors_enabled=True):
            if s['severity'] == 'P0':
                self.assertIn(s['id'], catalog.P0_IDS, s['name'])
                self.assertIn('-P0-', s['name'])
            if s['id'] not in catalog.P0_IDS:
                self.assertNotIn('-P0-', s['name'])

    def test_missing_data_policy(self):
        for _, s in self.specs('prod', res_enabled=True):
            p = s['params']
            if s['kind'] == 'composite':
                self.assertNotIn('TreatMissingData', p, 'Composite 에는 누락 정책을 넣지 않는다')
            elif s['id'] in ('SLO-01', 'SLO-02', 'API-01', 'RES-01', 'RES-02', 'RES-03', 'RES-04') or s['severity'] == 'leaf':
                self.assertEqual(p['TreatMissingData'], 'ignore', s['name'])
            elif s['id'] in ('OBS-01', 'NATIVE-01'):
                self.assertEqual(p['TreatMissingData'], 'breaching', s['name'])
            else:
                self.assertEqual(p['TreatMissingData'], 'notBreaching', s['name'])

    def test_edge_leaf_has_no_p0_prefix(self):
        """leaf 이름이 P0 접두어면 긴급 채널 멘션이 leaf 상태 변화마다 울린다."""
        leaves = [s for _, s in self.specs('prod') if s['severity'] == 'leaf']
        self.assertEqual(len(leaves), 2)
        self.assertTrue(all('-P0-' not in s['name'] for s in leaves))
        composite = next(s for _, s in self.specs('prod') if s['kind'] == 'composite')
        self.assertIn(' AND ', composite['params']['AlarmRule'])

    def test_regions(self):
        r = reg('prod')
        for region, s in self.specs('prod', res_enabled=True):
            central = s['id'].split('-')[0] in ('SLO', 'LAT', 'EDGE', 'TLS', 'RES') or s['id'] in ('NATIVE-01',) \
                or (s['id'] == 'OBS-01' and not s['name'].endswith('db-collector')) or s['id'] in ('API-01', 'API-02')
            self.assertEqual(region, r['monitor_region'] if central else r['api_region'], s['name'])

    def test_res_and_detectors_are_gated(self):
        ids = {s['id'] for _, s in self.specs('prod', res_enabled=False, detectors_enabled=False)}
        self.assertFalse(ids & {'RES-01', 'FLOW-01', 'HTTP-04', 'CACHE-01'})

    def test_stg_has_no_native_02_and_limited_slo_rules(self):
        ids = {s['id'] for _, s in self.specs('stg')}
        self.assertNotIn('NATIVE-02', ids)
        self.assertNotIn('SLO-04', ids)
        self.assertIn('API-01', ids)

    def test_every_catalog_yaml_id_is_known(self):
        """alarm-catalog.yaml 의 상태표에 적힌 ID 와 실제 정의가 어긋나지 않게."""
        with open(os.path.join(HERE, '..', 'alarm-catalog.yaml'), encoding='utf-8') as f:
            text = f.read()
        implemented = set(re.findall(r'^\s+- id: ([A-Z]+-\d+)\s*$\n\s+status: (?:active|shadow|gated)', text, re.M))
        built = {s['id'] for _, s in self.specs('prod', res_enabled=True, detectors_enabled=True)}
        self.assertEqual(implemented - built, set(), 'yaml 에는 구현됐다고 적었는데 정의가 없다')


if __name__ == '__main__':
    unittest.main()
