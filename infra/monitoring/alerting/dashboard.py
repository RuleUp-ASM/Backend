"""서버(운영) 대시보드 ruleup-<env>-ops — 노션 「운영 경보 상세」 §4.2 패널 순서(OPS-1~6, OPS-6 JVM 은 10/15 리뷰). 재실행 안전.
prod 는 이 화면 + 사용자 지표(business_dashboard.py) + 감시망(observe_dashboard.py), stg 는 business 없이.
런북(RB-01~03)이 패널 번호로 이 화면을 가리킨다 — 순서를 바꾸면 노션 런북도 고친다.
사용: python dashboard.py [prod|stg]"""
import json
import os
import sys

import boto3

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'lambdas'))
import slo_core  # noqa: E402

ENV = sys.argv[1] if len(sys.argv) > 1 else 'prod'
with open(os.path.join(HERE, '..', 'env', f'{ENV}.json'), encoding='utf-8') as f:
    REG = json.load(f)
R, MON = REG['api_region'], REG['monitor_region']
cw = boto3.client('cloudwatch', region_name=R)
LB, TG = REG['load_balancer'], REG['target_group']
NS, P, LOG = f'RuleUp/App/{ENV}', f'ruleup-{ENV}', f'/ecs/ruleup-{ENV}-api'
SVC = REG['metric_service']
BASE = ['Env', ENV, 'Service', SVC]
OPS = list(slo_core.OPERATIONS)
CONSOLE = f'https://{R}.console.aws.amazon.com/cloudwatch/home?region={R}'
widgets = []
y = 0


def text(md, h=1):
    global y
    widgets.append({'type': 'text', 'x': 0, 'y': y, 'width': 24, 'height': h, 'properties': {'markdown': md}})
    y += h


def row(*ws, h=6):
    global y
    x = 0
    ws = [w for w in ws if w[1]]   # 비어 있는 패널(아직 없는 원천)은 그리지 않는다 — PutDashboard 가 거절한다
    for title, metrics, extra in ws:
        w = 24 // len(ws)
        p = {'title': title, 'region': R, 'view': 'timeSeries', 'stacked': False, 'period': 300, 'metrics': metrics}
        p.update(extra or {})
        widgets.append({'type': 'metric', 'x': x, 'y': y, 'width': w, 'height': h, 'properties': p})
        x += w
    y += h


def alarms(title, prefix, region, h=3):
    global y
    c = boto3.client('cloudwatch', region_name=region)
    arns = [a['AlarmArn'] for pg in c.get_paginator('describe_alarms').paginate(AlarmNamePrefix=prefix)
            for a in pg['MetricAlarms'] + pg.get('CompositeAlarms', [])][:100]
    if arns:
        widgets.append({'type': 'alarm', 'x': 0, 'y': y, 'width': 24, 'height': h, 'properties': {
            'title': title, 'alarms': arns, 'sortBy': 'stateUpdatedTimestamp', 'states': ['ALARM', 'INSUFFICIENT_DATA', 'OK']}})
        y += h


alb = lambda m, s, **o: ['AWS/ApplicationELB', m, 'LoadBalancer', LB, 'TargetGroup', TG, {'stat': s, **o}]
sli = lambda m, op, s='Sum', **o: ['RuleUp/SLI', m, *BASE, 'Operation', op, {'stat': s, 'label': op, **o}]
ops_m = lambda m, s='Maximum', *extra, **o: ['RuleUp/Ops', m, *BASE, *extra, {'stat': s, **o}]
app = lambda m, s='Sum', **o: [NS, m, {'stat': s, **o}]

alarms(f'경보 상태 — 서울 ({ENV})', f'{P}-', R)
alarms(f'경보 상태 — SLO·외부 점검·자원 고갈 ({MON})', f'{P}-P', MON)
text(f'**로그** [{LOG}]({CONSOLE}#logsV2:log-groups/log-group/{LOG.replace("/", "$252F")}) · [Logs Insights]({CONSOLE}#logsV2:logs-insights)　'
     f'**트레이스** [X-Ray]({CONSOLE}#xray:traces/query) — `service("ruleup-api-{ENV}") AND annotation.http_route = "/api/v1/..."`, 로그 traceId `abcd1234…` → `1-abcd1234-…`　'
     f'**감시망** 대시보드 `{REG["dashboards"]["observe"]}`({MON})　**런북** [장애 대응]({REG["runbook_url"]})'
     + (f'　**사용자 지표** `{REG["dashboards"]["business"]}`' if REG['dashboards'].get('business') else ''), h=2)

