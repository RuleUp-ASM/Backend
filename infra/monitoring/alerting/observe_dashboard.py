"""감시망 대시보드 ruleup-<env>-observe(중앙 감시 리전) — 노션 「운영 경보 상세」 §4.2: OBS-1 native/공개 점검 · OBS-2 Lambda/수집/계산
최신성 · OBS-3 SNS/전달 · OBS-4 백업. 재실행 안전. 런북 RB-06 이 패널 번호로 가리킨다.
사용: python observe_dashboard.py [prod|stg]"""
import json
import os
import sys

import boto3

HERE = os.path.dirname(os.path.abspath(__file__))
ENV = sys.argv[1] if len(sys.argv) > 1 else 'prod'
with open(os.path.join(HERE, '..', 'env', f'{ENV}.json'), encoding='utf-8') as f:
    REG = json.load(f)
MON, API = REG['monitor_region'], REG['api_region']
P = f'ruleup-{ENV}'
ORIGINS = list(REG['probe_origins'])
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
        p = {'title': title, 'region': MON, 'view': 'timeSeries', 'stacked': False, 'period': 60, 'metrics': metrics}
        p.update(extra or {})
        widgets.append({'type': 'metric', 'x': x, 'y': y, 'width': w, 'height': h, 'properties': p})
        x += w
    y += h


hc = REG.get('route53_health_check_id')
text('## OBS-1 · native 백스톱·공개 점검')
row(('공개 점검 실패(1=실패)', [['RuleUp/Edge', 'ProbeFailure', 'Env', ENV, 'Probe', 'public', 'Origin', o, {'stat': 'Maximum', 'label': o}] for o in ORIGINS], None),
    ('Route 53 헬스체크(1=정상)' + ('' if hc else ' — 원장 route53_health_check_id 미기록'),
     [['AWS/Route53', 'HealthCheckStatus', 'HealthCheckId', hc, {'stat': 'Minimum'}]] if hc else [], None),
    ('인증서 잔여 일수', [['RuleUp/Edge', 'CertificateDaysLeft', 'Env', ENV, 'Origin', o, {'stat': 'Minimum', 'label': o}] for o in ORIGINS],
     {'period': 3600, 'annotations': {'horizontal': [{'value': 14, 'label': 'TLS-01'}]}}))

text('## OBS-2 · 수집·계산 최신성 — 하트비트는 「실제 완료」만 1 이다(실행 ping 아님)')
comps = ['slo-calculator', 'resource-monitor'] + [f'edge-{o}' for o in ORIGINS] + (['anomaly-detector'] if REG['rules'].get('detectors_enabled') else [])
fns = [f'{P}-slo-calculator', f'{P}-resource-monitor', f'{P}-edge-probe']
row(('컴포넌트 하트비트', [['RuleUp/Observe', 'CollectorHeartbeat', 'Env', ENV, 'Component', c, {'stat': 'Minimum', 'label': c}] for c in comps]
     + [['RuleUp/Observe', 'CollectorHeartbeat', 'Env', ENV, 'Component', 'db-collector', {'stat': 'Minimum', 'label': 'db-collector', 'region': API}]], None),
    ('Lambda 오류·스로틀', [['AWS/Lambda', m, 'FunctionName', f, {'stat': 'Sum', 'label': f'{f.removeprefix(P + "-")} {m}'}]
                         for f in fns for m in ('Errors', 'Throttles')], None),
    ('Lambda 소요(ms)', [['AWS/Lambda', 'Duration', 'FunctionName', f, {'stat': 'Maximum', 'label': f.removeprefix(P + '-')}] for f in fns], None),
    ('Scheduler 실패 DLQ', [['AWS/SQS', 'ApproximateNumberOfMessagesVisible', 'QueueName', f'{P}-monitor-dlq', {'stat': 'Maximum'}]], None))

text('## OBS-3 · 알림 전달 — SNS 실패·EventBridge 실패 (PagerDuty·watchdog 은 개통 전)')
topics = [REG['sns']['alerts']] + [t for t in (REG['sns'].get('p0'), REG['sns'].get('pagerduty')) if t] + (['ruleup-prod-p0-slack'] if ENV == 'prod' else [])
row(('SNS 전달 실패 — 서울', [['AWS/SNS', 'NumberOfNotificationsFailed', 'TopicName', t, {'stat': 'Sum', 'label': t, 'region': API}] for t in topics], {'period': 300}),
    ('SNS 전달 실패 — 중앙', [['AWS/SNS', 'NumberOfNotificationsFailed', 'TopicName', t, {'stat': 'Sum', 'label': t}] for t in topics], {'period': 300}),
    ('EventBridge P0 멘션 규칙 실패', [['AWS/Events', 'FailedInvocations', 'RuleName', r, {'stat': 'Sum', 'label': f'{r} ({reg})', 'region': reg}]
                                   for r in ('ruleup-prod-p0-alarm', 'ruleup-prod-p0-recovered') for reg in (API, MON)] if ENV == 'prod' else [], {'period': 300}))

text('## OBS-4 · 백업·복구 가능 시점')
db = ['Database', REG['rds_instance'], 'Env', ENV]
row(('복구 가능 시점 지연(초)', [['RuleUp/Backup', 'LatestRestorableLagSec', *db, {'stat': 'Maximum', 'region': API}]],
     {'period': 300, 'annotations': {'horizontal': [{'value': 900, 'label': 'BACKUP-02'}]}}),
    ('마지막 자동 스냅샷 경과(초)', [['RuleUp/Backup', 'AutomatedSnapshotAgeSec', *db, {'stat': 'Maximum', 'region': API}]],
     {'period': 3600, 'annotations': {'horizontal': [{'value': 93600, 'label': 'BACKUP-03'}]}}),
    ('백업 켜짐·실패', [['RuleUp/Backup', 'BackupEnabled', *db, {'stat': 'Minimum', 'region': API}],
                      ['RuleUp/Backup', 'BackupFailureCount', *db, {'stat': 'Sum', 'region': API, 'yAxis': 'right'}]], {'period': 300}))
boto3.client('cloudwatch', region_name=MON).put_dashboard(DashboardName=REG['dashboards']['observe'],
                                                          DashboardBody=json.dumps({'widgets': widgets}, ensure_ascii=False))
print('ok', REG['dashboards']['observe'], len(widgets))
