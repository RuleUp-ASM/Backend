#!/usr/bin/env bash
# 관리자 API Lambda(ruleup-prod-admin) 기반 — 새 계정(063450199872)에 로컬에서 실행한다(프로필 ruleup-new). 재실행 안전.
#   provision.sh net       보안 그룹 규칙 · 내부 DNS 영역 · 서버 역할 권한(NAT 경로·내부 이름 갱신)
#   provision.sh db        DB 계정 ruleup_admin (비밀번호는 Secrets Manager ruleup-prod/admin 에 만들고 서버에서 적용)
#   provision.sh function <이미지 URI>   실행 역할 · 함수 · 함수 URL
# Cloudflare Access 값(팀 도메인·AUD·운영자 이메일)은 환경변수 ACCESS_TEAM_DOMAIN · ACCESS_AUD · ACCESS_EMAILS 로 준다.
# 구성과 이유는 같은 폴더 README.md.
set -euo pipefail
export AWS_PAGER="" AWS_PROFILE=ruleup-new AWS_DEFAULT_REGION=ap-northeast-2
R=ap-northeast-2
ACC=$(aws sts get-caller-identity --query Account --output text)
VPC=vpc-0d64985eef7a21dfa
PRI_SUBNETS=subnet-092fde1e51ec94668,subnet-02442d9a00842061b
PRIVATE_RT=rtb-0bff0d49713be879a
SG_EC2=sg-03eb0ba1d7c23cb01
SG_LAMBDA=sg-07fa3bb1c3740c1a6       # ruleup-prod-admin-lambda (RDS SG 가 이미 3306 을 받아 준다)
DB_HOST=ruleup-prod-mysql.c3iyygkco1hp.ap-northeast-2.rds.amazonaws.com
FN=ruleup-prod-admin
MEMORY=512        # 계정 상한(2026-11 기준). 풀리면 2048 권장 — 기동이 몇 배 빨라진다
TIMEOUT=300       # 콜드 스타트(앱 기동)가 첫 요청 안에서 끝나야 한다
ROLE=ruleup-prod-admin-lambda
ZONE_NAME=ruleup.internal
HERE=$(cd "$(dirname "$0")" && pwd)

