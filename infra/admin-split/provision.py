#!/usr/bin/env python3
"""관리자 API 분리 인프라 — 환경당 한 벌. 재실행 안전(있으면 건너뛰고 없으면 만든다).

    python3 infra/admin-split/provision.py stg                 # 계획만(조회 전용, 아무것도 바꾸지 않는다)
    python3 infra/admin-split/provision.py stg --apply         # 만든다
    python3 infra/admin-split/provision.py stg --apply --only network,iam
    python3 infra/admin-split/provision.py stg --apply --only alb-lockdown   # 공개 ALB 를 Cloudflare IP 로만 (따로 승인)

단계(--only 로 고른다, 순서대로 돈다)
  network       관리자 SG(인바운드 0) · NAT 인스턴스(t4g.nano) · app 서브넷 전용 라우트 테이블 · S3 게이트웨이 엔드포인트
                · RDS/Redis SG 에 관리자 SG 인입 추가
  iam           관리자 실행 역할·애플리케이션 역할(각각 최소 권한) · DB 작업용 실행 역할 · 배포 역할에 관리자 서비스 권한
  secrets       ruleup-<env>/admin(DB_PASSWORD·BAN_LIST_SALT·TUNNEL_TOKEN) · ruleup-<env>/db-users(APP·MIGRATOR 비밀번호)
                — 비밀번호는 Secrets Manager 가 만들고 이 스크립트는 값을 보지 않는다
  logs          /ecs/ruleup-<env>-admin(90일) · 접근 거부 급증 경보
  ecs           관리자 태스크 정의(앱 + cloudflared 사이드카) · 서비스(private, 공인 IP 없음, desired 0 으로 시작)
  alb-guard     공개 ALB 에 /api/v1/admin/* → 404 고정 응답 규칙(롤백 때 옛 이미지가 떠도 관리자 경로가 안 열리게)
  alb-lockdown  공개 ALB SG 인입을 Cloudflare IP 대역만으로(직접 ALB 주소 호출 차단). 기본 단계에 없다 — 따로 돌린다

비용(서울, 2026-10 기준 대략): NAT 인스턴스 t4g.nano 약 $3.8 + EBS 8GB $0.7 + 공인 IPv4 $3.7 → 월 약 $8.2.
관리자 Fargate 0.5vCPU/1GB ARM Spot 월 약 $5(상시 1개). 시크릿 2개 $0.8. Cloudflare Access·Tunnel 은 무료(50명 이하).
"""
import argparse
import base64
import json
import sys
import time
import urllib.request

import boto3
from botocore.exceptions import ClientError

R = "ap-northeast-2"
ACCOUNT = "961178969292"

ENV = {
    "stg": dict(
        vpc="vpc-0ef4c35f726c26f91",
        public_subnets=["subnet-0a2360734a3653376", "subnet-0595a56229c354659"],
        app_subnets=["subnet-0fcd4ba3b49d1c1f0", "subnet-0abeb77bcb1454295"],
        rds_sg="sg-02bee8529c33df348", redis_sg="sg-00aa79d94ddc975da",
        alb_sg="sg-08ef8e7cee478be3b", api_sg="sg-012ba8470fe3e280a",
        alb_listeners=["arn:aws:elasticloadbalancing:ap-northeast-2:961178969292:listener/app/ruleup-stg-alb/1a2d3584a7aaa2c1/c3d721a6473e516f"],
        db_host="ruleup-stg-mysql-v3.c34mcock6t1w.ap-northeast-2.rds.amazonaws.com",
        redis_host="ruleup-stg-redis.frgs7y.0001.apn2.cache.amazonaws.com",
        jdbc_params="serverTimezone=Asia/Seoul&sslMode=REQUIRED",
        media_bucket="ruleup-stg-media-961178969292",
        capacity=[{"capacityProvider": "FARGATE_SPOT", "weight": 1, "base": 0}],
        admin_origin="https://admin.ruleup.co.kr",
        tunnel_hostname="admin-api-stg.ruleup.co.kr",
    ),
    "prod": dict(
        vpc="vpc-0a7005d07813b0a5a",
        public_subnets=["subnet-04268e0a4c0243a41", "subnet-005f6477f60ccf761"],
        app_subnets=["subnet-0409244069c5d17c0", "subnet-07468be31ddf92006"],
        rds_sg="sg-06c394aa4581c42c4", redis_sg="sg-083c7b80379c3c5ea",
        alb_sg="sg-03f6935fe4c111e77", api_sg="sg-0ce610d9387070705",
        alb_listeners=["arn:aws:elasticloadbalancing:ap-northeast-2:961178969292:listener/app/ruleup-prod-alb/30526d51ec29a005/8a7aab667c2eaa74"],
        db_host="ruleup-prod-mysql-v2.c34mcock6t1w.ap-northeast-2.rds.amazonaws.com",
        redis_host="ruleup-prod-redis.frgs7y.ng.0001.apn2.cache.amazonaws.com",
        jdbc_params="serverTimezone=Asia/Seoul&sslMode=REQUIRED",
        media_bucket="ruleup-prod-media-961178969292",
        # 운영도 Spot — 관리자 화면은 회수 때 1~2분 끊겨도 사용자 영향이 없다. 상시성이 필요해지면 FARGATE base=1.
        capacity=[{"capacityProvider": "FARGATE_SPOT", "weight": 1, "base": 0}],
        admin_origin="https://admin.ruleup.co.kr",
        tunnel_hostname="admin-api.ruleup.co.kr",
    ),
}

# cloudflared 는 버전을 고정한다(태그 고정 = 공급망 변화가 배포 없이 들어오지 않게). 올릴 때 이 값만 바꾼다.
CLOUDFLARED_IMAGE = "cloudflare/cloudflared:2025.8.1"
NAT_AMI_PARAM = "/aws/service/ami-amazon-linux-latest/al2023-ami-minimal-kernel-default-arm64"

