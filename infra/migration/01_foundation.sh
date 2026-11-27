#!/usr/bin/env bash
# 새 계정(ruleup-new) 기반 리소스: VPC·서브넷·보안그룹·ECR·S3·SQS·시크릿·RDS.
# 기존 계정(기본 프로필)에서는 읽기만 한다. 만든 식별자는 STATE 파일에 적는다.
# 한 번 만들고 끝나는 스크립트다 — 다시 돌리면 이미 있는 리소스에서 멈춘다.
set -euo pipefail
export AWS_PAGER="" AWS_DEFAULT_REGION=ap-northeast-2
NEW="--profile ruleup-new"
NEW_ACC=063450199872
OLD_SECRET=ruleup-prod/app
STATE="${HOME}/.ruleup-migration/new-infra.env"
TAGS='{Key=Project,Value=ruleup},{Key=Env,Value=prod}'
say() { printf '\n== %s\n' "$*"; }
put() { echo "$1=$2" >> "$STATE"; echo "  $1=$2"; }
: > "$STATE"

say "VPC 10.20.0.0/16"
VPC=$(aws $NEW ec2 create-vpc --cidr-block 10.20.0.0/16 \
  --tag-specifications "ResourceType=vpc,Tags=[{Key=Name,Value=ruleup-prod-vpc},$TAGS]" --query Vpc.VpcId --output text)
aws $NEW ec2 modify-vpc-attribute --vpc-id "$VPC" --enable-dns-hostnames
put VPC "$VPC"
IGW=$(aws $NEW ec2 create-internet-gateway \
  --tag-specifications "ResourceType=internet-gateway,Tags=[{Key=Name,Value=ruleup-prod-igw},$TAGS]" --query InternetGateway.InternetGatewayId --output text)
aws $NEW ec2 attach-internet-gateway --vpc-id "$VPC" --internet-gateway-id "$IGW"

subnet() { # name cidr az
  aws $NEW ec2 create-subnet --vpc-id "$VPC" --cidr-block "$2" --availability-zone "$3" \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=$1},$TAGS]" --query Subnet.SubnetId --output text
}
PUB_A=$(subnet ruleup-prod-pub-a 10.20.0.0/24 ap-northeast-2a); put PUB_A "$PUB_A"
PUB_C=$(subnet ruleup-prod-pub-c 10.20.1.0/24 ap-northeast-2c); put PUB_C "$PUB_C"
PRI_A=$(subnet ruleup-prod-pri-a 10.20.10.0/24 ap-northeast-2a); put PRI_A "$PRI_A"
PRI_C=$(subnet ruleup-prod-pri-c 10.20.11.0/24 ap-northeast-2c); put PRI_C "$PRI_C"
for s in "$PUB_A" "$PUB_C"; do aws $NEW ec2 modify-subnet-attribute --subnet-id "$s" --map-public-ip-on-launch; done

rt() { # name
  aws $NEW ec2 create-route-table --vpc-id "$VPC" \
    --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=$1},$TAGS]" --query RouteTable.RouteTableId --output text
}
RT_PUB=$(rt ruleup-prod-rt-pub); put RT_PUB "$RT_PUB"
RT_PRI=$(rt ruleup-prod-rt-pri); put RT_PRI "$RT_PRI"
aws $NEW ec2 create-route --route-table-id "$RT_PUB" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW" >/dev/null
for s in "$PUB_A" "$PUB_C"; do aws $NEW ec2 associate-route-table --route-table-id "$RT_PUB" --subnet-id "$s" >/dev/null; done
for s in "$PRI_A" "$PRI_C"; do aws $NEW ec2 associate-route-table --route-table-id "$RT_PRI" --subnet-id "$s" >/dev/null; done
# S3 게이트웨이 엔드포인트(무료) — NAT 없이 프라이빗 서브넷에서 S3 로 간다.
aws $NEW ec2 create-vpc-endpoint --vpc-id "$VPC" --service-name com.amazonaws.ap-northeast-2.s3 \
  --route-table-ids "$RT_PUB" "$RT_PRI" --query VpcEndpoint.VpcEndpointId --output text

say "보안 그룹"
sg() { # name desc
  aws $NEW ec2 create-security-group --vpc-id "$VPC" --group-name "$1" --description "$2" \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=$1},$TAGS]" --query GroupId --output text
}
SG_EC2=$(sg ruleup-prod-ec2 "API server - no inbound, cloudflared outbound only"); put SG_EC2 "$SG_EC2"
SG_ADMIN=$(sg ruleup-prod-admin-lambda "Admin Lambda"); put SG_ADMIN "$SG_ADMIN"
SG_RDS=$(sg ruleup-prod-rds "MySQL from API server and admin Lambda"); put SG_RDS "$SG_RDS"
for src in "$SG_EC2" "$SG_ADMIN"; do
  aws $NEW ec2 authorize-security-group-ingress --group-id "$SG_RDS" --protocol tcp --port 3306 --source-group "$src" >/dev/null
