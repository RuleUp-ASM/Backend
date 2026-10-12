"""저트래픽 예외 재무장 — 노션 「알람 카탈로그」 §2 「P0 상태 유지·재무장 계약」. 운영자가 직접 돌린다.

사용자 요청이 줄어 정상 표본이 없을 때, 격리 기능시험·복수 점검 같은 <b>독립적인 정상 증거</b>가 있을 때만
해당 Operation·Rule 의 AlarmFlag=0 을 1회 낸다(SLO-01/02·API-01 이 OK 로 돌아갈 근거). 이 0 은 SLO 요청·실패·버짓 통계에
들어가지 않는다(원천 RuleUp/SLI 는 건드리지 않는다). 감사 기록은 /ruleup/<env>/monitoring-audit 로그 그룹에 남긴다.

    python3 infra/monitoring/lambdas/rearm.py prod --operation sync --rule API-01 \\
        --incident INC-20261011-01 --approver 이성은 --evidence "stg 격리 sync 10건 성공·오리건/버지니아 점검 정상"

재무장 뒤 다음 2회 60초 평가에서도 OK 인지, PagerDuty 가 의도한 사건만 닫혔는지 확인한다(RB-01/07).
계산기가 죽어 있거나 증거가 없으면 쓰지 않는다. set-alarm-state 는 영구 재무장 수단이 아니다.
"""
import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone

import boto3

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import slo_core  # noqa: E402

P0_RULES = ('SLO-01', 'SLO-02', 'API-01')

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('env', choices=['stg', 'prod'])
    ap.add_argument('--operation', required=True, choices=list(slo_core.OPERATIONS))
    ap.add_argument('--rule', required=True, choices=P0_RULES)
    ap.add_argument('--incident', required=True)
    ap.add_argument('--approver', required=True)
    ap.add_argument('--evidence', required=True)
    a = ap.parse_args()
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'env', f'{a.env}.json'), encoding='utf-8') as f:
        reg = json.load(f)
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    record = {'kind': 'rearm', 'env': a.env, 'operation': a.operation, 'rule': a.rule, 'incident': a.incident,
              'approver': a.approver, 'evidence': a.evidence, 'published_at': now.isoformat(),
              'note': 'alarm re-arm only — not counted in SLO/budget'}
    logs = boto3.client('logs', region_name=reg['monitor_region'])
    group, stream = f"/ruleup/{a.env}/monitoring-audit", 'rearm'
    for create in (lambda: logs.create_log_group(logGroupName=group), lambda: logs.create_log_stream(logGroupName=group, logStreamName=stream)):
        try:
            create()
        except logs.exceptions.ResourceAlreadyExistsException:
            pass
    logs.put_log_events(logGroupName=group, logStreamName=stream,
                        logEvents=[{'timestamp': int(time.time() * 1000), 'message': json.dumps(record, ensure_ascii=False)}])
    boto3.client('cloudwatch', region_name=reg['monitor_region']).put_metric_data(Namespace='RuleUp/SLO', MetricData=[{
        'MetricName': 'AlarmFlag', 'Value': 0, 'Timestamp': now, 'StorageResolution': 60,
        'Dimensions': [{'Name': 'Env', 'Value': a.env}, {'Name': 'Service', 'Value': reg['metric_service']},
                       {'Name': 'Operation', 'Value': a.operation}, {'Name': 'Rule', 'Value': a.rule}]}])
    print(json.dumps(record, ensure_ascii=False, indent=2))
    print('다음 2회 평가가 OK 로 유지되는지, PagerDuty 사건이 맞게 닫혔는지 확인한다.')
