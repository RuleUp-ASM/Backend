"""자원 감시기 — 노션 「알람 카탈로그」 §0·§4·§4.2·§5·§7. 버지니아 Scheduler(1분) → 이 함수(서울 API 를 읽기만 한다).

매분
  · ECS DescribeServices → RuleUp/Ops DesiredCount·RunningCount·TaskShortfall(0 포함, ECS-01/02)
  · 멈춘 태스크 → UnexpectedStopCount·OOMStopCount(ECS-03/04), 실패한 배포 → DeployFailureCount(ECS-07)
  · 오토스케일 상한·최근 실패 → ScaleOutBlocked
  · RES-01~04 판정 → 버지니아 RuleUp/Ops CriticalCapacityFlag(Env,Service,Rule). 입력이 불완전하면 그 Rule 은 내지 않는다
  · RDS → RuleUp/DB FreeStoragePct(DB-05)·StorageMinutesToFull(DB-09)·DbMemoryPressure(DB-02)
5분마다
  · RDS 백업 → RuleUp/Backup BackupEnabled·LatestRestorableLagSec·AutomatedSnapshotAgeSec·BackupFailureCount(BACKUP-01~04)
모든 원천을 읽은 실행만 RuleUp/Observe CollectorHeartbeat(Component=resource-monitor) 를 낸다(OBS-01).

원장 rules.res_enabled 가 꺼져 있어도 Flag 는 계산해 내보낸다(그림자 평가). 경보는 p0_p1.py 가 res_enabled 일 때만 만든다.
"""
from datetime import timedelta, timezone, datetime

import boto3

import cwio
import resource_core as rc
import slo_core


