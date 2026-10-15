"""SLO·급성 장애 판정 — 노션 「알람 카탈로그」 §1·§2·§4(API-01/02)·§8(버짓). boto3 없이 시험한다.

입력은 Operation 별 분 단위 원천 카운터(앱이 RuleUp/SLI 로 매분 보낸 EligibleCount·AvailabilityBadCount·LatencyBadCount)다.
계산기는 두 창 AND·표본 게이트까지 여기서 판정하고 Rule 별 Flag 하나만 낸다(창별 leaf 경보를 만들지 않는다).

Flag 의 세 상태
  1    조건 충족
  0    두 창 모두 유효하고 표본도 충분한데 조건 미달 — 「정상」이라는 근거가 있는 0
  None 발행하지 않는다(창 coverage 부족·이력 부족·짧은 창 표본 미달). 0 으로 메우지 않는다 —
       P0 경보는 missing=ignore 라 기존 ALARM 이 유지되고, 입력이 끊겼다고 정상으로 돌아가지 않는다.
"""
from dataclasses import dataclass

AVAILABILITY_SLO = 0.995
LATENCY_GOOD = 0.95

# 앱의 SliOperation 과 같은 목록·지연 목표(ms). tests/test_contract.py 가 자바 enum 과 대조한다.
OPERATIONS = {
    'oauth_google': ('C01', 3000), 'oauth_kakao': ('C01', 3000), 'refresh': ('C01', 1000),
    'explore': ('C02', 1000), 'detail': ('C02', 1000),
    'join': ('C03', 1500),
    'sync': ('C04', 3000), 'manual': ('C04', 3000),
    'today': ('C05', 1000), 'progress': ('C05', 1000),
}


@dataclass(frozen=True)
class BurnRule:
    id: str
    kind: str            # 'availability' | 'latency'
    long_min: int
    short_min: int
    burn: float
    short_min_requests: int
    short_min_bad: int
    severity: str


BURN_RULES = (
    BurnRule('SLO-01', 'availability', 60, 5, 14.4, 20, 3, 'P0'),
    BurnRule('SLO-02', 'availability', 360, 30, 6.0, 50, 3, 'P0'),
    BurnRule('SLO-03', 'availability', 1440, 120, 3.0, 50, 0, 'P1'),
    BurnRule('SLO-04', 'availability', 4320, 360, 1.0, 100, 0, 'P1'),
    BurnRule('LAT-01', 'latency', 60, 5, 3.0, 30, 0, 'P1'),
)
ALL_RULES = tuple(r.id for r in BURN_RULES) + ('API-01', 'API-02')
LONGEST_WINDOW_MIN = max(r.long_min for r in BURN_RULES)
# refresh·sync 만 가용성 BurnRate 4창을 대시보드용으로 낸다(노션 §8 선택 진단 8개).
BURN_DIAGNOSTIC_OPS = ('refresh', 'sync')
BURN_DIAGNOSTIC_WINDOWS = (5, 60, 360, 1440)


@dataclass(frozen=True)
class Window:
    minutes: int
    present: int
    eligible: int
    bad: int
    latency_bad: int

    @property
    def valid(self):
        """누락 분이 창의 1% 이하일 때만 유효(5분·1시간 창은 한 분도 빠지면 안 된다)."""
        return self.minutes - self.present <= int(self.minutes * 0.01)

    @property
    def coverage(self):
        return self.present / self.minutes if self.minutes else 0.0

    def burn(self, kind):
        if self.eligible <= 0:
            return None
        if kind == 'latency':
            return self.latency_bad / self.eligible / (1 - LATENCY_GOOD)
        return self.bad / self.eligible / (1 - AVAILABILITY_SLO)


def window(series, last_minute, minutes):
    """series: {분 인덱스: (eligible, bad, latency_bad)} — 데이터포인트가 있던 분만 들어 있다."""
    e = b = lb = present = 0
    for m in range(last_minute - minutes + 1, last_minute + 1):
        row = series.get(m)
        if row is None:
            continue
        present += 1
        e += row[0]; b += row[1]; lb += row[2]
    return Window(minutes, present, e, b, lb)


