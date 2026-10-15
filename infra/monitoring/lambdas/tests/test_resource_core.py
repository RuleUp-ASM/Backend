import os, sys, unittest
from datetime import datetime, timezone
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
import resource_core as r


def cap(**kw):
    base = dict(healthy=2, mem_min=[50, 50], mem_samples=[2, 2], scale_blocked_history=[0, 0, 0, 0],
                cpu_avg=[30, 30, 30], busy_threads_max=[10, 10], slow_cuj=False, oom_5m=0)
    base.update(kw)
    return r.Capacity(**base)


def at(sec):
    return datetime.fromtimestamp(sec, timezone.utc)


class StopTest(unittest.TestCase):

    def test_oom_needs_reason_not_just_137(self):
        self.assertEqual(r.classify_stop({'stoppedReason': 'Essential container in task exited',
                                          'containers': [{'exitCode': 137, 'reason': 'OutOfMemoryError: Container killed due to memory usage'}]}), 'oom')
        self.assertEqual(r.classify_stop({'stoppedReason': 'Essential container in task exited',
                                          'containers': [{'exitCode': 137}]}), 'unexpected')

    def test_deployment_replacement_is_expected(self):
        self.assertEqual(r.classify_stop({'stopCode': 'ServiceSchedulerInitiated',
                                          'stoppedReason': 'Scaling activity initiated by (deployment ecs-svc/123)'}), 'expected')
        self.assertEqual(r.classify_stop({'stopCode': 'TaskFailedToStart', 'stoppedReason': 'CannotPullContainerError'}), 'unexpected')

    def test_window_dedupes(self):
        tasks = [{'taskArn': 'a', 'stoppedAt': at(100), 'containers': [{'reason': 'OutOfMemoryError'}]},
                 {'taskArn': 'a', 'stoppedAt': at(100), 'containers': [{'reason': 'OutOfMemoryError'}]},
                 {'taskArn': 'b', 'stoppedAt': at(170), 'stopCode': 'TaskFailedToStart'},
                 {'taskArn': 'c', 'stoppedAt': at(30)}]
        self.assertEqual(r.count_stops(tasks, 60, 180), (1, 2))


class ResTest(unittest.TestCase):

    def test_res01_one_full_task_with_spare_is_p1_not_p0(self):
        """태스크 하나만 99% 이고 다른 정상 태스크에 여유가 있으면 P0 가 아니다 — Minimum 은 여유 있는 쪽."""
        self.assertEqual(r.res01(cap(mem_min=[40, 40])), 0)

    def test_res01_last_healthy_task_full(self):
        self.assertEqual(r.res01(cap(healthy=1, mem_min=[99.5, 97], mem_samples=[1, 1])), 1)

    def test_res01_missing_report_suspends(self):
        """정상 대상 2대인데 1대만 보고했으면 판정 보류 — 보고하지 않은 태스크의 여유를 모른다."""
        self.assertIsNone(r.res01(cap(mem_min=[99.5, 99.5], mem_samples=[1, 2])))

    def test_res01_98_needs_blocked_scale_out(self):
        self.assertEqual(r.res01(cap(mem_min=[98.5, 50])), 0)
        self.assertEqual(r.res01(cap(mem_min=[98.5, 50], scale_blocked_history=[1, 0, 0, 0])), 1, '1분이면 충분하다(10/15)')

    def test_res02(self):
        self.assertEqual(r.res02(cap(oom_5m=2, healthy=1)), 1)
        self.assertEqual(r.res02(cap(oom_5m=1, healthy=1)), 0, '단일 OOM 은 P1(ECS-04)')
        self.assertEqual(r.res02(cap(oom_5m=3, healthy=2)), 0)

    def test_res03_cpu_alone_is_not_p0(self):
        self.assertEqual(r.res03(cap(cpu_avg=[99.5, 30, 30])), 0, '확장 가능하면 P1(ECS-09)')
        self.assertEqual(r.res03(cap(cpu_avg=[99.5, 30, 30], scale_blocked_history=[1, 1, 0, 0])), 0, '처리 포화 증거 없음')
        self.assertEqual(r.res03(cap(cpu_avg=[99.5, 30, 30], scale_blocked_history=[1, 1, 0, 0], busy_threads_max=[96, 10])), 1,
                         '1분이면 충분하다(10/15)')

    def test_res03_blocked_must_last_a_minute(self):
        self.assertEqual(r.res03(cap(cpu_avg=[99.5], scale_blocked_history=[1, 0], busy_threads_max=[96])), 0)

    def test_scale_out_blocked(self):
        self.assertEqual(r.scale_out_blocked(6, 6, False), 1)
        self.assertEqual(r.scale_out_blocked(3, 6, False), 0)
        self.assertEqual(r.scale_out_blocked(3, 6, True), 1)


class StorageTest(unittest.TestCase):
    GB = 1024 ** 3

    def test_minutes_to_full_from_15_minute_trend(self):
        free = [10 * self.GB - i * 0 for i in range(16)]
        self.assertIsNone(r.minutes_to_full(free), '줄지 않으면 예측 불가(0 이 아니다)')
        free = [1 * self.GB + i * 0.1 * self.GB for i in range(16)]  # 분당 0.1GB 소비, 남은 1GB
        self.assertAlmostEqual(r.minutes_to_full(free), 10, places=3)

    def test_res04_requires_unexpandable(self):
        free = [1 * self.GB + i * 0.1 * self.GB for i in range(16)]
        self.assertEqual(r.res04(r.Storage([4, 6], free, expandable=True)), 0)
        self.assertEqual(r.res04(r.Storage([4, 6], free, expandable=False)), 1)
        self.assertIsNone(r.res04(r.Storage([None, 4], free, expandable=False)))

    def test_expandable(self):
        self.assertFalse(r.expandable({'AllocatedStorage': 100}))
        self.assertFalse(r.expandable({'AllocatedStorage': 200, 'MaxAllocatedStorage': 200}))
        self.assertTrue(r.expandable({'AllocatedStorage': 100, 'MaxAllocatedStorage': 200}))

    def test_db_memory_pressure_p1_five_minutes(self):
        low = [0.3 * self.GB] * 5    # 4GiB 의 10% = 0.4GiB 아래
        self.assertEqual(r.db_memory_flag('db.t4g.medium', low, [300, 200, 100], r.DB_MEMORY_PRESSURE), 1)
        self.assertEqual(r.db_memory_flag('db.t4g.medium', low, [100, 100], r.DB_MEMORY_PRESSURE), 0, 'Swap 이 늘지 않음')
        self.assertIsNone(r.db_memory_flag('db.t4g.medium', low[:4], [300, 100], r.DB_MEMORY_PRESSURE), '5분이 안 찼다')
        self.assertIsNone(r.db_memory_flag('db.x9.huge', low, [300, 100], r.DB_MEMORY_PRESSURE))

    def test_db_memory_critical_p0_one_minute(self):
        """P0 는 RAM 5% 1분 — 10% 선 아래여도 5% 를 넘으면 P1 만."""
        self.assertEqual(r.db_memory_flag('db.t4g.medium', [0.15 * self.GB], [300, 250, 200], r.DB_MEMORY_CRITICAL), 1)
        self.assertEqual(r.db_memory_flag('db.t4g.medium', [0.3 * self.GB], [300, 250, 200], r.DB_MEMORY_CRITICAL), 0)
        self.assertIsNone(r.db_memory_flag('db.t4g.medium', [0.15 * self.GB], [300], r.DB_MEMORY_CRITICAL), 'Swap 추세 두 점 필요')


if __name__ == '__main__':
    unittest.main()