def handler(event, context):
    reg = cwio.registry()
    env, svc = reg['env'], reg['metric_service']
    now = cwio.event_time(event)
    stamp = now.replace(second=0, microsecond=0)
    prev = stamp - timedelta(minutes=1)
    api = reg['api_region']
    ecs = boto3.client('ecs', region_name=api)
    cw_api = boto3.client('cloudwatch', region_name=api)
    cw_mon = boto3.client('cloudwatch', region_name=reg['monitor_region'])
    dims = {'Env': env, 'Service': svc}
    complete = True
    ops_out, db_out = [], []

    # ---- ECS 서비스·종료·배포
    healthy = desired = None
    oom_5m = None
    try:
        s = ecs.describe_services(cluster=reg['cluster'], services=[reg['service']])['services'][0]
        desired, running = s['desiredCount'], s['runningCount']
        ops_out += [('DesiredCount', dims, desired, stamp, 'Count'), ('RunningCount', dims, running, stamp, 'Count'),
                    ('TaskShortfall', dims, max(desired - running, 0), stamp, 'Count')]
        tasks = stopped_tasks(ecs, reg)
        oom_1m, unexpected_1m = rc.count_stops(tasks, prev.timestamp(), stamp.timestamp())
        oom_5m, _ = rc.count_stops(tasks, (stamp - timedelta(minutes=5)).timestamp(), stamp.timestamp())
        failed = rc.deploy_failures(s.get('deployments', []), prev.timestamp(), stamp.timestamp())
        ops_out += [('UnexpectedStopCount', dims, unexpected_1m, prev, 'Count'), ('OOMStopCount', dims, oom_1m, prev, 'Count'),
                    ('DeployFailureCount', dims, failed, prev, 'Count')]
    except Exception as e:  # noqa: BLE001
        complete = False
        cwio.log('collect_failed', part='ecs', error=str(e))
    try:
        healthy = healthy_targets(boto3.client('elbv2', region_name=api), reg['target_group'])
    except Exception as e:  # noqa: BLE001
        complete = False
        cwio.log('collect_failed', part='alb', error=str(e))

    # ---- 오토스케일
    blocked_now = None
    try:
        aas = boto3.client('application-autoscaling', region_name=api)
        rid = f"service/{reg['cluster']}/{reg['service']}"
        targets = aas.describe_scalable_targets(ServiceNamespace='ecs', ResourceIds=[rid])['ScalableTargets']
        acts = aas.describe_scaling_activities(ServiceNamespace='ecs', ResourceId=rid, MaxResults=10)['ScalingActivities']
        recent_fail = any(a['StatusCode'] == 'Failed' and a['StartTime'] >= now - timedelta(minutes=5) for a in acts)
        if desired is not None:
            blocked_now = rc.scale_out_blocked(desired, targets[0]['MaxCapacity'] if targets else None, recent_fail)
            ops_out.append(('ScaleOutBlocked', dims, blocked_now, stamp, 'None'))
    except Exception as e:  # noqa: BLE001
        complete = False
        cwio.log('collect_failed', part='autoscaling', error=str(e))

    # ---- 지표(서울): 메모리·CPU·스레드·CUJ 지연·RDS
    last = cwio.minute_index(now - timedelta(seconds=120))
    rds_id = reg['rds_instance']
    q = [cwio.stat('memmin', 'RuleUp/Ops', 'MaxMemoryHardPct', dims, 'Minimum'),
         cwio.stat('memn', 'RuleUp/Ops', 'MaxMemoryHardPct', dims, 'SampleCount'),
         cwio.stat('blk', 'RuleUp/Ops', 'ScaleOutBlocked', dims, 'Maximum'),
         cwio.stat('cpu', 'AWS/ECS', 'CPUUtilization', {'ClusterName': reg['cluster'], 'ServiceName': reg['service']}, 'Average'),
         cwio.stat('busy', 'RuleUp/Ops', 'MaxBusyThreadPct', dims, 'Maximum'),
         cwio.stat('free', 'AWS/RDS', 'FreeStorageSpace', {'DBInstanceIdentifier': rds_id}, 'Minimum'),
         cwio.stat('fmem', 'AWS/RDS', 'FreeableMemory', {'DBInstanceIdentifier': rds_id}, 'Minimum'),
         cwio.stat('swap', 'AWS/RDS', 'SwapUsage', {'DBInstanceIdentifier': rds_id}, 'Maximum')]
    for i, op in enumerate(slo_core.OPERATIONS):
        q += [cwio.stat(f'p{i}', 'RuleUp/SLI', 'DurationMs', {**dims, 'Operation': op}, 'p95'),
              cwio.stat(f'n{i}', 'RuleUp/SLI', 'EligibleCount', {**dims, 'Operation': op}, 'Sum')]
    m = {}
    try:
        # 끝은 지금 분 직전까지 — 직전 실행들이 낸 ScaleOutBlocked(분 timestamp = 실행 분)까지 읽는다.
        m = cwio.get_metric_data(cw_api, q, datetime.fromtimestamp((last - 16) * 60, timezone.utc), stamp)
    except Exception as e:  # noqa: BLE001
        complete = False
        cwio.log('collect_failed', part='metrics', error=str(e))

    def recent(qid, n, start=last):
        s = m.get(qid, {})
        return [s.get(k) for k in range(start, start - n, -1)]

    blk_hist = [v for v in recent('blk', 3, cwio.minute_index(stamp) - 1)]
    slow = None
    if m:
        # 적격 요청 ≥20건(2분 합)인 CUJ 중 p95 가 자기 목표의 2배 이상인 분이 2/2 인 것이 있는가
        slow = any(None not in recent(f'p{i}', 2) and sum(n or 0 for n in recent(f'n{i}', 2)) >= 20
                   and min(recent(f'p{i}', 2)) >= 2 * target
                   for i, (_, target) in enumerate(slo_core.OPERATIONS.values()))
    capacity = rc.Capacity(healthy=healthy, mem_min=recent('memmin', 2), mem_samples=recent('memn', 2),
                           scale_blocked_history=([blocked_now] if blocked_now is not None else []) + [b for b in blk_hist if b is not None],
                           cpu_avg=recent('cpu', 3), busy_threads_max=recent('busy', 2), slow_cuj=slow, oom_5m=oom_5m)

    # ---- RDS
    storage = None
    try:
        inst = boto3.client('rds', region_name=api).describe_db_instances(DBInstanceIdentifier=rds_id)['DBInstances'][0]
        alloc = inst['AllocatedStorage'] * 1024 ** 3
        free = recent('free', 16)
        free_pct = [None if v is None else 100.0 * v / alloc for v in free]
        storage = rc.Storage(free_pct=free_pct, free_bytes=free, expandable=rc.expandable(inst))
        db = {'Env': env, 'Database': rds_id}
        if free_pct[0] is not None:
            db_out.append(('FreeStoragePct', db, free_pct[0], stamp, 'Percent'))
        eta = rc.minutes_to_full(free)
        if eta is not None:
            db_out.append(('StorageMinutesToFull', db, eta, stamp, 'None'))
        pressure = rc.db_memory_pressure(inst['DBInstanceClass'], recent('fmem', 10), recent('swap', 10))
        if pressure is not None:
            db_out.append(('DbMemoryPressure', db, pressure, stamp, 'None'))
        if now.minute % 5 == 0:
            backup(boto3.client('rds', region_name=api), cw_api, inst, env, now, stamp)
    except Exception as e:  # noqa: BLE001
        complete = False
        cwio.log('collect_failed', part='rds', error=str(e))

    flags = {'RES-01': rc.res01(capacity), 'RES-02': rc.res02(capacity), 'RES-03': rc.res03(capacity),
             'RES-04': rc.res04(storage) if storage else None}
    cwio.log('res_eval', env=env, healthy=healthy, flags=flags, capacity=capacity.__dict__,
             enabled=bool(reg['rules'].get('res_enabled')))
    cwio.put(cw_api, 'RuleUp/Ops', ops_out)
    cwio.put(cw_api, 'RuleUp/DB', db_out)
    cwio.put(cw_mon, 'RuleUp/Ops', [('CriticalCapacityFlag', {**dims, 'Rule': rid}, f, stamp, 'None')
                                     for rid, f in flags.items() if f is not None])
    if complete:
        cwio.put(cw_mon, 'RuleUp/Observe', [('CollectorHeartbeat', {'Env': env, 'Component': 'resource-monitor'}, 1, stamp, 'Count')])
    return {'flags': flags, 'complete': complete}


