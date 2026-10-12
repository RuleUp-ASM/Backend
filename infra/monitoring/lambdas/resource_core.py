"""임박한 자원 고갈(RES-01~04)·ECS 종료 분류·RDS 저장공간 예측 — 노션 「알람 카탈로그」 §4·§4.2·§5·§7. boto3 없이 시험한다.

RES Flag 도 SLO Flag 와 같은 세 상태다: 1(조건 충족) · 0(입력이 모두 최신·완전한데 조건 미달) · None(판정 보류, 발행 안 함).
경보는 missing=ignore 라 보류 중에는 직전 상태가 유지된다.
"""
from dataclasses import dataclass

# ---------- ECS 종료 분류 (ECS-03·04, RES-02) ----------

_EXPECTED_REASONS = ('Scaling activity initiated', 'deployment', 'Task replaced', 'ECS is performing maintenance')


def classify_stop(task):
    """'oom' · 'unexpected' · 'expected'. exitCode 137 하나만으로 OOM 이라 단정하지 않는다 — 컨테이너 reason 의
    OutOfMemoryError 를 본다. 정상 배포·축소·교체는 서비스 스케줄러가 남긴 사유로만 뺀다."""
    reason = task.get('stoppedReason') or ''
    containers = task.get('containers') or []
    if 'OutOfMemory' in reason or any('OutOfMemory' in (c.get('reason') or '') for c in containers):
        return 'oom'
    if task.get('stopCode') == 'ServiceSchedulerInitiated' and any(r.lower() in reason.lower() for r in _EXPECTED_REASONS):
        return 'expected'
    if task.get('stopCode') == 'UserInitiated':
        return 'expected'
    return 'unexpected'


def count_stops(tasks, start_ts, end_ts):
    """[start, end) 안에 멈춘 태스크 — 시각 창이 곧 중복 제거다(같은 taskArn 이 두 창에 걸리지 않는다)."""
    seen, oom, unexpected = set(), 0, 0
    for t in tasks:
        stopped = t.get('stoppedAt')
        arn = t.get('taskArn')
        if stopped is None or arn in seen:
            continue
        ts = stopped.timestamp()
        if not (start_ts <= ts < end_ts):
            continue
        seen.add(arn)
        kind = classify_stop(t)
        if kind == 'oom':
            oom += 1
            unexpected += 1
        elif kind == 'unexpected':
            unexpected += 1
    return oom, unexpected


def deploy_failures(deployments, start_ts, end_ts):
    return sum(1 for d in deployments
               if d.get('rolloutState') == 'FAILED' and d.get('updatedAt') and start_ts <= d['updatedAt'].timestamp() < end_ts)


# ---------- RES ----------

@dataclass(frozen=True)
class Capacity:
    healthy: int | None                 # ALB 정상 대상 수(지금)
    mem_min: list                       # 최근 완료 분부터 과거로 MaxMemoryHardPct Minimum
    mem_samples: list                   # 같은 분들의 SampleCount(보고한 태스크 수)
    scale_blocked_history: list         # 최근 3분 + 지금 ScaleOutBlocked (최근 → 과거)
    cpu_avg: list                       # AWS/ECS CPUUtilization Average 최근 3분
    busy_threads_max: list              # MaxBusyThreadPct Maximum 최근 2분
    slow_cuj: bool | None               # 적격 ≥20 인 CUJ 중 p95 ≥ 목표 2배가 2분 연속인 것이 있는가
    oom_5m: int | None


def res01(c):
    """정상 태스크 전부 하드 한도 ≥99%(1분 1/1), 또는 전부 ≥98% 2/2 + 확장 불가."""
    if not c.healthy or len(c.mem_min) < 1 or c.mem_samples[0] is None or c.mem_samples[0] < c.healthy:
        return None
    if c.mem_min[0] >= 99:
        return 1
    if len(c.mem_min) >= 2 and None not in c.mem_min[:2] and min(c.mem_min[:2]) >= 98 \
            and c.mem_samples[1] is not None and c.mem_samples[1] >= c.healthy and c.scale_blocked_history[:1] == [1]:
        return 1
    return 0


def res02(c):
    """실제 OOM 종료 ≥2건/5분 AND 정상 대상 ≤1."""
    if c.oom_5m is None or c.healthy is None:
        return None
    return 1 if c.oom_5m >= 2 and c.healthy <= 1 else 0