ec2 = boto3.client("ec2", region_name=R)
iam = boto3.client("iam")
sm = boto3.client("secretsmanager", region_name=R)
logs = boto3.client("logs", region_name=R)
cw = boto3.client("cloudwatch", region_name=R)
ecs = boto3.client("ecs", region_name=R)
elb = boto3.client("elbv2", region_name=R)
ssm = boto3.client("ssm", region_name=R)

APPLY = False
NAT_SUBNET_INDEX = 0   # AZ 장애 복구 때 --nat-az c 로 다른 public 서브넷에 다시 만든다


def act(desc, fn=None):
    """계획 모드면 할 일만 적고, 적용 모드면 실행한다."""
    if not APPLY:
        print(f"  [plan] {desc}")
        return None
    print(f"  [apply] {desc}")
    return fn() if fn else None


def ok(desc):
    print(f"  [ok]   {desc}")


def tags(name, env):
    return [{"Key": "Name", "Value": name}, {"Key": "env", "Value": env}, {"Key": "managed-by", "Value": "infra/admin-split"}]


def find_sg(vpc, name):
    r = ec2.describe_security_groups(Filters=[{"Name": "vpc-id", "Values": [vpc]}, {"Name": "group-name", "Values": [name]}])
    return r["SecurityGroups"][0]["GroupId"] if r["SecurityGroups"] else None


def ensure_sg(env, c, name, desc):
    sg = find_sg(c["vpc"], name)
    if sg:
        ok(f"SG {name} {sg}")
        return sg
    return act(f"SG {name} 생성", lambda: ec2.create_security_group(
        GroupName=name, Description=desc, VpcId=c["vpc"],
        TagSpecifications=[{"ResourceType": "security-group", "Tags": tags(name, env)}])["GroupId"])


def ensure_ingress_from_sg(target_sg, port, source_sg, label):
    if not source_sg:
        act(f"{label}: {target_sg} 에 {port} ← (생성 예정 SG)")
        return
    g = ec2.describe_security_groups(GroupIds=[target_sg])["SecurityGroups"][0]
    for p in g["IpPermissions"]:
        if p.get("FromPort") == port and any(x["GroupId"] == source_sg for x in p.get("UserIdGroupPairs", [])):
            ok(f"{label}: {target_sg}:{port} ← {source_sg}")
            return
    act(f"{label}: {target_sg}:{port} ← {source_sg} 추가", lambda: ec2.authorize_security_group_ingress(
        GroupId=target_sg, IpPermissions=[{"IpProtocol": "tcp", "FromPort": port, "ToPort": port,
                                            "UserIdGroupPairs": [{"GroupId": source_sg, "Description": label}]}]))


# ---------------------------------------------------------------- network
def network(env, c):
    print("== network")
    admin_sg = ensure_sg(env, c, f"ruleup-{env}-admin-sg",
                         "RuleUp admin API tasks - no inbound; egress to RDS/Redis/HTTPS/Cloudflare tunnel")
    nat_sg = ensure_sg(env, c, f"ruleup-{env}-nat-sg", "RuleUp NAT instance - inbound only from admin tasks")

    # 관리자 SG: 인바운드 0. 아웃바운드는 필요한 것만 — 기본 0.0.0.0/0 전체 허용 규칙은 걷는다.
    if admin_sg:
        g = ec2.describe_security_groups(GroupIds=[admin_sg])["SecurityGroups"][0]
        if g["IpPermissions"]:
            act("관리자 SG 인바운드 규칙 제거(인바운드 0 유지)",
                lambda: ec2.revoke_security_group_ingress(GroupId=admin_sg, IpPermissions=g["IpPermissions"]))
        want = [
            {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "IpRanges": [{"CidrIp": "0.0.0.0/0", "Description": "ECR/Secrets/Logs/SQS/S3/Access JWKS/Cloudflare"}]},
            {"IpProtocol": "tcp", "FromPort": 7844, "ToPort": 7844, "IpRanges": [{"CidrIp": "0.0.0.0/0", "Description": "cloudflared edge (HTTP2)"}]},
            {"IpProtocol": "udp", "FromPort": 7844, "ToPort": 7844, "IpRanges": [{"CidrIp": "0.0.0.0/0", "Description": "cloudflared edge (QUIC)"}]},
            {"IpProtocol": "tcp", "FromPort": 3306, "ToPort": 3306, "UserIdGroupPairs": [{"GroupId": c["rds_sg"], "Description": "RDS"}]},
            {"IpProtocol": "tcp", "FromPort": 6379, "ToPort": 6379, "UserIdGroupPairs": [{"GroupId": c["redis_sg"], "Description": "Redis"}]},
        ]
        have = g["IpPermissionsEgress"]
        allow_all = [p for p in have if p["IpProtocol"] == "-1"]
        if allow_all:
            act("관리자 SG 기본 전체 아웃바운드 제거", lambda: ec2.revoke_security_group_egress(GroupId=admin_sg, IpPermissions=allow_all))
        for w in want:
            exists = any(p["IpProtocol"] == w["IpProtocol"] and p.get("FromPort") == w["FromPort"] for p in have)
            if exists:
                ok(f"관리자 SG 아웃바운드 {w['IpProtocol']}/{w['FromPort']}")
            else:
                act(f"관리자 SG 아웃바운드 {w['IpProtocol']}/{w['FromPort']} 추가",
                    lambda w=w: ec2.authorize_security_group_egress(GroupId=admin_sg, IpPermissions=[w]))

    ensure_ingress_from_sg(c["rds_sg"], 3306, admin_sg, f"ruleup-{env}-admin -> RDS")
    ensure_ingress_from_sg(c["redis_sg"], 6379, admin_sg, f"ruleup-{env}-admin -> Redis")

    # NAT 인스턴스 SG: 관리자 SG 에서 오는 것만 받는다(공개 API 태스크는 public 서브넷이라 NAT 를 쓰지 않는다).
    if nat_sg:
        g = ec2.describe_security_groups(GroupIds=[nat_sg])["SecurityGroups"][0]
        if not any(any(x["GroupId"] == admin_sg for x in p.get("UserIdGroupPairs", [])) for p in g["IpPermissions"]):
            act("NAT SG 인입 ← 관리자 SG(전 프로토콜)", lambda: ec2.authorize_security_group_ingress(
                GroupId=nat_sg, IpPermissions=[{"IpProtocol": "-1", "UserIdGroupPairs": [{"GroupId": admin_sg, "Description": "admin tasks egress"}]}]))
        else:
            ok("NAT SG 인입 ← 관리자 SG")

    nat_eni = nat_instance(env, c, nat_sg)
    app_route_table(env, c, nat_eni)
    return admin_sg