def evaluate_burn(rule, series, last_minute):
    """(flag, 근거 dict). flag 는 1·0·None."""
    long_w = window(series, last_minute, rule.long_min)
    short_w = window(series, last_minute, rule.short_min)
    why = {'rule': rule.id, 'long': _w(long_w, rule.kind), 'short': _w(short_w, rule.kind)}
    if not long_w.valid or not short_w.valid:
        return None, {**why, 'state': 'insufficient_coverage'}
    if short_w.eligible < rule.short_min_requests:
        return None, {**why, 'state': 'low_sample'}
    hit = (long_w.burn(rule.kind) or 0) >= rule.burn and (short_w.burn(rule.kind) or 0) >= rule.burn \
        and _bad(short_w, rule.kind) >= rule.short_min_bad
    return (1 if hit else 0), {**why, 'state': 'breach' if hit else 'ok'}


# 급성 판정 창(분) — 노션 10/15 리뷰: API-01 2분(P0 1~2분 내 호출), API-02 3분.
ACUTE_WINDOW_MIN = {'API-01': 2, 'API-02': 3}


def evaluate_acute(rule_id, series, last_minute):
    """API-01(P0): 2분 실패율 ≥50%·요청 ≥20·실패 ≥10 / API-02(P1): 3분 실패율 ≥5%·요청 ≥20."""
    w = window(series, last_minute, ACUTE_WINDOW_MIN[rule_id])
    why = {'rule': rule_id, 'short': _w(w, 'availability')}
    if not w.valid:
        return None, {**why, 'state': 'insufficient_coverage'}
    if w.eligible < 20:
        return None, {**why, 'state': 'low_sample'}
    rate = w.bad / w.eligible
    hit = rate >= 0.5 and w.bad >= 10 if rule_id == 'API-01' else rate >= 0.05
    return (1 if hit else 0), {**why, 'state': 'breach' if hit else 'ok'}


def evaluate_operation(series, last_minute):
    """Operation 하나의 Rule 7개 → {rule_id: (flag, why)}."""
    out = {r.id: evaluate_burn(r, series, last_minute) for r in BURN_RULES}
    for rid in ('API-01', 'API-02'):
        out[rid] = evaluate_acute(rid, series, last_minute)
    return out


def burn_diagnostics(series, last_minute):
    """{창(분): BurnRate} — 창이 유효하고 요청이 있을 때만."""
    out = {}
    for minutes in BURN_DIAGNOSTIC_WINDOWS:
        w = window(series, last_minute, minutes)
        if w.valid and w.eligible > 0:
            out[minutes] = w.burn('availability')
    return out


@dataclass(frozen=True)
class Budget:
    hours_present: int
    eligible: int
    bad: int

    @property
    def coverage(self):
        return self.hours_present / 720

    @property
    def remaining_pct(self):
        if self.eligible <= 0:
            return None
        return 100.0 * (1 - self.bad / (self.eligible * (1 - AVAILABILITY_SLO)))

    @property
    def enforceable(self):
        """자동 배포 중단을 적용해도 되는 표본인가 — 완전한 30일·적격 ≥1,000·coverage ≥99%."""
        return self.coverage >= 0.99 and self.eligible >= 1000

    @property
    def freeze(self):
        r = self.remaining_pct
        return self.enforceable and r is not None and r <= 0


def budget(hourly, last_hour):
    """hourly: {시간 인덱스: (eligible, bad)} — 최근 30일(720시간)."""
    e = b = present = 0
    for h in range(last_hour - 719, last_hour + 1):
        row = hourly.get(h)
        if row is None:
            continue
        present += 1
        e += row[0]; b += row[1]
    return Budget(present, e, b)


def _bad(w, kind):
    return w.latency_bad if kind == 'latency' else w.bad


def _w(w, kind):
    br = w.burn(kind)
    return {'min': w.minutes, 'coverage': round(w.coverage, 4), 'eligible': w.eligible,
            'bad': _bad(w, kind), 'burn': None if br is None else round(br, 3)}