def stopped_tasks(ecs, reg):
    arns = ecs.list_tasks(cluster=reg['cluster'], serviceName=reg['service'], desiredStatus='STOPPED', maxResults=100)['taskArns']
    return ecs.describe_tasks(cluster=reg['cluster'], tasks=arns)['tasks'] if arns else []


def healthy_targets(elb, target_group):
    name = target_group.split('/')[1]
    arn = elb.describe_target_groups(Names=[name])['TargetGroups'][0]['TargetGroupArn']
    return sum(1 for t in elb.describe_target_health(TargetGroupArn=arn)['TargetHealthDescriptions']
               if t['TargetHealth']['State'] == 'healthy')


def backup(rds, cw, inst, env, now, stamp):
    """BACKUP-01~04. 값이 없거나 권한 오류면 0 으로 위장하지 않고 예외를 올려 하트비트를 뺀다."""
    db = {'Env': env, 'Database': inst['DBInstanceIdentifier']}
    out = [('BackupEnabled', db, 1 if inst.get('BackupRetentionPeriod', 0) > 0 else 0, stamp, 'None')]
    lrt = inst.get('LatestRestorableTime')
    if lrt:
        out.append(('LatestRestorableLagSec', db, (now - lrt).total_seconds(), stamp, 'Seconds'))
    snaps = rds.describe_db_snapshots(DBInstanceIdentifier=inst['DBInstanceIdentifier'], SnapshotType='automated')['DBSnapshots']
    done = [s['SnapshotCreateTime'] for s in snaps if s.get('Status') == 'available' and s.get('SnapshotCreateTime')]
    if done:
        out.append(('AutomatedSnapshotAgeSec', db, (now - max(done)).total_seconds(), stamp, 'Seconds'))
    events = rds.describe_events(SourceIdentifier=inst['DBInstanceIdentifier'], SourceType='db-instance',
                                 StartTime=now - timedelta(minutes=5), EndTime=now, EventCategories=['backup'])['Events']
    failures = sum(1 for e in events if 'fail' in e.get('Message', '').lower())
    out.append(('BackupFailureCount', db, failures, stamp, 'Count'))
    cwio.put(cw, 'RuleUp/Backup', out)