def nat_instance(env, c, nat_sg):
    """t4g.nano NAT 인스턴스. 키페어·SSH 없음. AWS 단순 자동 복구(기본 켜짐)로 하드웨어 장애 시 같은 ENI·IP 로 되살아난다."""
    name = f"ruleup-{env}-nat"
    r = ec2.describe_instances(Filters=[{"Name": "tag:Name", "Values": [name]},
                                        {"Name": "instance-state-name", "Values": ["pending", "running", "stopping", "stopped"]}])
    inst = [i for res in r["Reservations"] for i in res["Instances"]]
    if inst:
        i = inst[0]
        ok(f"NAT 인스턴스 {i['InstanceId']} ({i['State']['Name']}) eni={i['NetworkInterfaces'][0]['NetworkInterfaceId']}")
        return i["NetworkInterfaces"][0]["NetworkInterfaceId"]
    ami = ssm.get_parameter(Name=NAT_AMI_PARAM)["Parameter"]["Value"]
    user_data = """#!/bin/bash
set -euo pipefail
dnf install -y iptables-services
cat >/etc/sysctl.d/90-nat.conf <<'EOF'
net.ipv4.ip_forward = 1
net.ipv4.conf.all.rp_filter = 0
EOF
sysctl --system
IF=$(ip -o -4 route show to default | awk '{print $5}')
iptables -t nat -A POSTROUTING -o "$IF" -j MASQUERADE
iptables -A FORWARD -m state --state RELATED,ESTABLISHED -j ACCEPT
iptables -A FORWARD -s 10.0.0.0/8 -j ACCEPT
iptables-save >/etc/sysconfig/iptables
systemctl enable --now iptables
"""
    def create():
        res = ec2.run_instances(
            ImageId=ami, InstanceType="t4g.nano", MinCount=1, MaxCount=1,
            NetworkInterfaces=[{"DeviceIndex": 0, "SubnetId": c["public_subnets"][NAT_SUBNET_INDEX], "Groups": [nat_sg],
                                "AssociatePublicIpAddress": True, "DeleteOnTermination": True}],
            UserData=user_data,
            MetadataOptions={"HttpTokens": "required", "HttpEndpoint": "enabled"},
            MaintenanceOptions={"AutoRecovery": "default"},
            DisableApiTermination=True,
            BlockDeviceMappings=[{"DeviceName": "/dev/xvda", "Ebs": {"VolumeSize": 8, "VolumeType": "gp3", "Encrypted": True}}],
            TagSpecifications=[{"ResourceType": "instance", "Tags": tags(name, env)},
                               {"ResourceType": "volume", "Tags": tags(name, env)}])
        i = res["Instances"][0]
        eni = i["NetworkInterfaces"][0]["NetworkInterfaceId"]
        ec2.modify_network_interface_attribute(NetworkInterfaceId=eni, SourceDestCheck={"Value": False})
        ec2.get_waiter("instance_running").wait(InstanceIds=[i["InstanceId"]])
        # 하드웨어 장애가 아닌 OS 멈춤(인스턴스 상태 검사 실패)은 자동 복구 대상이 아니다 — 재부팅 경보를 건다.
        cw.put_metric_alarm(
            AlarmName=f"ruleup-{env}-nat-instance-check", Namespace="AWS/EC2", MetricName="StatusCheckFailed_Instance",
            Dimensions=[{"Name": "InstanceId", "Value": i["InstanceId"]}], Statistic="Maximum", Period=60,
            EvaluationPeriods=3, Threshold=1, ComparisonOperator="GreaterThanOrEqualToThreshold",
            AlarmActions=[f"arn:aws:automate:{R}:ec2:reboot", f"arn:aws:sns:{R}:{ACCOUNT}:ruleup-{env}-alarms"])
        return eni
    return act(f"NAT 인스턴스 {name} 생성(t4g.nano, {c['public_subnets'][NAT_SUBNET_INDEX]}, source/dest check 끔, 자동 복구·재부팅 경보)", create)