# ---------------- OPS-1 · CUJ/SLO ----------------
text('## OPS-1 · CUJ/SLO — 버짓 잔량·표본·Rule Flag (배포 중단·호출 상태)')
widgets.append({'type': 'metric', 'x': 0, 'y': y, 'width': 24, 'height': 3, 'properties': {
    'title': '30일 버짓 잔량(%) — 표본 1,000건 미만은 참고값(자동 배포 중단 없음)', 'region': MON, 'view': 'singleValue', 'period': 900,
    'metrics': [['RuleUp/SLO', 'BudgetRemainingPct', *BASE, 'Operation', op, {'stat': 'Minimum', 'label': op}] for op in OPS]}})
y += 3
row(('가용성 BurnRate — refresh', [['RuleUp/SLO', 'BurnRate', *BASE, 'Operation', 'refresh', 'Window', w, {'stat': 'Maximum', 'label': w, 'region': MON}]
                                   for w in ('5m', '60m', '360m', '1440m')], {'region': MON, 'period': 60,
                                   'annotations': {'horizontal': [{'value': 14.4, 'label': 'SLO-01'}, {'value': 6, 'label': 'SLO-02'}]}}),
    ('가용성 BurnRate — sync', [['RuleUp/SLO', 'BurnRate', *BASE, 'Operation', 'sync', 'Window', w, {'stat': 'Maximum', 'label': w, 'region': MON}]
                                for w in ('5m', '60m', '360m', '1440m')], {'region': MON, 'period': 60,
                                'annotations': {'horizontal': [{'value': 14.4, 'label': 'SLO-01'}, {'value': 6, 'label': 'SLO-02'}]}}),
    ('적격 요청 수(EligibleCount, 0 포함)', [sli('EligibleCount', op) for op in OPS], {'period': 300}))

# ---------------- OPS-2 · API ----------------
text('## OPS-2 · API — Operation 별 요청·오류·지연 (실패 기능·릴리즈 비교)')
row(('가용성 실패(AvailabilityBadCount)', [sli('AvailabilityBadCount', op) for op in OPS], None),
    ('p95 지연(ms) — DurationMs', [sli('DurationMs', op, 'p95') for op in OPS], None),
    ('ALB 요청·5xx', [alb('RequestCount', 'Sum', label='요청'), alb('HTTPCode_Target_5XX_Count', 'Sum', label='타깃 5xx', yAxis='right'),
                     ['AWS/ApplicationELB', 'HTTPCode_ELB_5XX_Count', 'LoadBalancer', LB, {'stat': 'Sum', 'label': 'ALB 5xx', 'yAxis': 'right'}]], None),
    ('정상 타깃 수', [alb('HealthyHostCount', 'Minimum')], None))
row(('4xx 거절(상태별, HTTP-04 원천)', [['RuleUp/Traffic', 'HttpRejectCount', *BASE, 'Status', s, {'stat': 'Sum', 'label': s}] for s in ('400', '401', '403', '429')], None),
    ('처리량(FLOW 원천)', [['RuleUp/Traffic', n, *BASE, {'stat': 'Sum', 'label': n}] for n in ('LoginCompletedCount', 'SyncReceivedCount', 'PushAttemptCount')], None))


def logs(title, query, width, x):
    widgets.append({'type': 'log', 'x': x, 'y': y, 'width': width, 'height': 8, 'properties': {'title': title, 'region': R, 'view': 'table',
        'query': f"SOURCE '{LOG}' | filter @message like /client_error / "
                 "| parse @message /client_error status=(?<status>\\d+) code=(?<code>\\S+) method=(?<method>\\S+) route=(?<route>\\S+)/ " + query}})


