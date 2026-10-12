"""handler 를 끝까지 한 번 돌려 보는 가짜 boto3 — 호출 이름·인자 모양이 틀리면 여기서 깨진다."""
import sys
from datetime import datetime, timedelta, timezone

PUT = []


class _Client:
    def __init__(self, service, region_name=None):
        self.service, self.region = service, region_name

    # cloudwatch
    def get_metric_data(self, MetricDataQueries, StartTime, EndTime, ScanBy=None, NextToken=None):
        assert len(MetricDataQueries) <= 500
        results = []
        for q in MetricDataQueries:
            period = q.get('MetricStat', {}).get('Period', 60)
            start = int(StartTime.timestamp()) // period * period
            ts = [datetime.fromtimestamp(t, timezone.utc) for t in range(start, int(EndTime.timestamp()), period)]
            results.append({'Id': q['Id'], 'Label': q['Id'], 'Timestamps': ts, 'Values': [1.0] * len(ts)})
        return {'MetricDataResults': results}

    def put_metric_data(self, Namespace, MetricData):
        assert len(MetricData) <= 1000
        for d in MetricData:
            assert set(d) >= {'MetricName', 'Dimensions', 'Value', 'Timestamp'}
        PUT.append((self.region, Namespace, MetricData))

    # ecs
    def describe_services(self, cluster, services):
        return {'services': [{'desiredCount': 2, 'runningCount': 2, 'deployments': []}]}

    def list_tasks(self, **kw):
        return {'taskArns': ['arn:t1']}

    def describe_tasks(self, cluster, tasks):
        return {'tasks': [{'taskArn': 'arn:t1', 'stoppedAt': datetime.now(timezone.utc) - timedelta(seconds=90),
                           'stoppedReason': 'Essential container in task exited', 'containers': [{'reason': 'OutOfMemoryError'}]}]}

    # elbv2
    def describe_target_groups(self, Names):
        return {'TargetGroups': [{'TargetGroupArn': 'arn:tg'}]}

    def describe_target_health(self, TargetGroupArn):
        return {'TargetHealthDescriptions': [{'TargetHealth': {'State': 'healthy'}}] * 2}

    # application-autoscaling
    def describe_scalable_targets(self, ServiceNamespace, ResourceIds):
        return {'ScalableTargets': [{'MaxCapacity': 6}]}

    def describe_scaling_activities(self, **kw):
        return {'ScalingActivities': []}

    # rds
    def describe_db_instances(self, DBInstanceIdentifier):
        return {'DBInstances': [{'DBInstanceIdentifier': DBInstanceIdentifier, 'AllocatedStorage': 100, 'MaxAllocatedStorage': 200,
                                 'DBInstanceClass': 'db.m7g.large', 'BackupRetentionPeriod': 7,
                                 'LatestRestorableTime': datetime.now(timezone.utc) - timedelta(minutes=5)}]}

    def describe_db_snapshots(self, **kw):
        return {'DBSnapshots': [{'Status': 'available', 'SnapshotCreateTime': datetime.now(timezone.utc) - timedelta(hours=3)}]}

    def describe_events(self, **kw):
        return {'Events': []}


def install():
    mod = type(sys)('boto3')
    mod.client = lambda service, region_name=None: _Client(service, region_name)
    sys.modules['boto3'] = mod
    PUT.clear()
    return PUT