def app_route_table(env, c, nat_eni):
    """app 서브넷만 쓰는 라우트 테이블. data 서브넷(RDS·Redis)과 나눠, 데이터 계층에는 인터넷 경로가 생기지 않게 한다."""
    name = f"ruleup-{env}-app-rt"
    r = ec2.describe_route_tables(Filters=[{"Name": "vpc-id", "Values": [c["vpc"]]}, {"Name": "tag:Name", "Values": [name]}])
    rt = r["RouteTables"][0]["RouteTableId"] if r["RouteTables"] else None
    if rt:
        ok(f"라우트 테이블 {name} {rt}")
    else:
        rt = act(f"라우트 테이블 {name} 생성", lambda: ec2.create_route_table(
            VpcId=c["vpc"], TagSpecifications=[{"ResourceType": "route-table", "Tags": tags(name, env)}])["RouteTable"]["RouteTableId"])
    if rt and nat_eni:
        routes = ec2.describe_route_tables(RouteTableIds=[rt])["RouteTables"][0]["Routes"]
        default = next((x for x in routes if x.get("DestinationCidrBlock") == "0.0.0.0/0"), None)
        if default and default.get("NetworkInterfaceId") == nat_eni and default.get("State") == "active":
            ok("0.0.0.0/0 → NAT")
        elif default:
            # NAT 를 다시 만들었거나(AZ 장애 복구) 옛 ENI 가 사라져 blackhole 이 된 경우
            act(f"0.0.0.0/0 → 새 NAT ENI {nat_eni} 로 교체(이전 {default.get('NetworkInterfaceId')}, {default.get('State')})",
                lambda: ec2.replace_route(RouteTableId=rt, DestinationCidrBlock="0.0.0.0/0", NetworkInterfaceId=nat_eni))
        else:
            act("0.0.0.0/0 → NAT ENI", lambda: ec2.create_route(RouteTableId=rt, DestinationCidrBlock="0.0.0.0/0", NetworkInterfaceId=nat_eni))
    else:
        act("0.0.0.0/0 → NAT ENI(NAT 생성 후)")

    # S3 게이트웨이 엔드포인트(무료) — ECR 이미지 레이어·미디어 버킷을 NAT 를 거치지 않고 받는다.
    eps = ec2.describe_vpc_endpoints(Filters=[{"Name": "vpc-id", "Values": [c["vpc"]]},
                                              {"Name": "service-name", "Values": [f"com.amazonaws.{R}.s3"]}])["VpcEndpoints"]
    if eps:
        ep = eps[0]
        if rt and rt not in ep["RouteTableIds"]:
            act(f"S3 게이트웨이 엔드포인트 {ep['VpcEndpointId']} 를 {name} 에 연결",
                lambda: ec2.modify_vpc_endpoint(VpcEndpointId=ep["VpcEndpointId"], AddRouteTableIds=[rt]))
        else:
            ok(f"S3 게이트웨이 엔드포인트 {ep['VpcEndpointId']}")
    else:
        act("S3 게이트웨이 엔드포인트 생성(무료)", lambda: ec2.create_vpc_endpoint(
            VpcId=c["vpc"], ServiceName=f"com.amazonaws.{R}.s3", VpcEndpointType="Gateway", RouteTableIds=[rt],
            TagSpecifications=[{"ResourceType": "vpc-endpoint", "Tags": tags(f"ruleup-{env}-s3", env)}]))

    current = ec2.describe_route_tables(Filters=[{"Name": "association.subnet-id", "Values": c["app_subnets"]}])["RouteTables"]
    for sub in c["app_subnets"]:
        assoc = next((a for t in current for a in t["Associations"] if a.get("SubnetId") == sub), None)
        if assoc and assoc["RouteTableId"] == rt:
            ok(f"{sub} → {name}")
        elif assoc:
            act(f"{sub} 의 라우트 테이블 교체 {assoc['RouteTableId']} → {name}",
                lambda a=assoc: ec2.replace_route_table_association(AssociationId=a["RouteTableAssociationId"], RouteTableId=rt))
        else:
            act(f"{sub} → {name} 연결", lambda s=sub: ec2.associate_route_table(SubnetId=s, RouteTableId=rt))


# ---------------------------------------------------------------- iam
def put_role(name, trust, policies, desc):
    try:
        iam.get_role(RoleName=name)
        ok(f"역할 {name}")
        exists = True
    except iam.exceptions.NoSuchEntityException:
        exists = False
    if not exists:
        act(f"역할 {name} 생성 — {desc}", lambda: iam.create_role(RoleName=name, AssumeRolePolicyDocument=json.dumps(trust),
                                                                Description=desc, MaxSessionDuration=3600))
    for pname, doc in policies.items():
        act(f"  인라인 정책 {name}/{pname}", lambda p=pname, d=doc: iam.put_role_policy(
            RoleName=name, PolicyName=p, PolicyDocument=json.dumps(d)))


def secret_arn(name):
    try:
        return sm.describe_secret(SecretId=name)["ARN"]
    except sm.exceptions.ResourceNotFoundException:
        return f"arn:aws:secretsmanager:{R}:{ACCOUNT}:secret:{name}-*"