done

say "ECR ruleup-api (최근 10개 유지)"
aws $NEW ecr create-repository --repository-name ruleup-api --image-scanning-configuration scanOnPush=true \
  --tags Key=Project,Value=ruleup --query repository.repositoryUri --output text
aws $NEW ecr put-lifecycle-policy --repository-name ruleup-api --lifecycle-policy-text \
  '{"rules":[{"rulePriority":1,"description":"keep last 10","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":10},"action":{"type":"expire"}}]}' >/dev/null

say "S3 미디어 버킷"
BUCKET=ruleup-prod-media-$NEW_ACC
aws $NEW s3api create-bucket --bucket "$BUCKET" --create-bucket-configuration LocationConstraint=ap-northeast-2 >/dev/null
aws $NEW s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws $NEW s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled
aws $NEW s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration \
  '{"Rules":[{"ID":"expire-noncurrent-30d","Filter":{},"Status":"Enabled","NoncurrentVersionExpiration":{"NoncurrentDays":30},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}'
put BUCKET "$BUCKET"

say "SQS (기존 큐 속성 그대로)"
queue() { # name [dlq-arn]
  local old attrs
  old=$(aws sqs get-queue-url --queue-name "$1" --query QueueUrl --output text)
  attrs=$(aws sqs get-queue-attributes --queue-url "$old" --attribute-names All --output json | python3 -c '
import sys, json
a = json.load(sys.stdin)["Attributes"]; dlq = sys.argv[1]
out = {k: a[k] for k in ("VisibilityTimeout", "MessageRetentionPeriod", "ReceiveMessageWaitTimeSeconds", "DelaySeconds", "MaximumMessageSize", "SqsManagedSseEnabled") if k in a}
if "RedrivePolicy" in a and dlq:
    rp = json.loads(a["RedrivePolicy"]); rp["deadLetterTargetArn"] = dlq; out["RedrivePolicy"] = json.dumps(rp)
print(json.dumps(out))' "${2:-}")
  aws $NEW sqs create-queue --queue-name "$1" --attributes "$attrs" --tags Project=ruleup --query QueueUrl --output text
}
for q in notifications moderation; do
  DLQ_URL=$(queue "ruleup-prod-$q-dlq")
  DLQ_ARN=$(aws $NEW sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  put "$(echo "Q_$q" | tr a-z A-Z)" "$(queue "ruleup-prod-$q" "$DLQ_ARN")"
done

say "시크릿 복사 (값은 화면에 찍지 않는다)"
aws secretsmanager get-secret-value --secret-id "$OLD_SECRET" --query SecretString --output text \
  | aws $NEW secretsmanager create-secret --name ruleup-prod/app --description "copied from 961178969292 on $(date +%F)" \
      --secret-string file:///dev/stdin --tags Key=Project,Value=ruleup --query ARN --output text

say "RDS MySQL 8.4.11 db.t4g.micro Single-AZ"
aws $NEW rds create-db-parameter-group --db-parameter-group-name ruleup-prod-mysql84 \
  --db-parameter-group-family mysql8.4 --description "ruleup prod" >/dev/null
aws $NEW rds modify-db-parameter-group --db-parameter-group-name ruleup-prod-mysql84 --parameters \
  ParameterName=slow_query_log,ParameterValue=1,ApplyMethod=immediate \
  ParameterName=long_query_time,ParameterValue=1,ApplyMethod=immediate \
  ParameterName=log_output,ParameterValue=FILE,ApplyMethod=immediate >/dev/null
aws $NEW rds create-db-subnet-group --db-subnet-group-name ruleup-prod-db \
  --db-subnet-group-description "ruleup prod private" --subnet-ids "$PRI_A" "$PRI_C" >/dev/null
DB_PW=$(aws secretsmanager get-secret-value --secret-id "$OLD_SECRET" --query SecretString --output text \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["DB_PASSWORD"])')
aws $NEW rds create-db-instance --db-instance-identifier ruleup-prod-mysql \
  --engine mysql --engine-version 8.4.11 --db-instance-class db.t4g.micro \
  --storage-type gp3 --allocated-storage 20 --max-allocated-storage 100 --storage-encrypted \
  --master-username ruleup --master-user-password "$DB_PW" \
  --db-subnet-group-name ruleup-prod-db --vpc-security-group-ids "$SG_RDS" --no-publicly-accessible \
  --availability-zone ap-northeast-2a --no-multi-az \
  --db-parameter-group-name ruleup-prod-mysql84 --backup-retention-period 7 \
  --preferred-backup-window 18:00-18:30 --preferred-maintenance-window sun:19:00-sun:20:00 \
  --enable-cloudwatch-logs-exports error slowquery --deletion-protection --copy-tags-to-snapshot \
  --tags Key=Project,Value=ruleup Key=Env,Value=prod \
  --query 'DBInstance.[DBInstanceIdentifier,DBInstanceStatus]' --output text
say "끝 — 식별자: $STATE"