# Logs Insights 는 `sort count() desc` 가 문법 오류다 — 별칭으로 정렬한다.
logs('4xx 오류 코드별', "| stats count(*) as n by status, code | sort n desc | limit 30", 10, 0)
logs('4xx API · 오류 코드별', "| stats count(*) as n by method, route, code | sort n desc | limit 30", 14, 10)
y += 8

# ---------------- OPS-3 · 자원 ----------------
text('## OPS-3 · 자원 — 태스크·CPU·hard 메모리·RES Flag·확장 상한·DB (포화·부족 원인)')
ecs = lambda m, s='Average': ['AWS/ECS', m, 'ClusterName', REG['cluster'], 'ServiceName', REG['service'], {'stat': s}]
row(('태스크 Desired·Running·부족', [ops_m('DesiredCount', 'Minimum'), ops_m('RunningCount', 'Minimum'), ops_m('TaskShortfall')], {'period': 60}),
    ('ECS CPU(%)', [ecs('CPUUtilization'), ecs('CPUUtilization', 'Maximum')], {'period': 60,
                   'annotations': {'horizontal': [{'value': 80}, {'value': 99, 'label': 'ECS-09'}]}}),
    ('하드 메모리(%) — 가장 찬/여유 있는 태스크', [ops_m('MaxMemoryHardPct', 'Maximum', label='최대(ECS-06)'),
                                         ops_m('MaxMemoryHardPct', 'Minimum', label='최소(RES-01)'),
                                         ops_m('MaxMemoryHardPct', 'SampleCount', label='보고 태스크 수', yAxis='right')], {'period': 60}),
    ('RES Flag·확장 막힘', [['RuleUp/Ops', 'CriticalCapacityFlag', *BASE, 'Rule', r, {'stat': 'Maximum', 'label': r, 'region': MON}]
                          for r in ('RES-01', 'RES-02', 'RES-03', 'RES-04')] + [ops_m('ScaleOutBlocked', label='ScaleOutBlocked')], {'period': 60}))
pool = ['Pool', 'ruleup-main']
row(('JVM — Old(GC 후)·GC 정지(%)', [ops_m('OldGenAfterGcPct'), ops_m('GcPauseRatio5m')], {'period': 60}),
    ('Hikari — active%·대기·timeout', [ops_m('MaxPoolActivePct', 'Maximum', *pool), ops_m('MaxPoolPending', 'Maximum', *pool, yAxis='right'),
                                      ops_m('PoolTimeoutDelta', 'Sum', *pool, yAxis='right')], {'period': 60}),
    ('Tomcat busy/max(%)', [ops_m('MaxBusyThreadPct')], {'period': 60}),
    ('종료·OOM·배포 실패', [ops_m('UnexpectedStopCount', 'Sum'), ops_m('OOMStopCount', 'Sum'), ops_m('DeployFailureCount', 'Sum')], None))
rds = lambda m, s='Average': ['AWS/RDS', m, 'DBInstanceIdentifier', REG['rds_instance'], {'stat': s}]
dbm = lambda m, d, s='Maximum': ['RuleUp/DB', m, 'Database', d, 'Env', ENV, {'stat': s}]
row(('RDS CPU(%)·연결 사용률(%)', [rds('CPUUtilization'), dbm('DbConnectionPct', 'primary')], {'period': 60,
     'annotations': {'horizontal': [{'value': 80, 'label': 'DB-01'}, {'value': 99, 'label': 'DB-10 P0'}]}}),
    ('RDS 메모리 — 여유(바이트)·Swap·Flag', [rds('FreeableMemory', 'Minimum'), rds('SwapUsage', 'Maximum'),
                                       *[dbm(f, REG['rds_instance'])[:-1] + [{'stat': 'Maximum', 'label': f, 'yAxis': 'right'}]
                                         for f in ('DbMemoryPressure', 'DbMemoryCritical')]],
     {'period': 60}),
    ('RDS 여유 저장공간(%)·소진 예상(분)', [dbm('FreeStoragePct', REG['rds_instance'], 'Minimum'),
                                       dbm('StorageMinutesToFull', REG['rds_instance'], 'Minimum') + []], {'period': 60}),
    ('락 대기·장기 트랜잭션(초)·데드락', [dbm('OldestLockWaitSec', 'primary'), dbm('OldestTransactionSec', 'primary'),
                                      dbm('DeadlockDelta', 'primary', 'Sum')], {'period': 60}),
    ('RDS 읽기·쓰기 지연(초)', [rds('ReadLatency'), rds('WriteLatency')], {'period': 60}))