def iam_roles(env, c):
    print("== iam")
    ecs_trust = {"Version": "2012-10-17", "Statement": [{
        "Effect": "Allow", "Principal": {"Service": "ecs-tasks.amazonaws.com"}, "Action": "sts:AssumeRole",
        "Condition": {"StringEquals": {"aws:SourceAccount": ACCOUNT}}}]}
    log_group = f"arn:aws:logs:{R}:{ACCOUNT}:log-group:/ecs/ruleup-{env}-admin"
    ecr_pull = [
        {"Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*"},
        {"Effect": "Allow", "Action": ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"],
         "Resource": f"arn:aws:ecr:{R}:{ACCOUNT}:repository/ruleup-api"},
        {"Effect": "Allow", "Action": ["logs:CreateLogStream", "logs:PutLogEvents"], "Resource": f"{log_group}:*"},
    ]
    # 실행 역할(ECS 에이전트): 이미지·로그·관리자 시크릿만. 공개 API 시크릿(ruleup-<env>/app)은 읽지 못한다.
    put_role(f"ruleup-{env}-admin-exec", ecs_trust, {
        "admin-exec": {"Version": "2012-10-17", "Statement": ecr_pull + [
            {"Effect": "Allow", "Action": "secretsmanager:GetSecretValue", "Resource": secret_arn(f"ruleup-{env}/admin")}]}},
        "RuleUp admin API task execution: ECR pull, admin log group, admin secret only")
    # 애플리케이션 역할(앱 코드): 알림 큐 발행 + 미디어 읽기(presign) + 자기 네임스페이스 지표만.
    # 수신·삭제·심사 큐·Bedrock·S3 쓰기·ECS Exec 없음.
    queue = f"arn:aws:sqs:{R}:{ACCOUNT}:ruleup-{env}-notifications"
    put_role(f"ruleup-{env}-admin-task", ecs_trust, {
        "admin-app": {"Version": "2012-10-17", "Statement": [
            {"Effect": "Allow", "Action": ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"], "Resource": queue},
            {"Effect": "Allow", "Action": "s3:GetObject", "Resource": f"arn:aws:s3:::{c['media_bucket']}/*"},
            {"Effect": "Allow", "Action": "cloudwatch:PutMetricData", "Resource": "*",
             "Condition": {"StringLike": {"cloudwatch:namespace": "RuleUp/App/*"}}}]}},
        "RuleUp admin API application: notification send, media read, metrics")
    # DB 일회성 태스크(계정 관리·마이그레이션): 마스터·계정 비밀번호를 읽는다. 상시 서비스와 역할을 나눈다 —
    # 공개 API·관리자 서비스의 실행 역할은 이 비밀번호들을 읽지 못한다.
    put_role(f"ruleup-{env}-dbops-exec", ecs_trust, {
        "dbops-exec": {"Version": "2012-10-17", "Statement": ecr_pull + [
            {"Effect": "Allow", "Action": "secretsmanager:GetSecretValue", "Resource": [
                secret_arn(f"ruleup-{env}/app"), secret_arn(f"ruleup-{env}/admin"), secret_arn(f"ruleup-{env}/db-users")]}]}},
        "RuleUp DB one-off tasks: user provisioning, schema migration")
    # 배포 역할: 관리자 서비스 갱신·태스크 정의 등록·관리자 역할 전달.
    branch_role = f"ruleup-{env}-gha-deploy"
    act(f"  인라인 정책 {branch_role}/deploy-admin", lambda: iam.put_role_policy(
        RoleName=branch_role, PolicyName="deploy-admin", PolicyDocument=json.dumps({"Version": "2012-10-17", "Statement": [
            {"Effect": "Allow", "Action": ["ecs:UpdateService", "ecs:DescribeServices"],
             "Resource": f"arn:aws:ecs:{R}:{ACCOUNT}:service/ruleup-{env}-cluster/ruleup-{env}-admin"},
            {"Effect": "Allow", "Action": ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"], "Resource": "*"},
            {"Effect": "Allow", "Action": "ecr:DescribeImages", "Resource": f"arn:aws:ecr:{R}:{ACCOUNT}:repository/ruleup-api"},
            {"Effect": "Allow", "Action": "ecs:RunTask",
             "Resource": f"arn:aws:ecs:{R}:{ACCOUNT}:task-definition/ruleup-migrate-{env}:*",
             "Condition": {"ArnEquals": {"ecs:cluster": f"arn:aws:ecs:{R}:{ACCOUNT}:cluster/ruleup-{env}-cluster"}}},
            {"Effect": "Allow", "Action": "ecs:DescribeTasks", "Resource": f"arn:aws:ecs:{R}:{ACCOUNT}:task/ruleup-{env}-cluster/*"},
            {"Effect": "Allow", "Action": "iam:PassRole", "Resource": [
                f"arn:aws:iam::{ACCOUNT}:role/ruleup-{env}-admin-exec", f"arn:aws:iam::{ACCOUNT}:role/ruleup-{env}-admin-task",
                f"arn:aws:iam::{ACCOUNT}:role/ruleup-{env}-dbops-exec"],
             "Condition": {"StringEquals": {"iam:PassedToService": "ecs-tasks.amazonaws.com"}}}]})))


# ---------------------------------------------------------------- secrets
def secrets(env, c):
    print("== secrets")

    def ensure(name, keys, desc):
        try:
            cur = json.loads(sm.get_secret_value(SecretId=name)["SecretString"])
            missing = [k for k in keys if k not in cur]
            if not missing:
                ok(f"시크릿 {name} (키 {sorted(cur)})")
                return
            def add():
                for k in missing:
                    cur[k] = keys[k]()
                sm.put_secret_value(SecretId=name, SecretString=json.dumps(cur))
            act(f"시크릿 {name} 에 키 추가 {missing}", add)
        except sm.exceptions.ResourceNotFoundException:
            act(f"시크릿 {name} 생성 (키 {sorted(keys)})", lambda: sm.create_secret(
                Name=name, Description=desc, SecretString=json.dumps({k: f() for k, f in keys.items()}),
                Tags=[{"Key": "env", "Value": env}, {"Key": "managed-by", "Value": "infra/admin-split"}]))

    def pw():
        # 영숫자만 — SQL·셸·sed 에서 따옴표 문제가 없다. 32자면 엔트로피가 충분하다.
        return sm.get_random_password(PasswordLength=32, ExcludePunctuation=True)["RandomPassword"]

    def ban_salt():
        # 영구 정지 해시 솔트는 공개 API 와 같아야 한다 — 다르면 관리자가 건 영구 정지가 가입 차단에 안 걸린다.
        app = json.loads(sm.get_secret_value(SecretId=f"ruleup-{env}/app")["SecretString"])
        return app.get("BAN_LIST_SALT", "ruleup-local-ban-salt")

    ensure(f"ruleup-{env}/admin", {"DB_PASSWORD": pw, "BAN_LIST_SALT": ban_salt, "TUNNEL_TOKEN": lambda: "SET_ME"},
           "RuleUp admin API: ruleup_admin DB password, ban salt, Cloudflare tunnel token")
    ensure(f"ruleup-{env}/db-users", {"APP_DB_PASSWORD": pw, "MIGRATOR_DB_PASSWORD": pw},
           "RuleUp DB users for public API(ruleup_app) and migrations(ruleup_migrator)")
    print("  ※ TUNNEL_TOKEN 은 Cloudflare 에서 터널을 만든 뒤 사람이 넣는다(README 2단계). 값은 출력하지 않는다.")


# ---------------------------------------------------------------- logs
def log_group(env, c):
    print("== logs")
    name = f"/ecs/ruleup-{env}-admin"
    if logs.describe_log_groups(logGroupNamePrefix=name)["logGroups"]:
        ok(f"로그 그룹 {name}")
    else:
        act(f"로그 그룹 {name} 생성(90일)", lambda: (logs.create_log_group(logGroupName=name),
                                                logs.put_retention_policy(logGroupName=name, retentionInDays=90)))
    act("메트릭 필터 admin_access_denied → RuleUp/Admin AccessDenied", lambda: logs.put_metric_filter(
        logGroupName=name, filterName="admin-access-denied", filterPattern='"admin_access_denied"',
        metricTransformations=[{"metricName": "AccessDenied", "metricNamespace": "RuleUp/Admin", "metricValue": "1",
                                "defaultValue": 0}]))
    act(f"경보 ruleup-{env}-admin-access-denied(5분 20회 이상)", lambda: cw.put_metric_alarm(
        AlarmName=f"ruleup-{env}-admin-access-denied", Namespace="RuleUp/Admin", MetricName="AccessDenied",
        Statistic="Sum", Period=300, EvaluationPeriods=1, Threshold=20, ComparisonOperator="GreaterThanOrEqualToThreshold",
        TreatMissingData="notBreaching", AlarmActions=[f"arn:aws:sns:{R}:{ACCOUNT}:ruleup-{env}-alarms"],
        AlarmDescription="관리자 API 인증 거부 급증 — 위조 토큰·허용 목록 밖 접근·우회 시도의 신호"))


# ---------------------------------------------------------------- ecs
def task_definition(env, c, image, access):
    admin_secret = secret_arn(f"ruleup-{env}/admin")
    if admin_secret.endswith("-*"):
        admin_secret = f"arn:aws:secretsmanager:{R}:{ACCOUNT}:secret:ruleup-{env}/admin"
    env_vars = {
        "SPRING_PROFILES_ACTIVE": f"{env},admin",
        "SPRING_DATASOURCE_URL": f"jdbc:mysql://{c['db_host']}:3306/RuleUp7?{c['jdbc_params']}",
        "SPRING_DATASOURCE_USERNAME": "ruleup_admin",
        "ADMIN_DB_POOL_SIZE": "5",
        "SPRING_DATA_REDIS_HOST": c["redis_host"], "SPRING_DATA_REDIS_PORT": "6379",
        "NOTIFICATION_QUEUE_URL": f"https://sqs.{R}.amazonaws.com/{ACCOUNT}/ruleup-{env}-notifications",
        "NOTIFICATION_QUEUE_REGION": R,
        "IMAGE_STORAGE": "s3", "IMAGE_S3_BUCKET": c["media_bucket"],
        "FCM_ENABLED": "false",
        "ADMIN_ACCESS_TEAM_DOMAIN": access["team_domain"],
        "ADMIN_ACCESS_AUDIENCES": access["audiences"],
        "ADMIN_ACCESS_ALLOWED_EMAILS": access["emails"],
        "ADMIN_ACCESS_ALLOWED_ORIGINS": c["admin_origin"],
        "JAVA_TOOL_OPTIONS": "-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError",
        "OTEL_ENABLED": "false",
    }
    return dict(
        family=f"ruleup-admin-{env}", networkMode="awsvpc", requiresCompatibilities=["FARGATE"],
        cpu="512", memory="1024", runtimePlatform={"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
        executionRoleArn=f"arn:aws:iam::{ACCOUNT}:role/ruleup-{env}-admin-exec",
        taskRoleArn=f"arn:aws:iam::{ACCOUNT}:role/ruleup-{env}-admin-task",
        containerDefinitions=[
            {"name": "admin", "image": image, "essential": True,
             "portMappings": [{"containerPort": 8080, "protocol": "tcp"}],
             "environment": [{"name": k, "value": v} for k, v in env_vars.items()],
             "secrets": [{"name": "SPRING_DATASOURCE_PASSWORD", "valueFrom": f"{admin_secret}:DB_PASSWORD::"},
                         {"name": "BAN_LIST_SALT", "valueFrom": f"{admin_secret}:BAN_LIST_SALT::"}],
             "healthCheck": {"command": ["CMD-SHELL",
                                         "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf \"GET /actuator/health HTTP/1.0\\r\\n\\r\\n\" >&3 && grep -q UP <&3'"],
                             "interval": 15, "timeout": 5, "retries": 3, "startPeriod": 150},
             "readonlyRootFilesystem": False,
             "stopTimeout": 60,
             "logConfiguration": {"logDriver": "awslogs", "options": {
                 "awslogs-group": f"/ecs/ruleup-{env}-admin", "awslogs-region": R, "awslogs-stream-prefix": "admin"}}},
            # 터널 커넥터 — 같은 태스크 안이라 원본은 localhost 다. 관리자 SG 에 인바운드가 하나도 없는 이유.
            # essential: 커넥터가 죽으면 태스크가 내려가고 서비스가 새 태스크(커넥터 포함)를 띄운다.
            # dependsOn HEALTHY: 앱이 뜨기 전에 터널이 붙어 502 를 내지 않게.
            {"name": "cloudflared", "image": CLOUDFLARED_IMAGE, "essential": True,
             "command": ["tunnel", "--no-autoupdate", "--metrics", "127.0.0.1:2000", "run"],
             "secrets": [{"name": "TUNNEL_TOKEN", "valueFrom": f"{admin_secret}:TUNNEL_TOKEN::"}],
             "dependsOn": [{"containerName": "admin", "condition": "HEALTHY"}],
             "user": "65532",
             "readonlyRootFilesystem": True,
             "memoryReservation": 64,
             "logConfiguration": {"logDriver": "awslogs", "options": {
                 "awslogs-group": f"/ecs/ruleup-{env}-admin", "awslogs-region": R, "awslogs-stream-prefix": "tunnel"}}},
        ],
        tags=[{"key": "env", "value": env}, {"key": "managed-by", "value": "infra/admin-split"}])


def ecs_service(env, c, admin_sg, access, image_tag):
    print("== ecs")
    image = f"{ACCOUNT}.dkr.ecr.{R}.amazonaws.com/ruleup-api:{image_tag}"
    td = task_definition(env, c, image, access)
    arn = act(f"태스크 정의 {td['family']} 등록(이미지 {image_tag}, 앱+cloudflared, 0.5vCPU/1GB ARM64)",
              lambda: ecs.register_task_definition(**td)["taskDefinition"]["taskDefinitionArn"])
    name = f"ruleup-{env}-admin"
    svc = ecs.describe_services(cluster=f"ruleup-{env}-cluster", services=[name])["services"]
    if svc and svc[0]["status"] == "ACTIVE":
        ok(f"서비스 {name} (desired={svc[0]['desiredCount']}) — 배포는 워크플로가 한다")
        return
    act(f"서비스 {name} 생성 — private {c['app_subnets']}, 공인 IP 없음, {c['capacity'][0]['capacityProvider']}, desired 0",
        lambda: ecs.create_service(
            cluster=f"ruleup-{env}-cluster", serviceName=name, taskDefinition=arn or td["family"], desiredCount=0,
            capacityProviderStrategy=c["capacity"],
            networkConfiguration={"awsvpcConfiguration": {"subnets": c["app_subnets"], "securityGroups": [admin_sg],
                                                          "assignPublicIp": "DISABLED"}},
            deploymentConfiguration={"minimumHealthyPercent": 100, "maximumPercent": 200,
                                     "deploymentCircuitBreaker": {"enable": True, "rollback": True}},
            enableExecuteCommand=False, propagateTags="SERVICE",
            tags=[{"key": "env", "value": env}, {"key": "managed-by", "value": "infra/admin-split"}]))


def dbops_task(env, c, admin_sg):
    """DB 계정을 만드는 일회성 태스크 정의. SQL 은 RunTask 때 base64 로 넘기고, 비밀번호는 시크릿 주입으로만 들어온다."""
    app_secret, admin_secret, users_secret = (secret_arn(f"ruleup-{env}/{n}") for n in ("app", "admin", "db-users"))
    td = dict(
        family=f"ruleup-dbops-{env}", networkMode="awsvpc", requiresCompatibilities=["FARGATE"], cpu="256", memory="512",
        runtimePlatform={"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
        executionRoleArn=f"arn:aws:iam::{ACCOUNT}:role/ruleup-{env}-dbops-exec",
        containerDefinitions=[{
            "name": "dbops", "image": "public.ecr.aws/docker/library/mysql:8.4", "essential": True,
            "entryPoint": ["sh", "-c"],
            "command": ["echo \"$SQL_B64\" | base64 -d"
                        " | sed -e \"s/\\${ADMIN_DB_PASSWORD}/$ADMIN_DB_PASSWORD/g\""
                        " -e \"s/\\${APP_DB_PASSWORD}/$APP_DB_PASSWORD/g\""
                        " -e \"s/\\${MIGRATOR_DB_PASSWORD}/$MIGRATOR_DB_PASSWORD/g\""
                        " | MYSQL_PWD=\"$MASTER_PASSWORD\" mysql --ssl-mode=REQUIRED -h \"$DB_HOST\" -u ruleup"],
            "environment": [{"name": "DB_HOST", "value": c["db_host"]}],
            "secrets": [{"name": "MASTER_PASSWORD", "valueFrom": f"{app_secret}:DB_PASSWORD::"},
                        {"name": "ADMIN_DB_PASSWORD", "valueFrom": f"{admin_secret}:DB_PASSWORD::"},
                        {"name": "APP_DB_PASSWORD", "valueFrom": f"{users_secret}:APP_DB_PASSWORD::"},
                        {"name": "MIGRATOR_DB_PASSWORD", "valueFrom": f"{users_secret}:MIGRATOR_DB_PASSWORD::"}],
            "logConfiguration": {"logDriver": "awslogs", "options": {
                "awslogs-group": f"/ecs/ruleup-{env}-admin", "awslogs-region": R, "awslogs-stream-prefix": "dbops"}}}])
    act(f"DB 작업 태스크 정의 {td['family']} 등록", lambda: ecs.register_task_definition(**td))


def migrate_task(env, c, image_tag):
    """스키마 마이그레이션 일회성 태스크 — 같은 이미지를 migrate 프로필로. DDL 계정(ruleup_migrator)은 여기에만 들어간다."""
    users_secret = secret_arn(f"ruleup-{env}/db-users")
    td = dict(
        family=f"ruleup-migrate-{env}", networkMode="awsvpc", requiresCompatibilities=["FARGATE"], cpu="512", memory="1024",
        runtimePlatform={"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
        executionRoleArn=f"arn:aws:iam::{ACCOUNT}:role/ruleup-{env}-dbops-exec",
        containerDefinitions=[{
            "name": "migrate", "image": f"{ACCOUNT}.dkr.ecr.{R}.amazonaws.com/ruleup-api:{image_tag}", "essential": True,
            "environment": [
                {"name": "SPRING_PROFILES_ACTIVE", "value": f"{env},migrate"},
                {"name": "SPRING_DATASOURCE_URL", "value": f"jdbc:mysql://{c['db_host']}:3306/RuleUp7?{c['jdbc_params']}"},
                {"name": "SPRING_DATASOURCE_USERNAME", "value": "ruleup_migrator"},
                {"name": "SPRING_DATA_REDIS_HOST", "value": c["redis_host"]},
                {"name": "JAVA_TOOL_OPTIONS", "value": "-XX:MaxRAMPercentage=70"}],
            "secrets": [{"name": "SPRING_DATASOURCE_PASSWORD", "valueFrom": f"{users_secret}:MIGRATOR_DB_PASSWORD::"}],
            "logConfiguration": {"logDriver": "awslogs", "options": {
                "awslogs-group": f"/ecs/ruleup-{env}-admin", "awslogs-region": R, "awslogs-stream-prefix": "migrate"}}}])
    act(f"마이그레이션 태스크 정의 {td['family']} 등록", lambda: ecs.register_task_definition(**td))


# ---------------------------------------------------------------- alb
def alb_guard(env, c):
    print("== alb-guard")
    for listener in c["alb_listeners"]:
        rules = elb.describe_rules(ListenerArn=listener)["Rules"]
        hit = [r for r in rules if any(v == "/api/v1/admin/*" for cond in r["Conditions"]
                                       for v in cond.get("PathPatternConfig", {}).get("Values", []))]
        if hit:
            ok(f"{listener.split('/')[-2]}: /api/v1/admin/* → 404 (priority {hit[0]['Priority']})")
            continue
        used = {int(r["Priority"]) for r in rules if r["Priority"] != "default"}
        prio = next(p for p in range(1, 50000) if p not in used)
        act(f"{listener.split('/')[-2]}: /api/v1/admin/* → 404 고정 응답(priority {prio})", lambda l=listener, p=prio: elb.create_rule(
            ListenerArn=l, Priority=p,
            Conditions=[{"Field": "path-pattern", "PathPatternConfig": {"Values": ["/api/v1/admin/*", "/api/v1/admin"]}}],
            Actions=[{"Type": "fixed-response", "FixedResponseConfig": {
                "StatusCode": "404", "ContentType": "application/json",
                "MessageBody": "{\"success\":false,\"data\":null,\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없어요.\"}}"}}],
            Tags=[{"Key": "managed-by", "Value": "infra/admin-split"}]))


def alb_lockdown(env, c):
    """공개 ALB 인입을 Cloudflare 대역으로만. 직접 ALB 주소로 WAF 를 우회하는 길을 닫는다."""
    print("== alb-lockdown")
    ranges = {}
    for fam, url in (("v4", "https://www.cloudflare.com/ips-v4"), ("v6", "https://www.cloudflare.com/ips-v6")):
        with urllib.request.urlopen(url, timeout=10) as r:
            ranges[fam] = [x.strip() for x in r.read().decode().split() if x.strip()]
    pl_ids = {}
    for fam, cidrs in ranges.items():
        name = f"cloudflare-ip{fam}"
        pls = ec2.describe_managed_prefix_lists(Filters=[{"Name": "prefix-list-name", "Values": [name]}])["PrefixLists"]
        if pls:
            pl = pls[0]
            entries = {e["Cidr"] for e in ec2.get_managed_prefix_list_entries(PrefixListId=pl["PrefixListId"])["Entries"]}
            add, rem = set(cidrs) - entries, entries - set(cidrs)
            if add or rem:
                act(f"접두사 목록 {name} 갱신 +{len(add)} -{len(rem)}", lambda p=pl, a=add, d=rem: ec2.modify_managed_prefix_list(
                    PrefixListId=p["PrefixListId"], CurrentVersion=p["Version"],
                    AddEntries=[{"Cidr": x} for x in a], RemoveEntries=[{"Cidr": x} for x in d]))
            else:
                ok(f"접두사 목록 {name} ({len(entries)}개)")
            pl_ids[fam] = pl["PrefixListId"]
        else:
            pl_ids[fam] = act(f"접두사 목록 {name} 생성({len(cidrs)}개)", lambda f=fam, n=name, cs=cidrs: ec2.create_managed_prefix_list(
                PrefixListName=n, AddressFamily="IPv4" if f == "v4" else "IPv6", MaxEntries=max(30, len(cs) + 10),
                Entries=[{"Cidr": x, "Description": "Cloudflare"} for x in cs])["PrefixList"]["PrefixListId"])

    g = ec2.describe_security_groups(GroupIds=[c["alb_sg"]])["SecurityGroups"][0]
    for p in g["IpPermissions"]:
        port = p.get("FromPort")
        open_world = [x for x in p.get("IpRanges", []) if x["CidrIp"] == "0.0.0.0/0"] + \
                     [x for x in p.get("Ipv6Ranges", []) if x["CidrIpv6"] == "::/0"]
        if not open_world:
            ok(f"ALB SG :{port} 전체 공개 아님")
            continue
        def swap(port=port, p=p):
            ec2.authorize_security_group_ingress(GroupId=c["alb_sg"], IpPermissions=[{
                "IpProtocol": "tcp", "FromPort": port, "ToPort": port,
                "PrefixListIds": [{"PrefixListId": pl_ids[f], "Description": f"Cloudflare {f}"} for f in ("v4", "v6") if pl_ids.get(f)]}])
            ec2.revoke_security_group_ingress(GroupId=c["alb_sg"], IpPermissions=[{
                "IpProtocol": "tcp", "FromPort": port, "ToPort": port,
                **({"IpRanges": [{"CidrIp": "0.0.0.0/0"}]} if p.get("IpRanges") else {}),
                **({"Ipv6Ranges": [{"CidrIpv6": "::/0"}]} if p.get("Ipv6Ranges") else {})}])
        act(f"ALB SG :{port} 0.0.0.0/0 → Cloudflare 접두사 목록으로 교체(먼저 추가 후 제거)", swap)


def main():
    global APPLY, NAT_SUBNET_INDEX
    ap = argparse.ArgumentParser()
    ap.add_argument("env", choices=ENV)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--only", default="network,iam,secrets,logs,ecs,alb-guard")
    ap.add_argument("--image-tag", default=None, help="첫 태스크 정의에 쓸 이미지 태그(기본: env 이름)")
    ap.add_argument("--access-team-domain", default="https://ruleup.cloudflareaccess.com")
    ap.add_argument("--access-aud", default="SET_ME")
    ap.add_argument("--access-emails", default="SET_ME")
    ap.add_argument("--nat-az", choices=["a", "c"], default="a", help="NAT 인스턴스를 둘 AZ(장애 복구 때 c)")
    a = ap.parse_args()
    APPLY = a.apply
    NAT_SUBNET_INDEX = 0 if a.nat_az == "a" else 1
    c = ENV[a.env]
    steps = a.only.split(",")
    print(f"# {a.env} — {'적용' if APPLY else '계획(변경 없음)'} — 단계 {steps}")
    access = {"team_domain": a.access_team_domain, "audiences": a.access_aud, "emails": a.access_emails}
    admin_sg = find_sg(c["vpc"], f"ruleup-{a.env}-admin-sg")
    if "network" in steps:
        admin_sg = network(a.env, c) or admin_sg
    if "iam" in steps:
        iam_roles(a.env, c)
    if "secrets" in steps:
        secrets(a.env, c)
    if "logs" in steps:
        log_group(a.env, c)
    if "ecs" in steps:
        ecs_service(a.env, c, admin_sg, access, a.image_tag or a.env)
        dbops_task(a.env, c, admin_sg)
        migrate_task(a.env, c, a.image_tag or a.env)
    if "alb-guard" in steps:
        alb_guard(a.env, c)
    if "alb-lockdown" in steps:
        alb_lockdown(a.env, c)


if __name__ == "__main__":
    main()
