"""경보 정의 — 노션 「알람 카탈로그 · SLO 구현 계약」의 ID 그대로. boto3 없이 만들고 시험한다(p0_p1.py 가 적용한다).

등급 규칙(노션 §3·§4.2·「운영 경보 상세」 §2.4)
  P0 은 SLO-01/02 · API-01 · EDGE-01 · NATIVE-01/02 · RES-01~04 만이다. CPU·메모리·DB·Redis·SQS 사용률 하나로는 P0 를 만들지 않는다.
  커스텀 P0(SLO·API-01·RES·EDGE leaf)는 missing=ignore — 입력이 끊겼다고 정상으로 돌아가지 않는다.
  상시 하트비트(OBS-01)·NATIVE-01 은 missing=breaching. 나머지 P1·희소 이벤트는 notBreaching.
이름은 ruleup-<env>-<P0|P1>-<ID>[-<대상>] — 긴급 채널 멘션(EventBridge)이 ruleup-prod-P0- 접두어로 잡는다.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'lambdas'))
import slo_core  # noqa: E402

P0_IDS = {'SLO-01', 'SLO-02', 'API-01', 'EDGE-01', 'NATIVE-01', 'NATIVE-02', 'RES-01', 'RES-02', 'RES-03', 'RES-04'}
RB = {'SLO': 'RB-01', 'LAT': 'RB-01', 'API': 'RB-01', 'EDGE': 'RB-01', 'TLS': 'RB-01', 'NATIVE': 'RB-01', 'AUTH': 'RB-04',
      'ECS': 'RB-02', 'RES': 'RB-02', 'JVM': 'RB-02', 'POOL': 'RB-02', 'THREAD': 'RB-02', 'DB': 'RB-02', 'REDIS': 'RB-02',
      'CACHE': 'RB-02', 'QUEUE': 'RB-03', 'JOB': 'RB-03', 'EXT': 'RB-03', 'TOKEN': 'RB-04', 'FLOW': 'RB-04', 'HTTP': 'RB-04',
      'SEC': 'RB-05', 'INV': 'RB-05', 'BACKUP': 'RB-06', 'OBS': 'RB-06'}

# 노션에서 이 저장소로 옮긴 뒤 이름이 바뀐(또는 등급이 내려간) 옛 경보 — 새 경보를 만든 뒤 지운다.
RETIRED = {
    'prod': ['ruleup-prod-P0-api-no-healthy-target', 'ruleup-prod-P0-ecs-cpu-critical', 'ruleup-prod-P0-ecs-memory-critical',
             'ruleup-prod-P0-api-latency-p95-critical', 'ruleup-prod-P0-rds-ruleup-prod-mysql-storage-low', 'ruleup-prod-P0-api-5xx-ratio',
             'ruleup-prod-P0-api-5xx-alb', 'ruleup-prod-P1-api-hosts-reduced', 'ruleup-prod-P1-ecs-cpu-high', 'ruleup-prod-P1-ecs-memory-high',
             'ruleup-prod-P1-api-latency-p95', 'ruleup-prod-P1-api-5xx-target', 'ruleup-prod-P1-rds-connections',
             'ruleup-prod-P1-sqs-moderation-dlq', 'ruleup-prod-P1-sqs-notifications-dlq', 'ruleup-prod-P1-fcm-success-rate',
             'ruleup-prod-P1-redis-ruleup-prod-redis-001-hit-rate-drop', 'ruleup-prod-P1-redis-ruleup-prod-redis-002-hit-rate-drop',
             'ruleup-prod-P1-sqs-moderation-backlog-age', 'ruleup-prod-P1-sqs-notifications-backlog-age',
             'ruleup-prod-P1-verification-sync-failed', 'ruleup-prod-P0-verification-sync-failed', 'ruleup-prod-P1-outbox-pending-age',
             'ruleup-prod-P1-llm-failed', 'ruleup-prod-P1-llm-latency',
             'ruleup-prod-P1-verification-finalize-overdue', 'ruleup-prod-P1-verification-finalize-failed',
             'ruleup-prod-P1-verification-materialize-failed', 'ruleup-prod-P1-outbox-dead-lettered',
             'ruleup-prod-P1-verification-finalize-late', 'ruleup-prod-rds-connections'],
    'stg': ['ruleup-stg-P0-api-no-healthy-target', 'ruleup-stg-P0-ecs-cpu-critical', 'ruleup-stg-P0-ecs-memory-critical',
            'ruleup-stg-P0-api-latency-p95-critical', 'ruleup-stg-P0-rds-ruleup-stg-mysql-v2-storage-low', 'ruleup-stg-P0-api-5xx-ratio',
            'ruleup-stg-P0-api-5xx-alb', 'ruleup-stg-P1-ecs-cpu-high', 'ruleup-stg-P1-ecs-memory-high', 'ruleup-stg-P1-api-latency-p95',
            'ruleup-stg-P1-api-5xx-target', 'ruleup-stg-P1-rds-connections', 'ruleup-stg-P1-sqs-moderation-dlq',
            'ruleup-stg-P1-sqs-notifications-dlq', 'ruleup-stg-P1-fcm-success-rate', 'ruleup-stg-P1-redis-ruleup-stg-redis-hit-rate-drop',
            'ruleup-stg-P1-sqs-moderation-backlog-age', 'ruleup-stg-P1-sqs-notifications-backlog-age', 'ruleup-stg-moderation-dlq-not-empty',
            'ruleup-stg-P1-verification-sync-failed', 'ruleup-stg-P1-outbox-pending-age', 'ruleup-stg-P1-llm-failed', 'ruleup-stg-P1-llm-latency',
             'ruleup-stg-P1-verification-finalize-overdue', 'ruleup-stg-P1-verification-finalize-failed',
             'ruleup-stg-P1-verification-materialize-failed', 'ruleup-stg-P1-outbox-dead-lettered',
             'ruleup-stg-P1-verification-finalize-late'],
}

LLM_PROVIDERS = (('generation', 'gemini'), ('generation', 'nova'), ('moderation', 'gemini'), ('moderation', 'nova'))


def build(reg, health_check_id=None):
    """[(region, spec)] — spec 은 {'kind': 'metric'|'composite', 'id', 'severity', 'name', 'params'}."""
    env = reg['env']
    p = f'ruleup-{env}'
    mon, api = reg['monitor_region'], reg['api_region']
    svc = reg['metric_service']
    base = {'Env': env, 'Service': svc}
    out = []

    def desc(alarm_id, text):
        rb = RB[alarm_id.split('-')[0]]
        dash = reg['dashboards']['observe' if alarm_id.split('-')[0] in ('OBS', 'EDGE', 'NATIVE', 'TLS') else 'ops']
        return (f'[{alarm_id}] {env} {svc} — {text}\n런북 {rb}: {reg["runbook_url"]}\n대시보드: {dash}\n'
                f'담당: {reg["owner"]} · 카탈로그: {reg["catalog_url"]}')[:1024]

    def add(region, alarm_id, suffix, text, **params):
        sev = 'P0' if alarm_id in P0_IDS else 'P1'
        name = f'{p}-{sev}-{alarm_id}' + (f'-{suffix}' if suffix else '')
        out.append((region, {'kind': 'metric', 'id': alarm_id, 'severity': sev, 'name': name,
                             'params': {'AlarmName': name, 'AlarmDescription': desc(alarm_id, text), **params}}))

    def metric(ns, name, dims, stat, period, evals, dta, threshold, op, missing):
        m = {'Namespace': ns, 'MetricName': name, 'Dimensions': [{'Name': k, 'Value': v} for k, v in dims.items()],
             'Period': period, 'EvaluationPeriods': evals, 'DatapointsToAlarm': dta, 'Threshold': threshold,
             'ComparisonOperator': op, 'TreatMissingData': missing}
        m['ExtendedStatistic' if stat.startswith('p') else 'Statistic'] = stat
        return m

    def math(expression, metrics, period, threshold, op='GreaterThanOrEqualToThreshold', evals=1, dta=1, missing='notBreaching',
             label='value'):
        qs = [{'Id': qid, 'ReturnData': False, 'MetricStat': {'Metric': {'Namespace': ns, 'MetricName': n,
               'Dimensions': [{'Name': k, 'Value': v} for k, v in d.items()]}, 'Period': period, 'Stat': st}}
              for qid, ns, n, d, st in metrics]
        qs.append({'Id': 'e', 'Expression': expression, 'Label': label, 'ReturnData': True})
        return {'Metrics': qs, 'EvaluationPeriods': evals, 'DatapointsToAlarm': dta, 'Threshold': threshold,
                'ComparisonOperator': op, 'TreatMissingData': missing}

    GE, GT, LT, LE = 'GreaterThanOrEqualToThreshold', 'GreaterThanThreshold', 'LessThanThreshold', 'LessThanOrEqualToThreshold'

    # ===================== 중앙 감시 리전 =====================
    rules = slo_core.ALL_RULES if reg['rules'].get('slo_rules') == 'all' else tuple(reg['rules']['slo_rules'])
    texts = {'SLO-01': '가용성 버짓 급속 소진 — BR(1h)·BR(5m) ≥14.4', 'SLO-02': '가용성 버짓 소진 — BR(6h)·BR(30m) ≥6',
             'SLO-03': '느린 버짓 소진 — BR(24h)·BR(2h) ≥3', 'SLO-04': '느린 버짓 소진 — BR(72h)·BR(6h) ≥1',
             'LAT-01': '지연 버짓 소진 — 지연 BR(1h)·BR(5m) ≥3', 'API-01': '급성 장애 — 5분 실패율 ≥50%·요청 ≥20·실패 ≥10',
             'API-02': 'API 실패율 ≥5%(5분·요청 ≥20)'}
    for op in slo_core.OPERATIONS:
        for rule in rules:
            p0 = rule in P0_IDS
            add(mon, rule, op, f'{op}: {texts[rule]}. 입력 누락·저표본이면 Flag 를 내지 않는다(계산기 로그 slo_eval).',
                **metric('RuleUp/SLO', 'AlarmFlag', {**base, 'Operation': op, 'Rule': rule}, 'Maximum', 60, 1, 1, 1, GE,
                         'ignore' if p0 else 'notBreaching'))

    origins = list(reg['probe_origins'])
    leaves = []
    for origin in origins:
        leaf = f'{p}-edge-leaf-{origin}'      # 행동 없는 구성 요소 — P0 접두어를 붙이지 않아 멘션이 따로 울리지 않는다
        leaves.append(leaf)
        out.append((mon, {'kind': 'metric', 'id': 'EDGE-01', 'severity': 'leaf', 'name': leaf, 'params': {
            'AlarmName': leaf, 'AlarmDescription': desc('EDGE-01', f'{origin} 공개 점검 3/3 실패(구성 요소, 단독 알림 없음)'),
            **metric('RuleUp/Edge', 'ProbeFailure', {'Env': env, 'Probe': 'public', 'Origin': origin}, 'Maximum', 60, 3, 3, 1, GE, 'ignore')}}))
        add(mon, 'EDGE-02', origin, f'{origin} 한 지점 공개 점검 3회 중 2회 실패 — DNS·TLS·WAF·본문 단계는 edge_probe 로그',
            **metric('RuleUp/Edge', 'ProbeFailure', {'Env': env, 'Probe': 'public', 'Origin': origin}, 'Maximum', 60, 3, 2, 1, GE, 'notBreaching'))
    name = f'{p}-P0-EDGE-01'
    out.append((mon, {'kind': 'composite', 'id': 'EDGE-01', 'severity': 'P0', 'name': name, 'params': {
        'AlarmName': name, 'AlarmDescription': desc('EDGE-01', '공개 경로 전면 장애 — 두 지점 공개 무인증 점검 모두 1분×3회 실패'),
        'AlarmRule': ' AND '.join(f'ALARM("{leaf}")' for leaf in leaves)}}))
    add(mon, 'TLS-01', None, '공개 인증서 잔여 ≤14일',
        **math('MIN([' + ','.join(f't{i}' for i in range(len(origins))) + '])',
               [(f't{i}', 'RuleUp/Edge', 'CertificateDaysLeft', {'Env': env, 'Origin': o}, 'Minimum') for i, o in enumerate(origins)],
               3600, 14, LE, label='인증서 잔여 일수'))
    if health_check_id:
        add(mon, 'NATIVE-01', None, 'Route 53 공개 헬스체크 비정상 3분 — 계산기·릴레이와 무관한 독립 백스톱',
            **metric('AWS/Route53', 'HealthCheckStatus', {'HealthCheckId': health_check_id}, 'Minimum', 60, 3, 3, 1, LT, 'breaching'))
    if reg['rules'].get('res_enabled'):
        res_text = {'RES-01': '정상 태스크 전부 하드 메모리 ≥99%(마지막 1대 포함)', 'RES-02': 'OOM ≥2회/5분 + 정상 태스크 ≤1',
                    'RES-03': 'CPU ≥99% 3분 + 확장 불가 + 처리 포화', 'RES-04': 'RDS 여유 ≤5% + 15분 내 소진 예상 + 확장 불가'}
        for rid, text in res_text.items():
            add(mon, rid, None, f'임박한 자원 고갈 — {text}. 근거는 resource-monitor 로그 res_eval',
                **metric('RuleUp/Ops', 'CriticalCapacityFlag', {**base, 'Rule': rid}, 'Maximum', 60, 1, 1, 1, GE, 'ignore'))
    components = ['slo-calculator', 'resource-monitor'] + [f'edge-{o}' for o in origins]
    for comp in components:
        add(mon, 'OBS-01', comp, f'{comp} 하트비트 3분 누락 — 관측 누락이지 서비스 장애 확정이 아니다',
            **metric('RuleUp/Observe', 'CollectorHeartbeat', {'Env': env, 'Component': comp}, 'Minimum', 60, 3, 3, 1, LT, 'breaching'))
    if reg['rules'].get('detectors_enabled'):
        add(mon, 'OBS-01', 'anomaly-detector', '이상 탐지 계산기 하트비트 15분 누락',
            **metric('RuleUp/Observe', 'CollectorHeartbeat', {'Env': env, 'Component': 'anomaly-detector'}, 'Minimum', 300, 3, 3, 1, LT, 'breaching'))
        for det in ('FLOW-01', 'FLOW-02', 'FLOW-03', 'HTTP-04-400', 'HTTP-04-401', 'HTTP-04-403', 'HTTP-04-429', 'CACHE-01'):
            alarm_id, suffix = ('HTTP-04', det[-3:]) if det.startswith('HTTP-04') else (det, None)
            add(mon, alarm_id, suffix, f'{det} 평소 범위 이탈 15분(제품 참여율이 아니라 서버가 받은 처리량)',
                **metric('RuleUp/Detectors', 'AlarmFlag', {'Env': env, 'Detector': det}, 'Maximum', 300, 3, 3, 1, GE, 'notBreaching'))

    # ===================== API 리전(서울) =====================
    lb, tg = reg['load_balancer'], reg['target_group']
    if env == 'prod':
        add(api, 'NATIVE-02', None, 'ALB Target 5xx ≥50%(5분·요청 ≥20·오류 ≥10) — 독립 백스톱. ELB 5xx 는 섞지 않는다',
            **math('IF(r >= 20, IF(FILL(t5, 0) >= 10, 100 * FILL(t5, 0) / r, 0), 0)',
                   [('r', 'AWS/ApplicationELB', 'RequestCount', {'LoadBalancer': lb, 'TargetGroup': tg}, 'Sum'),
                    ('t5', 'AWS/ApplicationELB', 'HTTPCode_Target_5XX_Count', {'LoadBalancer': lb, 'TargetGroup': tg}, 'Sum')],
                   300, 50, label='Target 5xx 비율(%)'))
    ecs = {'ClusterName': reg['cluster'], 'ServiceName': reg['service']}
    add(api, 'ECS-01', None, f'Desired < 승인 최소치({reg["min_expected_tasks"]}) 5분',
        **metric('RuleUp/Ops', 'DesiredCount', base, 'Minimum', 60, 5, 5, reg['min_expected_tasks'], LT, 'notBreaching'))
    add(api, 'ECS-02', None, 'Running < Desired 5분', **metric('RuleUp/Ops', 'TaskShortfall', base, 'Maximum', 60, 5, 5, 1, GE, 'notBreaching'))
    add(api, 'ECS-03', None, '예상 밖 태스크 종료 ≥3건/5분(기동 실패·크래시 루프)',
        **metric('RuleUp/Ops', 'UnexpectedStopCount', base, 'Sum', 300, 1, 1, 3, GE, 'notBreaching'))
    add(api, 'ECS-04', None, 'OOM 종료 ≥1건/5분(정상 여유가 있으면 P1)',
        **metric('RuleUp/Ops', 'OOMStopCount', base, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    add(api, 'ECS-05', '80', 'ECS CPU 평균 ≥80% 5분', **metric('AWS/ECS', 'CPUUtilization', ecs, 'Average', 60, 5, 5, 80, GE, 'notBreaching'))
    add(api, 'ECS-05', '90', 'ECS CPU 평균 ≥90% 2분', **metric('AWS/ECS', 'CPUUtilization', ecs, 'Average', 60, 2, 2, 90, GE, 'notBreaching'))
    add(api, 'ECS-06', '80', '가장 찬 태스크 하드 메모리 ≥80% 5분', **metric('RuleUp/Ops', 'MaxMemoryHardPct', base, 'Maximum', 60, 5, 5, 80, GE, 'notBreaching'))
    add(api, 'ECS-06', '90', '가장 찬 태스크 하드 메모리 ≥90% 2분', **metric('RuleUp/Ops', 'MaxMemoryHardPct', base, 'Maximum', 60, 2, 2, 90, GE, 'notBreaching'))
    add(api, 'ECS-07', None, '배포 실패 ≥1건/5분 — 자동 롤백 결과 확인', **metric('RuleUp/Ops', 'DeployFailureCount', base, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    if reg['min_expected_tasks'] >= 2:
        add(api, 'ECS-08', None, f'정상 타깃 ≤1대 5분(목표 {reg["min_expected_tasks"]}대)',
            **metric('AWS/ApplicationELB', 'HealthyHostCount', {'LoadBalancer': lb, 'TargetGroup': tg}, 'Maximum', 60, 5, 5, 1, LE, 'notBreaching'))
    add(api, 'ECS-09', None, 'ECS CPU 평균 ≥99% 1분 — 긴급 P1(CPU 하나로는 P0 아님)',
        **metric('AWS/ECS', 'CPUUtilization', ecs, 'Average', 60, 1, 1, 99, GE, 'notBreaching'))
    add(api, 'JVM-01', None, 'GC 직후 Old 영역 ≥80% 10분', **metric('RuleUp/Ops', 'OldGenAfterGcPct', base, 'Maximum', 60, 10, 10, 80, GE, 'notBreaching'))
    add(api, 'JVM-02', None, 'GC 정지 비율 ≥10%(최근 5분)', **metric('RuleUp/Ops', 'GcPauseRatio5m', base, 'Maximum', 60, 1, 1, 10, GE, 'notBreaching'))
    pool = {**base, 'Pool': 'ruleup-main'}
    add(api, 'POOL-01', None, 'Hikari active/max ≥80% 5분', **metric('RuleUp/Ops', 'MaxPoolActivePct', pool, 'Maximum', 60, 5, 5, 80, GE, 'notBreaching'))
    add(api, 'POOL-02', None, '커넥션 대기 스레드 ≥1(5분 중 3분)', **metric('RuleUp/Ops', 'MaxPoolPending', pool, 'Maximum', 60, 5, 3, 1, GE, 'notBreaching'))
    add(api, 'POOL-03', None, '커넥션 획득 timeout ≥1건/5분', **metric('RuleUp/Ops', 'PoolTimeoutDelta', pool, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    add(api, 'THREAD-01', None, 'Tomcat busy/max ≥80% 10분', **metric('RuleUp/Ops', 'MaxBusyThreadPct', base, 'Maximum', 60, 10, 10, 80, GE, 'notBreaching'))
    rds = {'DBInstanceIdentifier': reg['rds_instance']}
    db = {'Env': env, 'Database': reg['rds_instance']}
    add(api, 'DB-01', '80', 'RDS CPU ≥80% 10분', **metric('AWS/RDS', 'CPUUtilization', rds, 'Average', 60, 10, 10, 80, GE, 'notBreaching'))
    add(api, 'DB-01', '90', 'RDS CPU ≥90% 3분', **metric('AWS/RDS', 'CPUUtilization', rds, 'Average', 60, 3, 3, 90, GE, 'notBreaching'))
    add(api, 'DB-02', None, 'Freeable ≤RAM 10% + Swap 증가 10분', **metric('RuleUp/DB', 'DbMemoryPressure', db, 'Maximum', 60, 10, 10, 1, GE, 'notBreaching'))
    primary = {'Env': env, 'Database': 'primary'}
    add(api, 'DB-03', '75', 'Threads_connected/max_connections ≥75% 10분', **metric('RuleUp/DB', 'DbConnectionPct', primary, 'Maximum', 60, 10, 10, 75, GE, 'notBreaching'))
    add(api, 'DB-03', '95', 'Threads_connected/max_connections ≥95% 3분', **metric('RuleUp/DB', 'DbConnectionPct', primary, 'Maximum', 60, 3, 3, 95, GE, 'notBreaching'))
    add(api, 'DB-04', 'read', 'RDS 읽기 지연 ≥100ms 10분', **metric('AWS/RDS', 'ReadLatency', rds, 'Average', 60, 10, 10, 0.1, GE, 'notBreaching'))
    add(api, 'DB-04', 'write', 'RDS 쓰기 지연 ≥100ms 10분', **metric('AWS/RDS', 'WriteLatency', rds, 'Average', 60, 10, 10, 0.1, GE, 'notBreaching'))
    add(api, 'DB-05', None, 'RDS 여유 저장공간 <15% 15분', **metric('RuleUp/DB', 'FreeStoragePct', db, 'Minimum', 60, 15, 15, 15, LT, 'notBreaching'))
    add(api, 'DB-06', None, '최장 락 대기 ≥5초 3분', **metric('RuleUp/DB', 'OldestLockWaitSec', primary, 'Maximum', 60, 3, 3, 5, GE, 'notBreaching'))
    add(api, 'DB-07', None, '데드락 ≥3건/5분', **metric('RuleUp/DB', 'DeadlockDelta', primary, 'Sum', 300, 1, 1, 3, GE, 'notBreaching'))
    add(api, 'DB-08', None, '최장 트랜잭션 ≥60초 5분', **metric('RuleUp/DB', 'OldestTransactionSec', primary, 'Maximum', 60, 5, 5, 60, GE, 'notBreaching'))
    for node in reg['redis_nodes']:
        n = {'CacheClusterId': node}
        add(api, 'REDIS-01', f'{node}-80', 'Redis 엔진 CPU ≥80% 10분', **metric('AWS/ElastiCache', 'EngineCPUUtilization', n, 'Average', 60, 10, 10, 80, GE, 'notBreaching'))
        add(api, 'REDIS-01', f'{node}-90', 'Redis 엔진 CPU ≥90% 3분', **metric('AWS/ElastiCache', 'EngineCPUUtilization', n, 'Average', 60, 3, 3, 90, GE, 'notBreaching'))
        add(api, 'REDIS-02', node, 'Redis used_memory/maxmemory ≥85% 10분 — eviction 정책 확인',
            **metric('AWS/ElastiCache', 'DatabaseMemoryUsagePercentage', n, 'Maximum', 60, 10, 10, 85, GE, 'notBreaching'))
    for q in reg['queues']:
        add(api, 'QUEUE-01', q.removeprefix(f'{p}-'), f'{q} 가장 오래된 메시지 >10분 5분 — 소비자 정지·오류',
            **metric('AWS/SQS', 'ApproximateAgeOfOldestMessage', {'QueueName': q}, 'Maximum', 60, 5, 5, 600, GT, 'notBreaching'))
    for q in reg['dlqs']:
        add(api, 'QUEUE-02', q.removeprefix(f'{p}-'), f'{q} 에 재시도 끝에 실패한 메시지 ≥1',
            **metric('AWS/SQS', 'ApproximateNumberOfMessagesVisible', {'QueueName': q}, 'Maximum', 60, 1, 1, 1, GE, 'notBreaching'))
    app_ns = f'RuleUp/App/{env}'
    add(api, 'JOB-01', 'outbox', 'Outbox 가장 오래된 대기 >10분',
        **metric(app_ns, 'outbox.pending.oldest_age_seconds.value', {}, 'Maximum', 60, 1, 1, 600, GT, 'notBreaching'))
    add(api, 'JOB-01', 'outbox-dead-lettered', 'Outbox 작업이 재시도 끝에 포기됨(포기 누적 증가)',
        **math('DIFF(m)', [('m', app_ns, 'outbox.dead_lettered.count.value', {}, 'Maximum')], 300, 0, GT, label='포기 증가'))
    add(api, 'JOB-02', 'finalize-overdue', '확정 시각이 1시간 넘게 지난 미확정 판정이 15분째 — 확정 배치 정지·반복 실패',
        **metric(app_ns, 'verification.finalize.overdue.value', {}, 'Maximum', 300, 3, 3, 0, GT, 'notBreaching'))
    add(api, 'JOB-02', 'finalize-failed', '판정 확정이 건별로 실패해 격리됨(0건이어야 함)',
        **metric(app_ns, 'verification.finalize.failed.count', {}, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    add(api, 'JOB-02', 'materialize-failed', '무신호 귀속일 채우기 실패 — 그 멤버·날짜의 판정 행이 열리지 않음',
        **metric(app_ns, 'verification.materialize.failed.count', {}, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    for feature, provider in LLM_PROVIDERS + (('push', 'fcm'),):
        d = {**base, 'Feature': feature, 'Provider': provider}
        add(api, 'EXT-01', f'{feature}-{provider}', f'{provider}({feature}) 기술 실패 ≥10%(10분·호출 ≥20, 429·쿼터 포함)',
            **math('IF(FILL(c, 0) >= 20, 100 * FILL(f, 0) / c, 0)',
                   [('c', 'RuleUp/Ops', 'ProviderCallCount', d, 'Sum'), ('f', 'RuleUp/Ops', 'ProviderFailureCount', d, 'Sum')],
                   600, 10, label='실패율(%)'))
    for feature in ('generation', 'moderation'):
        d = {**base, 'Feature': feature}
        add(api, 'EXT-02', feature, f'LLM({feature}) p95 >15초(10분·표본 ≥20)',
            **math('IF(FILL(n, 0) >= 20, p95, 0)',
                   [('p95', 'RuleUp/Ops', 'LlmLatencyMs', d, 'p95'), ('n', 'RuleUp/Ops', 'LlmLatencyMs', d, 'SampleCount')],
                   600, 15000, GT, label='p95(ms)'))
    add(api, 'TOKEN-01', None, '무효 FCM 토큰 ≥20%(10분·발송 ≥20) — 집단 무효화(프로젝트 이전·SENDER_ID_MISMATCH)',
        **math('IF(FILL(a, 0) >= 20, 100 * FILL(i, 0) / a, 0)',
               [('a', 'RuleUp/Ops', 'ProviderCallCount', {**base, 'Feature': 'push', 'Provider': 'fcm'}, 'Sum'),
                ('i', 'RuleUp/Ops', 'InvalidTokenCount', {**base, 'Provider': 'fcm'}, 'Sum')], 600, 20, label='무효 비율(%)'))
    for service in ('ruleup-admin', 'ruleup-api'):
        add(api, 'SEC-01', service.removeprefix('ruleup-'), '관리자 로그인 실패 ≥5회/10분 — 침해 확정이 아니다(RB-05 로 조사)',
            **metric('RuleUp/Security', 'AdminLoginFailedCount', {'Env': env, 'Service': service}, 'Sum', 600, 1, 1, 5, GE, 'notBreaching'))
    add(api, 'SEC-05', None, 'refresh 토큰 재사용 탐지 ≥1건/5분 — 회전 충돌·탈취를 가린다',
        **metric('RuleUp/Security', 'RefreshReuseDetectedCount', base, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    add(api, 'INV-01', 'score-ledger', '점수 원장 재구성 불일치 ≥1건 — 증거를 고치지 말고 RB-05',
        **metric('RuleUp/Ops', 'InvariantViolationCount', {**base, 'Invariant': 'SCORE_LEDGER'}, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    add(api, 'BACKUP-01', None, 'RDS 자동 백업 꺼짐(보존 0일)', **metric('RuleUp/Backup', 'BackupEnabled', db, 'Minimum', 300, 1, 1, 1, LT, 'notBreaching'))
    add(api, 'BACKUP-02', None, '복구 가능 시점 지연 >15분(5분×3)', **metric('RuleUp/Backup', 'LatestRestorableLagSec', db, 'Maximum', 300, 3, 3, 900, GT, 'notBreaching'))
    add(api, 'BACKUP-03', None, '마지막 자동 스냅샷 >26시간', **metric('RuleUp/Backup', 'AutomatedSnapshotAgeSec', db, 'Maximum', 3600, 1, 1, 93600, GT, 'notBreaching'))
    add(api, 'BACKUP-04', None, '백업 실패 이벤트 ≥1건', **metric('RuleUp/Backup', 'BackupFailureCount', db, 'Sum', 300, 1, 1, 1, GE, 'notBreaching'))
    add(api, 'OBS-01', 'db-collector', 'DB 진단 수집 3분 누락(권한 부족 포함) — 관측 누락',
        **metric('RuleUp/Observe', 'CollectorHeartbeat', {'Env': env, 'Component': 'db-collector'}, 'Minimum', 60, 3, 3, 1, LT, 'breaching'))
    return out