# ---------------- OPS-4 · 의존성 ----------------
text('## OPS-4 · 의존성 — Redis·SQS·FCM·LLM·배치 (외부 장애·적체)')
red = lambda m, node, **o: ['AWS/ElastiCache', m, 'CacheClusterId', node, {'stat': 'Average', **o}]
sqs = lambda q, m: ['AWS/SQS', m, 'QueueName', q, {'stat': 'Maximum', 'label': q}]
# 히트율은 경보 없이 화면에만 — 앱 캐시(@Cacheable)는 Caffeine 이라, 이 값은 탐색 ZSET·rate limit 같은 Redis 직접 조회의 적중률이다.
row(('Redis CPU·메모리·히트율(%)', [red(m, n, label=f'{n} {m}') for n in REG['redis_nodes'] for m in ('EngineCPUUtilization', 'DatabaseMemoryUsagePercentage')]
     + [red('CacheHitRate', n, label=f'{n} 히트율') for n in REG['redis_nodes']], None),
    ('SQS 가장 오래된 메시지(초)·DLQ', [sqs(q, 'ApproximateAgeOfOldestMessage') for q in REG['queues']]
     + [sqs(q, 'ApproximateNumberOfMessagesVisible')[:-1] + [{'stat': 'Maximum', 'label': q, 'yAxis': 'right'}] for q in REG['dlqs']], None),
    ('공급자 호출 실패(429·쿼터 포함)', [['RuleUp/Ops', 'ProviderFailureCount', *BASE, 'Feature', f, 'Provider', pv, {'stat': 'Sum', 'label': f'{f}/{pv}'}]
                                    for f, pv in (('generation', 'gemini'), ('generation', 'nova'), ('moderation', 'gemini'),
                                                  ('moderation', 'nova'), ('push', 'fcm'))], None),
    ('LLM p95(ms)·무효 FCM 토큰', [['RuleUp/Ops', 'LlmLatencyMs', *BASE, 'Feature', f, {'stat': 'p95', 'label': f'{f} p95'}] for f in ('generation', 'moderation')]
     + [['RuleUp/Ops', 'InvalidTokenCount', *BASE, 'Provider', 'fcm', {'stat': 'Sum', 'label': '무효 토큰', 'yAxis': 'right'}]], None))
row(('인증 확정 배치', [app('verification.finalize.overdue.value', 'Maximum', label='1시간 넘게 미확정'),
                     app('verification.finalize.failed.count', label='확정 실패'), app('verification.materialize.failed.count', label='채우기 실패'),
                     app('verification.finalize.no_signal.count', label='신호 없이 실패', yAxis='right')], None),
    ('Outbox', [app('outbox.pending.oldest_age_seconds.value', 'Maximum', label='가장 오래된 대기(초)'),
                app('outbox.dead_lettered.count.value', 'Maximum', label='포기 누적', yAxis='right')], None),
    ('보안·불변식', [['RuleUp/Security', 'AdminLoginFailedCount', 'Env', ENV, 'Service', s, {'stat': 'Sum', 'label': f'관리자 로그인 실패({s})'}]
                    for s in ('ruleup-admin', 'ruleup-api')]
     + [['RuleUp/Security', 'RefreshReuseDetectedCount', *BASE, {'stat': 'Sum', 'label': 'refresh 재사용'}],
        ['RuleUp/Ops', 'InvariantViolationCount', *BASE, 'Invariant', 'SCORE_LEDGER', {'stat': 'Sum', 'label': '점수 원장 불일치'}]], None))

