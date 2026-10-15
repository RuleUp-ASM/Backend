import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
import slo_core as s

L = 100_000  # 마지막 완료 분


def flat(minutes, eligible, bad, latency_bad=None, end=L):
    return {m: (eligible, bad, bad if latency_bad is None else latency_bad) for m in range(end - minutes + 1, end + 1)}


class BurnRuleTest(unittest.TestCase):

    def test_sustained_20_percent_errors_page_even_below_50_percent(self):
        """20~40% 지속 오류를 「50% 미만」이라 방치하지 않는다 — 14.4배(7.2%)를 넘으면 SLO-01 P0."""
        series = flat(4320, 10, 2)
        flags = s.evaluate_operation(series, L)
        self.assertEqual(flags['SLO-01'][0], 1)
        self.assertEqual(flags['SLO-02'][0], 1)
        self.assertEqual(flags['API-01'][0], 0, '50% 급성 기준에는 못 미친다')
        self.assertEqual(flags['API-02'][0], 1)

    def test_healthy_traffic_gives_evidence_backed_zero(self):
        flags = s.evaluate_operation(flat(4320, 10, 0), L)
        for rid in s.ALL_RULES:
            self.assertEqual(flags[rid][0], 0, rid)

    def test_low_sample_is_not_published(self):
        """짧은 창 요청이 게이트 미만이면 0 을 내지 않는다 — 저표본은 정상 판정이 아니다."""
        flags = s.evaluate_operation(flat(4320, 1, 0), L)   # 5분 5건 < 20
        self.assertIsNone(flags['SLO-01'][0])
        self.assertEqual(flags['SLO-01'][1]['state'], 'low_sample')
        self.assertIsNone(flags['API-01'][0])

    def test_missing_minute_in_short_window_suppresses_flag(self):
        """계측이 한 분 끊기면 5분 창은 유효하지 않다 — 입력 누락을 정상 0 으로 바꾸지 않는다."""
        series = flat(4320, 10, 5)
        del series[L - 1]
        flag, why = s.evaluate_burn(s.BURN_RULES[0], series, L)
        self.assertIsNone(flag)
        self.assertEqual(why['state'], 'insufficient_coverage')

    def test_long_window_without_history_is_pending(self):
        """72시간 이력이 없으면 SLO-04 는 준비 중(발행 안 함), 1시간 창 규칙은 먼저 돈다."""
        series = flat(120, 10, 5)
        flags = s.evaluate_operation(series, L)
        self.assertIsNone(flags['SLO-04'][0])
        self.assertEqual(flags['SLO-01'][0], 1)

    def test_zero_requests_with_heartbeat_is_measurement_impossible(self):
        series = flat(4320, 0, 0)
        flag, why = s.evaluate_burn(s.BURN_RULES[0], series, L)
        self.assertIsNone(flag)
        self.assertEqual(why['state'], 'low_sample')

    def test_short_window_bad_minimum(self):
        """SLO-01 은 짧은 창 실패 ≥3 건이어야 호출한다(20건 중 2건 실패는 BR 20 이어도 호출 안 함)."""
        series = flat(4320, 4, 0)
        series[L] = (4, 2, 2)
        flag, _ = s.evaluate_burn(s.BURN_RULES[0], series, L)
        self.assertEqual(flag, 0)

    def test_latency_rule_counts_slow_good_requests(self):
        series = flat(4320, 10, 0, latency_bad=2)   # 20% 느림 → BR 4
        flag, _ = s.evaluate_burn(s.BURN_RULES[4], series, L)
        self.assertEqual(flag, 1)

    def test_acute_failure_uses_two_minute_window(self):
        series = flat(4320, 10, 0)
        for m in range(L - 1, L + 1):
            series[m] = (10, 5, 5)   # 최근 2분 20건 중 10건 실패
        self.assertEqual(s.evaluate_acute('API-01', series, L)[0], 1)
        for m in range(L - 1, L + 1):
            series[m] = (10, 4, 4)   # 20건 중 8건 = 40%
        self.assertEqual(s.evaluate_acute('API-01', series, L)[0], 0)

    def test_acute_low_sample_in_two_minutes_is_pending(self):
        """2분 창에 요청 20건이 안 되면 판정하지 않는다 — 그 전 분의 실패는 세지 않는다."""
        series = flat(4320, 4, 3)   # 분당 4건 → 2분 8건
        self.assertIsNone(s.evaluate_acute('API-01', series, L)[0])

    def test_api02_uses_three_minute_window(self):
        series = flat(4320, 10, 0)
        series[L - 3] = (10, 10, 10)   # 3분 창 밖의 실패는 보지 않는다
        self.assertEqual(s.evaluate_acute('API-02', series, L)[0], 0)
        series[L - 2] = (10, 2, 2)     # 30건 중 2건 = 6.7%
        self.assertEqual(s.evaluate_acute('API-02', series, L)[0], 1)


class BudgetTest(unittest.TestCase):

    def test_low_traffic_budget_is_review_only(self):
        """30일 400건 중 3건 실패는 잔량 음수지만 저표본 검토 대상일 뿐 자동 중단이 아니다."""
        hourly = {h: (0, 0) for h in range(1, 721)}
        hourly[720] = (400, 3)
        b = s.budget(hourly, 720)
        self.assertLess(b.remaining_pct, 0)
        self.assertFalse(b.enforceable)
        self.assertFalse(b.freeze)

    def test_999_vs_1000_requests(self):
        for total, expected in ((999, False), (1000, True)):
            hourly = {h: (0, 0) for h in range(1, 721)}
            hourly[720] = (total, 10)
            self.assertEqual(s.budget(hourly, 720).freeze, expected, total)

    def test_incomplete_coverage_never_freezes(self):
        hourly = {h: (10, 1) for h in range(100, 721)}   # 621/720 시간
        self.assertFalse(s.budget(hourly, 720).enforceable)


if __name__ == '__main__':
    unittest.main()
