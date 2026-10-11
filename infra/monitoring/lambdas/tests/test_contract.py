"""앱과 계산기가 같은 Operation·지연 목표를 쓰는지 — 한쪽만 바뀌면 시계열이 갈라져 SLO 가 조용히 빈다."""
import os, re, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
import slo_core

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', '..'))
JAVA = os.path.join(ROOT, 'src/main/java/com/ruleup/ruleup_backend/observability/sli/SliOperation.java')


class ContractTest(unittest.TestCase):

    def test_java_enum_matches_calculator(self):
        src = open(JAVA, encoding='utf-8').read()
        java = {m.group(1): (m.group(2), int(m.group(3)))
                for m in re.finditer(r'\w+\("(\w+)", "(C0\d)", HttpMethod\.\w+, "[^"]+", (\d+)\)', src)}
        self.assertEqual(java, slo_core.OPERATIONS)


if __name__ == '__main__':
    unittest.main()
