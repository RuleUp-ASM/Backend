#!/usr/bin/env bash
# 새 계정 서버 계층: IAM 역할 · 구성 버킷 · SNS · 파라미터 · 기존 VPC 피어링 · 시작 템플릿 · ASG(1/1).
# 기존 계정(기본 프로필)에 넣는 변경은 세 가지뿐이고 모두 추가다(되돌리기는 맨 아래 주석).
#   1) 피어링 수락 + 프라이빗 라우트 테이블에 10.20.0.0/16 경로
#   2) DB 보안 그룹에 10.20.0.0/16 → 3306 허용
#   3) ECR ruleup-api 저장소 정책에 새 계정 pull 허용
set -euo pipefail
export AWS_PAGER="" AWS_DEFAULT_REGION=ap-northeast-2
NEW="--profile ruleup-new"
NEW_ACC=063450199872; OLD_ACC=961178969292
OLD_VPC=vpc-0a7005d07813b0a5a; OLD_PRI_RT=rtb-0e6dfaa3d7588bc53; OLD_RDS_SG=sg-06c394aa4581c42c4
OLD_DB=ruleup-prod-mysql-v2.c34mcock6t1w.ap-northeast-2.rds.amazonaws.com
OLD_IMAGE=$OLD_ACC.dkr.ecr.ap-northeast-2.amazonaws.com/ruleup-api:prod
HERE=$(cd "$(dirname "$0")" && pwd)
STATE="${HOME}/.ruleup-migration/new-infra.env"; source "$STATE"
say() { printf '\n== %s\n' "$*"; }
put() { echo "$1=$2" >> "$STATE"; echo "  $1=$2"; }

say "IAM 역할 ruleup-prod-ec2"
aws $NEW iam create-role --role-name ruleup-prod-ec2 --assume-role-policy-document \
  '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' \
  --tags Key=Project,Value=ruleup --query Role.Arn --output text