# ---------------- OPS-5 · 배포 ----------------
text('## OPS-5 · 배포 — 전후 15분 비교')
text(f'배포 기록: GitHub Actions `{REG["deploy_workflow"]}` · ECS 서비스 `{REG["cluster"]}/{REG["service"]}` 이벤트 탭(태스크 정의 리비전·롤아웃 상태). '
     '배포 전 `.github/scripts/monitoring_preflight.py` 가 ALARM·stale·EligibleCount 누락·BudgetFreeze 를 막는다. '
     'CloudWatch 에는 배포 마커가 없다 — 아래 그래프의 Running 교체 구간이 곧 배포 구간이다.', h=2)
row(('Running·Desired(배포 교체 구간)', [ops_m('RunningCount', 'Minimum'), ops_m('DesiredCount', 'Maximum')], {'period': 60}),
    ('CUJ 실패 합계 vs 4xx', [[{'expression': 'SUM([' + ','.join(f'b{i}' for i in range(len(OPS))) + '])', 'label': '가용성 실패 합', 'id': 'bs'}]]
     + [sli('AvailabilityBadCount', op, id=f'b{i}', visible=False) for i, op in enumerate(OPS)]
     + [['RuleUp/Traffic', 'HttpRejectCount', *BASE, 'Status', '400', {'stat': 'Sum', 'label': '400', 'yAxis': 'right'}]], {'period': 60}))

# ---------------- OPS-6 · JVM 런타임 (10/15 리뷰) ----------------
# 원천은 앱 RuntimeOpsSampler(RuleUp/Ops). 태스크 차원이 없어 Maximum = 가장 나쁜 태스크, ProcessUptimeSec 는 Minimum = 막 재기동한 태스크.
text('## OPS-6 · JVM 런타임 — 힙 영역·GC 정지·스레드·클래스·버퍼 (누수·GC 지연·락 경합)')
row(('힙·Non-heap 사용(MB) — 영역별, 기준선 = 최대 힙', [ops_m('JvmMemoryUsedMb', 'Maximum', 'Area', a, label=a) for a in ('eden', 'survivor', 'old')]
     + [ops_m('JvmHeapMaxMb', label='최대 힙')]
     + [ops_m('JvmMemoryUsedMb', 'Maximum', 'Area', a, label=a, yAxis='right') for a in ('metaspace', 'codecache')], {'period': 60}),
    ('GC — 직후 Old(%)·정지 최대(ms)·정지 횟수', [ops_m('OldGenAfterGcPct', label='Old(GC 후, JVM-01)'), ops_m('GcPauseRatio5m', label='정지 비율 5분(JVM-02)'),
                                             ops_m('JvmGcPauseMaxMs', label='정지 최대(ms)', yAxis='right'),
                                             ops_m('JvmGcPauseCount', 'Sum', label='정지 횟수', yAxis='right')], {'period': 60}),
    ('스레드 — 상태별', [ops_m('JvmThreads', 'Maximum', 'State', st, label=st) for st in ('live', 'runnable', 'waiting', 'blocked', 'daemon', 'peak')],
     {'period': 60}))
row(('클래스 로딩(개)', [ops_m('JvmClassesLoaded')], {'period': 60}),
    ('힙 밖 버퍼(MB) — direct·mapped', [ops_m('JvmBufferUsedMb', 'Maximum', 'Pool', pl, label=pl) for pl in ('direct', 'mapped')], {'period': 60}),
    ('프로세스 CPU(%)·업타임(초, 최소)', [ops_m('ProcessCpuPct', label='CPU'), ops_m('ProcessUptimeSec', 'Minimum', label='업타임', yAxis='right')],
     {'period': 60}))

text('## 최근 오류 로그')
# 대시보드를 열 때마다 Logs Insights 가 이 범위를 훑는다(스캔 GB 과금) — 그래서 ERROR 만, 50줄만.
widgets.append({'type': 'log', 'x': 0, 'y': y, 'width': 24, 'height': 8, 'properties': {'title': f'ERROR 로그 ({LOG})', 'region': R, 'view': 'table',
    'query': f"SOURCE '{LOG}' | fields @timestamp, @message | filter @message like / ERROR / | sort @timestamp desc | limit 50"}})
y += 8
cw.put_dashboard(DashboardName=REG['dashboards']['ops'], DashboardBody=json.dumps({'widgets': widgets}, ensure_ascii=False))
print('ok', REG['dashboards']['ops'], len(widgets))