net() {
  # 서버가 Lambda 서브넷의 패킷을 받아 밖으로 넘긴다(443) + Redis 통로(6379). 다른 인바운드는 여전히 없다.
  for PORT in 443 6379; do
    aws ec2 authorize-security-group-ingress --group-id "$SG_EC2" --ip-permissions \
      "IpProtocol=tcp,FromPort=$PORT,ToPort=$PORT,UserIdGroupPairs=[{GroupId=$SG_LAMBDA,Description=admin-lambda}]" >/dev/null 2>&1 || true
  done
  ZONE=$(aws route53 list-hosted-zones-by-name --dns-name "$ZONE_NAME" --query "HostedZones[?Name=='$ZONE_NAME.'].Id | [0]" --output text)
  if [ "$ZONE" = None ]; then
    ZONE=$(aws route53 create-hosted-zone --name "$ZONE_NAME" --vpc "VPCRegion=$R,VPCId=$VPC" \
      --caller-reference "ruleup-$(date +%s)" --hosted-zone-config Comment=ruleup-internal,PrivateZone=true --query HostedZone.Id --output text)
  fi
  aws iam put-role-policy --role-name ruleup-prod-ec2 --policy-name ruleup-admin-egress --policy-document "{
    \"Version\":\"2012-10-17\",\"Statement\":[
      {\"Sid\":\"NatRoute\",\"Effect\":\"Allow\",\"Action\":[\"ec2:ReplaceRoute\",\"ec2:CreateRoute\"],\"Resource\":\"arn:aws:ec2:$R:$ACC:route-table/$PRIVATE_RT\"},
      {\"Sid\":\"NatSelf\",\"Effect\":\"Allow\",\"Action\":\"ec2:ModifyInstanceAttribute\",\"Resource\":\"arn:aws:ec2:$R:$ACC:instance/*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/Name\":\"ruleup-prod-api\"}}},
      {\"Sid\":\"InternalName\",\"Effect\":\"Allow\",\"Action\":\"route53:ChangeResourceRecordSets\",\"Resource\":\"arn:aws:route53:::${ZONE#/}\"},
      {\"Sid\":\"FindZone\",\"Effect\":\"Allow\",\"Action\":\"route53:ListHostedZonesByName\",\"Resource\":\"*\"}]}"
  echo "net: sg rules, zone $ZONE, server role policy ruleup-admin-egress"
}

db() {
  # 관리자 Lambda 전용 계정: 전 테이블 DML, DDL·GRANT 없음, 연결 상한 20(공개 API 풀을 먹지 못하게), TLS 필수.
  # 테이블 단위로 더 좁힌 목록은 infra/admin-split/db/admin-grants.txt 에 있으나 10/27 이후 기능(이의·회원 알림)이 빠져 있어
  # 지금은 쓰지 않는다 — 목록을 갱신·검증한 뒤 좁힌다.
  if ! aws secretsmanager describe-secret --secret-id ruleup-prod/admin >/dev/null 2>&1; then
    PW=$(aws secretsmanager get-random-password --exclude-punctuation --password-length 40 --query RandomPassword --output text)
    aws secretsmanager create-secret --name ruleup-prod/admin --secret-string "{\"DB_PASSWORD\":\"$PW\"}" >/dev/null
    unset PW
  fi
  SCRIPT=$(base64 < "$HERE/db-user.sh" | tr -d '\n')
  IID=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names ruleup-prod-api --query 'AutoScalingGroups[0].Instances[0].InstanceId' --output text)
  CID=$(aws ssm send-command --instance-ids "$IID" --document-name AWS-RunShellScript \
    --parameters "{\"commands\":[\"echo $SCRIPT | base64 -d > /tmp/db-user.sh && bash /tmp/db-user.sh < /dev/null; rm -f /tmp/db-user.sh\"]}" --query Command.CommandId --output text)
  aws ssm wait command-executed --command-id "$CID" --instance-id "$IID" || true
  aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" --query '[Status,StandardOutputContent,StandardErrorContent]' --output text
}

function_() {
  IMAGE=${1:?이미지 URI}
  if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
    aws iam create-role --role-name "$ROLE" --assume-role-policy-document \
      '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
    aws iam attach-role-policy --role-name "$ROLE" --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole
    sleep 10
  fi
  # 관리자가 하는 일만: 알림 큐에 넣기(전달은 공개 API 컨슈머), 미디어 읽기. 시크릿 조회·큐 수신·S3 쓰기 없음.
  aws iam put-role-policy --role-name "$ROLE" --policy-name app --policy-document "{
    \"Version\":\"2012-10-17\",\"Statement\":[
      {\"Effect\":\"Allow\",\"Action\":[\"sqs:SendMessage\",\"sqs:GetQueueUrl\",\"sqs:GetQueueAttributes\"],\"Resource\":\"arn:aws:sqs:$R:$ACC:ruleup-prod-notifications\"},
      {\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::ruleup-prod-media-$ACC/*\"}]}"

  # 환경변수 파일은 값이 화면·셸 기록에 남지 않게 파이썬이 임시 파일로 만든다.
  ENVF=$(mktemp); trap 'rm -f "$ENVF"' EXIT
  aws secretsmanager get-secret-value --secret-id ruleup-prod/app --query SecretString --output text > "$ENVF.app"
  aws secretsmanager get-secret-value --secret-id ruleup-prod/admin --query SecretString --output text > "$ENVF.admin"
  python3 - "$ENVF" "$DB_HOST" "$ACC" <<'PY'
import json, os, sys
out, db_host, acc = sys.argv[1:4]
app = json.load(open(out + ".app")); admin = json.load(open(out + ".admin"))
os.remove(out + ".app"); os.remove(out + ".admin")
env = {
    "SPRING_PROFILES_ACTIVE": "prod,admin",
    # Lambda Web Adapter: 이벤트를 localhost:8080 HTTP 로 바꿔 준다. 기동이 10초를 넘으므로 비동기 초기화.
    "AWS_LWA_PORT": "8080", "AWS_LWA_READINESS_CHECK_PATH": "/actuator/health", "AWS_LWA_ASYNC_INIT": "true",
    # 이 계정은 Lambda 메모리 상한이 512MB 다(새 계정 제한) — JVM 을 그 안에 맞춘다. 상한이 풀리면 MEMORY 와 함께 늘린다.
    "JAVA_TOOL_OPTIONS": "-Xmx176m -Xss512k -XX:MaxMetaspaceSize=180m -XX:ReservedCodeCacheSize=40m -XX:MaxDirectMemorySize=24m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError",
    "SPRING_DATASOURCE_URL": f"jdbc:mysql://{db_host}:3306/RuleUp7?sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
    "SPRING_DATASOURCE_USERNAME": "ruleup_admin",
    "SPRING_DATASOURCE_PASSWORD": admin["DB_PASSWORD"],
    "ADMIN_DB_POOL_SIZE": "3",
    # 한 인스턴스는 요청을 한 번에 하나만 받는다 — 스레드를 줄여 메모리를 아끼고, 빈은 처음 쓰일 때 만든다(기동 단축).
    "SERVER_TOMCAT_THREADS_MAX": "4", "SERVER_TOMCAT_THREADS_MIN_SPARE": "1",
    "SPRING_MAIN_LAZY_INITIALIZATION": "true", "SPRING_DATA_JPA_REPOSITORIES_BOOTSTRAP_MODE": "lazy",
    "SPRING_DATA_REDIS_HOST": "ec2.ruleup.internal", "SPRING_DATA_REDIS_PORT": "6379",
    "NOTIFICATION_QUEUE_URL": f"https://sqs.ap-northeast-2.amazonaws.com/{acc}/ruleup-prod-notifications",
    "NOTIFICATION_QUEUE_REGION": "ap-northeast-2",
    "IMAGE_STORAGE": "s3", "IMAGE_S3_BUCKET": f"ruleup-prod-media-{acc}",
    "METRICS_CLOUDWATCH_ENABLED": "false", "METRICS_OTLP_ENABLED": "false", "FCM_ENABLED": "false",
    "ADMIN_ACCESS_TEAM_DOMAIN": os.environ.get("ACCESS_TEAM_DOMAIN", ""),
    "ADMIN_ACCESS_AUDIENCES": os.environ.get("ACCESS_AUD", ""),
    "ADMIN_ACCESS_ALLOWED_EMAILS": os.environ.get("ACCESS_EMAILS", ""),
    "ADMIN_ACCESS_ALLOWED_ORIGINS": "https://admin.ruleup.co.kr",
}
for k in ("BAN_LIST_SALT", "ADMIN_STATS_EXCLUDED_USER_IDS", "REVIEW_ACCOUNT_IDS"):
    if app.get(k): env[k] = app[k]
json.dump({"Variables": env}, open(out, "w"))
PY
  if aws lambda get-function --function-name "$FN" >/dev/null 2>&1; then
    aws lambda update-function-code --function-name "$FN" --image-uri "$IMAGE" >/dev/null
    aws lambda wait function-updated-v2 --function-name "$FN"
    aws lambda update-function-configuration --function-name "$FN" --environment "file://$ENVF" --memory-size "$MEMORY" --timeout "$TIMEOUT" >/dev/null
  else
    aws lambda create-function --function-name "$FN" --package-type Image --code "ImageUri=$IMAGE" --architectures arm64 \
      --role "arn:aws:iam::$ACC:role/$ROLE" --memory-size "$MEMORY" --timeout "$TIMEOUT" --environment "file://$ENVF" \
      --vpc-config "SubnetIds=$PRI_SUBNETS,SecurityGroupIds=$SG_LAMBDA" >/dev/null
  fi
  aws lambda wait function-updated-v2 --function-name "$FN"
  aws logs create-log-group --log-group-name "/aws/lambda/$FN" 2>/dev/null || true
  aws logs put-retention-policy --log-group-name "/aws/lambda/$FN" --retention-in-days 30
  # 인스턴스는 하나만 둔다. Lambda 는 인스턴스당 요청을 한 번에 하나만 받으므로, 화면이 동시에 보낸 요청은 429 로 튕기고
  # AdminPage Worker 가 잠깐 뒤 다시 보낸다(요청 하나가 수십 ms 라 체감되지 않는다). 여러 개를 허용하면 동시 요청마다
  # 새 인스턴스가 90초씩 콜드 스타트를 한다. DB 연결 수와, 주소가 새어 나가 두드려질 때의 비용도 함께 묶인다.
  aws lambda put-function-concurrency --function-name "$FN" --reserved-concurrent-executions 1 >/dev/null
  warm
  # 함수 URL 은 공개(NONE)다. 문지기는 앱의 Cloudflare Access JWT 검증 — 서명·발급자·AUD·만료·허용 이메일이 맞지 않으면 401.
  if ! aws lambda get-function-url-config --function-name "$FN" >/dev/null 2>&1; then
    aws lambda create-function-url-config --function-name "$FN" --auth-type NONE >/dev/null
    aws lambda add-permission --function-name "$FN" --statement-id url --action lambda:InvokeFunctionUrl --principal '*' --function-url-auth-type NONE >/dev/null
    aws lambda add-permission --function-name "$FN" --statement-id url-invoke --action lambda:InvokeFunction --principal '*' --invoked-via-function-url >/dev/null 2>&1 || true
  fi
  aws lambda get-function-url-config --function-name "$FN" --query FunctionUrl --output text
}

warm() {
  # 4분마다 헬스 요청을 넣어 그 한 인스턴스를 깨워 둔다(유휴 회수·콜드 스타트 방지). 비용은 호출당 수 ms.
  SROLE=ruleup-prod-admin-warm
  if ! aws iam get-role --role-name "$SROLE" >/dev/null 2>&1; then
    aws iam create-role --role-name "$SROLE" --assume-role-policy-document \
      '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
    sleep 10
  fi
  aws iam put-role-policy --role-name "$SROLE" --policy-name invoke --policy-document \
    "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"lambda:InvokeFunction\",\"Resource\":\"arn:aws:lambda:$R:$ACC:function:$FN\"}]}"
  # 함수 URL 이 만드는 것과 같은 모양(HTTP API v2)의 이벤트 — 어댑터가 GET /actuator/health 로 바꿔 앱에 넘긴다.
  INPUT='{"version":"2.0","routeKey":"$default","rawPath":"/actuator/health","rawQueryString":"","headers":{"host":"warm.internal","user-agent":"ruleup-warm"},"requestContext":{"accountId":"anonymous","apiId":"warm","domainName":"warm.internal","domainPrefix":"warm","http":{"method":"GET","path":"/actuator/health","protocol":"HTTP/1.1","sourceIp":"127.0.0.1","userAgent":"ruleup-warm"},"requestId":"warm","routeKey":"$default","stage":"$default","time":"01/Jan/2026:00:00:00 +0000","timeEpoch":0},"isBase64Encoded":false}'
  TARGET=$(python3 -c 'import json,sys; print(json.dumps({"Arn":sys.argv[1],"RoleArn":sys.argv[2],"Input":sys.argv[3],"RetryPolicy":{"MaximumRetryAttempts":0}}))' \
    "arn:aws:lambda:$R:$ACC:function:$FN" "arn:aws:iam::$ACC:role/$SROLE" "$INPUT")
  if aws scheduler get-schedule --name ruleup-prod-admin-warm >/dev/null 2>&1; then OP=update-schedule; else OP=create-schedule; fi
  aws scheduler $OP --name ruleup-prod-admin-warm --schedule-expression 'rate(4 minutes)' \
    --flexible-time-window Mode=OFF --target "$TARGET" >/dev/null
}

case "${1:-}" in
  net) net ;;
  db) db ;;
  function) function_ "${2:-}" ;;
  *) echo "usage: provision.sh net|db|function <image>"; exit 2 ;;
esac