aws $NEW iam attach-role-policy --role-name ruleup-prod-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws $NEW iam put-role-policy --role-name ruleup-prod-ec2 --policy-name ruleup-prod-app --policy-document "$(cat <<JSON
{"Version":"2012-10-17","Statement":[
 {"Sid":"Media","Effect":"Allow","Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],"Resource":"arn:aws:s3:::$BUCKET/*"},
 {"Sid":"Config","Effect":"Allow","Action":["s3:GetObject","s3:ListBucket"],"Resource":["arn:aws:s3:::ruleup-prod-config-$NEW_ACC","arn:aws:s3:::ruleup-prod-config-$NEW_ACC/*"]},
 {"Sid":"Queues","Effect":"Allow","Action":["sqs:SendMessage","sqs:ReceiveMessage","sqs:DeleteMessage","sqs:ChangeMessageVisibility","sqs:GetQueueAttributes","sqs:GetQueueUrl"],"Resource":"arn:aws:sqs:ap-northeast-2:$NEW_ACC:ruleup-prod-*"},
 {"Sid":"Secrets","Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"arn:aws:secretsmanager:ap-northeast-2:$NEW_ACC:secret:ruleup-prod/*"},
 {"Sid":"Params","Effect":"Allow","Action":["ssm:GetParameter","ssm:PutParameter"],"Resource":"arn:aws:ssm:ap-northeast-2:$NEW_ACC:parameter/ruleup/prod/*"},
 {"Sid":"EcrAuth","Effect":"Allow","Action":"ecr:GetAuthorizationToken","Resource":"*"},
 {"Sid":"EcrPull","Effect":"Allow","Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer","ecr:BatchCheckLayerAvailability"],"Resource":["arn:aws:ecr:ap-northeast-2:$NEW_ACC:repository/ruleup-api","arn:aws:ecr:ap-northeast-2:$OLD_ACC:repository/ruleup-api"]},
 {"Sid":"EcrPushForMigration","Effect":"Allow","Action":["ecr:InitiateLayerUpload","ecr:UploadLayerPart","ecr:CompleteLayerUpload","ecr:PutImage"],"Resource":"arn:aws:ecr:ap-northeast-2:$NEW_ACC:repository/ruleup-api"},
 {"Sid":"Bedrock","Effect":"Allow","Action":["bedrock:Converse","bedrock:ConverseStream","bedrock:InvokeModel"],"Resource":["arn:aws:bedrock:*::foundation-model/*","arn:aws:bedrock:*:$NEW_ACC:inference-profile/*"]},
 {"Sid":"MetricsInterim","Effect":"Allow","Action":"cloudwatch:PutMetricData","Resource":"*"},
 {"Sid":"OpsAlert","Effect":"Allow","Action":"sns:Publish","Resource":"arn:aws:sns:ap-northeast-2:$NEW_ACC:ruleup-prod-alerts"}
]}
JSON
)"
aws $NEW iam create-instance-profile --instance-profile-name ruleup-prod-ec2 >/dev/null
aws $NEW iam add-role-to-instance-profile --instance-profile-name ruleup-prod-ec2 --role-name ruleup-prod-ec2

say "SNS · 구성 버킷 · 파라미터"
TOPIC=$(aws $NEW sns create-topic --name ruleup-prod-alerts --tags Key=Project,Value=ruleup --query TopicArn --output text); put TOPIC "$TOPIC"
CFG=ruleup-prod-config-$NEW_ACC
aws $NEW s3api create-bucket --bucket "$CFG" --create-bucket-configuration LocationConstraint=ap-northeast-2 >/dev/null
aws $NEW s3api put-public-access-block --bucket "$CFG" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws $NEW s3api put-bucket-versioning --bucket "$CFG" --versioning-configuration Status=Enabled
aws $NEW s3 sync "$HERE/../ec2/" "s3://$CFG/ec2/" --only-show-errors
put CFG "$CFG"

APP_ENV=$(python3 - <<PY
import json
print(json.dumps({
 "SPRING_PROFILES_ACTIVE": "prod", "AWS_REGION": "ap-northeast-2",
 "APP_RUNTIME_LEGACY_PUBLIC_ADMIN": "true", "APP_REQUEST_LOG_BODY": "false",
 "SPRING_DATASOURCE_URL": "jdbc:mysql://dbproxy:3306/RuleUp7?createDatabaseIfNotExist=true&sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
 "SPRING_DATASOURCE_USERNAME": "ruleup", "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "15",
 "SPRING_DATA_REDIS_HOST": "redis", "SPRING_DATA_REDIS_PORT": "6379",
 "NOTIFICATION_QUEUE_REGION": "ap-northeast-2", "NOTIFICATION_QUEUE_URL": "$Q_NOTIFICATIONS", "MODERATION_QUEUE_URL": "$Q_MODERATION",
 "FCM_ENABLED": "true", "FCM_PROJECT_ID": "ruleup-5e41e",
 "IMAGE_STORAGE": "s3", "IMAGE_S3_BUCKET": "$BUCKET", "OPS_ALERT_TOPIC_ARN": "$TOPIC",
 "SPRINGDOC_API_DOCS_ENABLED": "false", "SPRINGDOC_SWAGGER_UI_ENABLED": "false",
 "MIN_APP_VERSION_ANDROID": "1.0.5", "MIN_APP_VERSION_CODE_ANDROID": "8",
 "MANAGEMENT_SERVER_PORT": "8081", "OTEL_ENABLED": "false",
 "JAVA_TOOL_OPTIONS": "-Xmx512m -XX:+ExitOnOutOfMemoryError",
}))
PY
)
aws $NEW ssm put-parameter --name /ruleup/prod/app-env --type String --tier Advanced --value "$APP_ENV" --overwrite >/dev/null
aws $NEW ssm put-parameter --name /ruleup/prod/deploy --type String --overwrite \
  --value "{\"image\":\"$OLD_IMAGE\",\"db_host\":\"$OLD_DB\"}" >/dev/null
# cloudflared·alloy 용 값. 터널 토큰은 터널을 만든 뒤 채운다(없으면 cloudflared 만 재시작을 반복한다).
python3 - <<'PY' | aws $NEW secretsmanager create-secret --name ruleup-prod/infra --secret-string file:///dev/stdin --tags Key=Project,Value=ruleup --query ARN --output text
import json, os
d = os.path.expanduser("~/.ruleup-migration/")
r = lambda f: open(d + f).read().strip()
tok = r("tunnel.token") if os.path.exists(d + "tunnel.token") else "PENDING"
print(json.dumps({"TUNNEL_TOKEN": tok, "GRAFANA_OTLP_ENDPOINT": r("grafana.otlp-endpoint"),
                  "GRAFANA_OTLP_USER": r("grafana.otlp-instance-id"), "GRAFANA_OTLP_TOKEN": r("grafana.token"),
                  "GRAFANA_LOKI_URL": r("grafana.loki-url"), "GRAFANA_LOKI_USER": r("grafana.loki-user")}))
PY

say "피어링 (새 → 기존)"
PCX=$(aws $NEW ec2 create-vpc-peering-connection --vpc-id "$VPC" --peer-vpc-id $OLD_VPC --peer-owner-id $OLD_ACC \
  --tag-specifications 'ResourceType=vpc-peering-connection,Tags=[{Key=Name,Value=ruleup-migration}]' \
  --query VpcPeeringConnection.VpcPeeringConnectionId --output text); put PCX "$PCX"
sleep 5
aws ec2 accept-vpc-peering-connection --vpc-peering-connection-id "$PCX" --query VpcPeeringConnection.Status.Code --output text
for rtb in "$RT_PUB" "$RT_PRI"; do
  aws $NEW ec2 create-route --route-table-id "$rtb" --destination-cidr-block 10.1.0.0/16 --vpc-peering-connection-id "$PCX" >/dev/null
done
# 앞선 시도(정지된 계정)의 경로·규칙이 남아 있을 수 있어, 경로는 바꿔 끼우고 규칙은 있으면 넘어간다.
aws ec2 replace-route --route-table-id $OLD_PRI_RT --destination-cidr-block 10.20.0.0/16 --vpc-peering-connection-id "$PCX" 2>/dev/null \
  || aws ec2 create-route --route-table-id $OLD_PRI_RT --destination-cidr-block 10.20.0.0/16 --vpc-peering-connection-id "$PCX" >/dev/null
aws ec2 authorize-security-group-ingress --group-id $OLD_RDS_SG --ip-permissions \
  'IpProtocol=tcp,FromPort=3306,ToPort=3306,IpRanges=[{CidrIp=10.20.0.0/16,Description=account-migration}]' >/dev/null 2>&1 \
  || echo "  (DB 보안 그룹 규칙은 이미 있음)"
# 새 DB 는 복제 때 기존 DB 로 나가고, 서버는 새 DB 로 들어온다(이미 허용). 반대로 기존 → 새 DB 접근은 열지 않는다.

say "기존 ECR 에 새 계정 pull 허용"
aws ecr set-repository-policy --repository-name ruleup-api --policy-text \
  "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"MigrationPull\",\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::$NEW_ACC:root\"},\"Action\":[\"ecr:BatchGetImage\",\"ecr:GetDownloadUrlForLayer\",\"ecr:BatchCheckLayerAvailability\"]}]}" >/dev/null

say "시작 템플릿 · ASG"
AMI=$(aws $NEW ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 --query Parameter.Value --output text)
UD=$(base64 < "$HERE/../ec2/user-data.sh" | tr -d '\n')
sleep 10   # 인스턴스 프로파일 전파
aws $NEW ec2 create-launch-template --launch-template-name ruleup-prod-api --launch-template-data "$(cat <<JSON
{"ImageId":"$AMI","InstanceType":"t4g.small","IamInstanceProfile":{"Name":"ruleup-prod-ec2"},
 "SecurityGroupIds":["$SG_EC2"],"UserData":"$UD",
 "BlockDeviceMappings":[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3","Encrypted":true,"DeleteOnTermination":true}}],
 "MetadataOptions":{"HttpTokens":"required","HttpPutResponseHopLimit":2,"HttpEndpoint":"enabled"},
 "CreditSpecification":{"CpuCredits":"unlimited"},
 "TagSpecifications":[{"ResourceType":"instance","Tags":[{"Key":"Name","Value":"ruleup-prod-api"},{"Key":"Project","Value":"ruleup"},{"Key":"Env","Value":"prod"}]},
                      {"ResourceType":"volume","Tags":[{"Key":"Project","Value":"ruleup"}]}]}
JSON
)" --query LaunchTemplate.LaunchTemplateId --output text
aws $NEW autoscaling create-auto-scaling-group --auto-scaling-group-name ruleup-prod-api \
  --launch-template 'LaunchTemplateName=ruleup-prod-api,Version=$Latest' \
  --min-size 1 --max-size 1 --desired-capacity 1 --vpc-zone-identifier "$PUB_A,$PUB_C" \
  --health-check-type EC2 --health-check-grace-period 600 \
  --tags Key=Project,Value=ruleup,PropagateAtLaunch=false
say "끝"
# 되돌리기(기존 계정):
#   aws ec2 revoke-security-group-ingress --group-id sg-06c394aa4581c42c4 --protocol tcp --port 3306 --cidr 10.20.0.0/16
#   aws ec2 delete-route --route-table-id rtb-0e6dfaa3d7588bc53 --destination-cidr-block 10.20.0.0/16
#   aws ec2 delete-vpc-peering-connection --vpc-peering-connection-id <PCX>
#   aws ecr delete-repository-policy --repository-name ruleup-api