def res03(c):
    """CPU 평균 ≥99% 3/3 AND 확장 3분 이상 막힘 AND (Tomcat busy ≥95% 2/2 OR CUJ p95 ≥목표 2배 2/2)."""
    if len(c.cpu_avg) < 3 or None in c.cpu_avg[:3] or len(c.scale_blocked_history) < 4:
        return None
    saturated = (len(c.busy_threads_max) >= 2 and None not in c.busy_threads_max[:2] and min(c.busy_threads_max[:2]) >= 95) \
        or bool(c.slow_cuj)
    hit = min(c.cpu_avg[:3]) >= 99 and all(b == 1 for b in c.scale_blocked_history[:4]) and saturated
    return 1 if hit else 0


def scale_out_blocked(desired, max_capacity, recent_scale_out_failed):
    """승인된 상한 도달 또는 최근 ScaleOut 실패가 증명될 때만 1. 오토스케일 대상이 없으면 늘릴 길이 없으니 1."""
    if recent_scale_out_failed:
        return 1
    if max_capacity is None:
        return 1
    return 1 if desired >= max_capacity else 0


@dataclass(frozen=True)
class Storage:
    free_pct: list           # 최근 → 과거, 1분 FreeStorageSpace/AllocatedStorage×100
    free_bytes: list         # 최근 → 과거 15분 이상
    expandable: bool | None  # MaxAllocatedStorage 여유가 있고 자동 확장이 켜져 있는가


def minutes_to_full(free_bytes):
    """최근 15분의 실제 감소 추세를 그대로 늘린 소진 예상(분). 줄지 않거나 표본이 모자라면 None — 0 으로 두지 않는다."""
    pts = [v for v in free_bytes[:16] if v is not None]
    if len(pts) < 10:
        return None
    consumed = pts[-1] - pts[0]          # 과거 − 최근
    if consumed <= 0:
        return None
    per_min = consumed / (len(pts) - 1)
    return pts[0] / per_min


def res04(s):
    """여유 ≤5% 2/2 AND 소진 예상 ≤15분 AND 확장 불가."""
    if len(s.free_pct) < 2 or None in s.free_pct[:2] or s.expandable is None:
        return None
    eta = minutes_to_full(s.free_bytes)
    hit = max(s.free_pct[:2]) <= 5 and eta is not None and eta <= 15 and not s.expandable
    return 1 if hit else 0


def expandable(instance):
    """RDS 스토리지 자동 확장이 실제로 가능한가 — MaxAllocatedStorage 가 없으면 꺼진 것."""
    max_alloc = instance.get('MaxAllocatedStorage')
    if not max_alloc:
        return False
    return instance.get('AllocatedStorage', 0) < max_alloc * 0.99


# RDS 인스턴스 클래스 → RAM(GiB). DB-02 DbMemoryPressure 의 「RAM 의 10%」. 모르는 클래스는 판정하지 않는다.
RDS_RAM_GIB = {
    'db.t4g.micro': 1, 'db.t4g.small': 2, 'db.t4g.medium': 4, 'db.t4g.large': 8, 'db.t4g.xlarge': 16,
    'db.t3.micro': 1, 'db.t3.small': 2, 'db.t3.medium': 4, 'db.t3.large': 8,
    'db.m7g.large': 8, 'db.m7g.xlarge': 16, 'db.m7g.2xlarge': 32, 'db.m6g.large': 8, 'db.m6g.xlarge': 16,
    'db.r7g.large': 16, 'db.r7g.xlarge': 32, 'db.r6g.large': 16, 'db.r6g.xlarge': 32,
}


def db_memory_pressure(instance_class, freeable, swap):
    """최근 10분 내내 Freeable ≤ RAM 10% 이고 그 사이 Swap 이 늘었으면 1. 입력이 모자라면 None."""
    ram = RDS_RAM_GIB.get(instance_class)
    f = [v for v in freeable[:10] if v is not None]
    w = [v for v in swap[:10] if v is not None]
    if ram is None or len(f) < 10 or len(w) < 2:
        return None
    limit = ram * 1024 ** 3 * 0.10
    return 1 if max(f) <= limit and w[0] > w[-1] else 0
