"""야간 보류 큐의 QUEUE-01 경보를 밤에 끄고 아침에 되돌린다 — EventBridge Scheduler. 재실행 안전.

    python3 infra/monitoring/alerting/night_hold.py prod
    python3 infra/monitoring/alerting/night_hold.py stg --dry-run     # 만들 역할·일정만 출력

왜: 알림 컨슈머는 21:00~08:00 KST 에 큐를 읽지 않는다(NotificationConsumer·NotificationWindow). 21시 이후 들어온 메시지는
08시까지 남는 게 설계라 QUEUE-01(>10분)이 매일 밤 울리고 08:02 쯤 스스로 풀렸다(2026-10-20~).

일정(Asia/Seoul, 시각은 catalog.NIGHT_HOLD_*)
  21:00 DisableAlarmActions
  08:10 EnableAlarmActions
  08:11 SetAlarmState OK — 밤새 ALARM 이던 경보는 켜기만 해서는 상태가 안 바뀌어 알림이 가지 않는다. OK 로 되돌려 두면
        컨슈머가 정말 멈췄을 때 다음 평가(1분)에서 ALARM 으로 바뀌며 알림이 간다(그때는 OK·ALARM 이 연달아 온다).
        평소처럼 08:02 에 이미 OK 면 상태 변화가 없어 아무것도 안 온다.
적용 시점이 밤이면 그 자리에서 동작을 끈다. p0_p1.py 도 밤에 돌리면 이 경보를 꺼 둔 채로 둔다.
"""
import argparse
import json
import os
import sys
import time
from datetime import datetime
from zoneinfo import ZoneInfo

import boto3

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog  # noqa: E402
from p0_p1 import load  # noqa: E402

KST = ZoneInfo('Asia/Seoul')


def role_name(env):
    return f'ruleup-{env}-alarm-night-hold'


def schedules(reg, role_arn):
    """[(이름, cron, 대상 API, 입력)]"""
    p = f"ruleup-{reg['env']}"
    names = catalog.night_held(reg)
    cron = lambda hm: f'cron({hm[1]} {hm[0]} * * ? *)'
    out = [(f'{p}-night-hold-off', cron(catalog.NIGHT_HOLD_OFF), 'disableAlarmActions', {'AlarmNames': names}),
           (f'{p}-night-hold-on', cron(catalog.NIGHT_HOLD_ON), 'enableAlarmActions', {'AlarmNames': names})]
    for n in names:
        out.append((f"{p}-night-hold-reset-{n.removeprefix(f'{p}-P1-')}", cron(catalog.NIGHT_HOLD_RESET), 'setAlarmState',
                    {'AlarmName': n, 'StateValue': 'OK',
                     'StateReason': 'night hold ended - re-arm so a stalled consumer alarms again on next evaluation'}))
    return out


def ensure_role(iam, reg, alarm_arns):
    name = role_name(reg['env'])
    trust = {'Version': '2012-10-17', 'Statement': [{
        'Effect': 'Allow', 'Principal': {'Service': 'scheduler.amazonaws.com'}, 'Action': 'sts:AssumeRole',
        'Condition': {'StringEquals': {'aws:SourceAccount': reg['account_id']}}}]}
    policy = {'Version': '2012-10-17', 'Statement': [{
        'Effect': 'Allow', 'Action': ['cloudwatch:DisableAlarmActions', 'cloudwatch:EnableAlarmActions', 'cloudwatch:SetAlarmState'],
        'Resource': alarm_arns}]}
    try:
        arn = iam.get_role(RoleName=name)['Role']['Arn']
        iam.update_assume_role_policy(RoleName=name, PolicyDocument=json.dumps(trust))
    except iam.exceptions.NoSuchEntityException:
        # IAM Description 은 Latin-1 만 받는다.
        arn = iam.create_role(RoleName=name, AssumeRolePolicyDocument=json.dumps(trust),
                              Description='EventBridge Scheduler: mute night-hold queue alarms 21:00-08:10 KST')['Role']['Arn']
    iam.put_role_policy(RoleName=name, PolicyName='alarm-actions', PolicyDocument=json.dumps(policy))
    return arn


def put_schedule(sch, name, cron, api, payload, role_arn):
    params = dict(Name=name, GroupName='default', ScheduleExpression=cron, ScheduleExpressionTimezone='Asia/Seoul',
                  FlexibleTimeWindow={'Mode': 'OFF'}, State='ENABLED',
                  Description=f'night hold: cloudwatch {api} (see infra/monitoring/alerting/night_hold.py)',
                  Target={'Arn': f'arn:aws:scheduler:::aws-sdk:cloudwatch:{api}', 'RoleArn': role_arn,
                          'Input': json.dumps(payload), 'RetryPolicy': {'MaximumRetryAttempts': 5, 'MaximumEventAgeInSeconds': 600}})
    try:
        sch.get_schedule(Name=name, GroupName='default')
        call = sch.update_schedule
    except sch.exceptions.ResourceNotFoundException:
        call = sch.create_schedule
    # 갓 만든 역할은 몇 초 동안 Scheduler 가 못 볼 수 있다.
    for attempt in range(8):
        try:
            return call(**params)
        except sch.exceptions.ValidationException:
            if attempt == 7:
                raise
            time.sleep(5)


def apply(reg, dry_run=False):
    region = reg['api_region']
    names = catalog.night_held(reg)
    if not names:
        print('야간 보류 큐가 없다 — 할 일 없음')
        return
    cw = boto3.client('cloudwatch', region_name=region)
    found = {a['AlarmName']: a['AlarmArn'] for a in cw.describe_alarms(AlarmNames=names)['MetricAlarms']}
    missing = [n for n in names if n not in found]
    if missing:
        sys.exit(f'경보가 없다 — p0_p1.py {reg["env"]} 를 먼저 돌린다: {missing}')

    role_arn = f"arn:aws:iam::{reg['account_id']}:role/{role_name(reg['env'])}"
    plan = schedules(reg, role_arn)
    if dry_run:
        print('role', role_arn)
        for name, cron, api, payload in plan:
            print(f'{name:55} {cron:22} {api:20} {json.dumps(payload, ensure_ascii=False)}')
        return

    role_arn = ensure_role(boto3.client('iam'), reg, list(found.values()))
    sch = boto3.client('scheduler', region_name=region)
    for name, cron, api, payload in plan:
        put_schedule(sch, name, cron, api, payload, role_arn)
        print('ok', name, cron, api)

    if catalog.night_hold_quiet(datetime.now(KST).time()):
        cw.disable_alarm_actions(AlarmNames=names)
        print('지금은 야간 — 경보 동작을 바로 껐다', names)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('env', choices=['stg', 'prod'])
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    apply(load(a.env), a.dry_run)
