#!/usr/bin/env bash
# 새 계정(063450199872) 보안 알림 — GuardDuty · CloudTrail · EventBridge → SNS ruleup-prod-alerts → Slack. 재실행 안전.
#   bash infra/security/alerts.sh          (프로필 ruleup-new)
#
#   SEC-02 GuardDuty finding  심각도 4 이상 → #ruleup-alert, 7 이상은 긴급 채널에도
#   SEC-03 보안 그룹 규칙 변경 → #ruleup-alert
#   SEC-04 루트 계정 사용(콘솔 로그인·API 호출) · 콘솔 로그인 실패 → 긴급 채널에도
# 한계: 조직 SCP 가 us-east-1 의 EventBridge 를 막고 있어, 그 리전에만 떨어지는 이벤트(IAM 키·정책 변경, 글로벌 엔드포인트
#       콘솔 로그인)는 규칙을 걸 수 없다. IAM 쪽 이상 행위는 GuardDuty(IAM 분석 포함)가 서울에서 finding 으로 올린다.
# 탐지는 침해 확정이 아니다 — 승인된 변경(배포·이 저장소의 스크립트)도 그대로 알린다.
set -euo pipefail
export AWS_PAGER="" AWS_PROFILE=ruleup-new AWS_DEFAULT_REGION=ap-northeast-2
R=ap-northeast-2
ACC=$(aws sts get-caller-identity --query Account --output text)
TOPIC=arn:aws:sns:$R:$ACC:ruleup-prod-alerts
TRAIL=ruleup-management
TRAIL_BUCKET=ruleup-cloudtrail-$ACC

# 1) GuardDuty — 켜져 있지 않으면 켠다(30일 무료 뒤 사용량 과금, 이 규모에서 월 수 달러 이하).
DET=$(aws guardduty list-detectors --query 'DetectorIds[0]' --output text)
if [ "$DET" = None ]; then
  DET=$(aws guardduty create-detector --enable --finding-publishing-frequency FIFTEEN_MINUTES --query DetectorId --output text)
fi
echo "guardduty detector $DET"

# 2) CloudTrail — "AWS API Call via CloudTrail" 이벤트는 트레일이 있어야 EventBridge 로 온다. 관리 이벤트 첫 사본은 무료.
if ! aws s3api head-bucket --bucket "$TRAIL_BUCKET" 2>/dev/null; then
  aws s3api create-bucket --bucket "$TRAIL_BUCKET" --create-bucket-configuration LocationConstraint=$R >/dev/null
  aws s3api put-public-access-block --bucket "$TRAIL_BUCKET" \
    --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  aws s3api put-bucket-lifecycle-configuration --bucket "$TRAIL_BUCKET" --lifecycle-configuration \
    '{"Rules":[{"ID":"expire-365d","Status":"Enabled","Filter":{},"Expiration":{"Days":365}}]}'
fi
aws s3api put-bucket-policy --bucket "$TRAIL_BUCKET" --policy "{
  \"Version\":\"2012-10-17\",\"Statement\":[
   {\"Sid\":\"AclCheck\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"cloudtrail.amazonaws.com\"},\"Action\":\"s3:GetBucketAcl\",\"Resource\":\"arn:aws:s3:::$TRAIL_BUCKET\",
    \"Condition\":{\"StringEquals\":{\"aws:SourceArn\":\"arn:aws:cloudtrail:$R:$ACC:trail/$TRAIL\"}}},
   {\"Sid\":\"Write\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"cloudtrail.amazonaws.com\"},\"Action\":\"s3:PutObject\",\"Resource\":\"arn:aws:s3:::$TRAIL_BUCKET/AWSLogs/$ACC/*\",
    \"Condition\":{\"StringEquals\":{\"s3:x-amz-acl\":\"bucket-owner-full-control\",\"aws:SourceArn\":\"arn:aws:cloudtrail:$R:$ACC:trail/$TRAIL\"}}}]}"
if ! aws cloudtrail get-trail --name "$TRAIL" >/dev/null 2>&1; then
  # 다중 리전(글로벌 서비스 이벤트 포함)으로 만들어 IAM 변경도 기록에는 남긴다. SCP 가 막으면 서울 단일 리전으로.
  aws cloudtrail create-trail --name "$TRAIL" --s3-bucket-name "$TRAIL_BUCKET" --is-multi-region-trail --enable-log-file-validation >/dev/null 2>&1 \
    || aws cloudtrail create-trail --name "$TRAIL" --s3-bucket-name "$TRAIL_BUCKET" --include-global-service-events --enable-log-file-validation >/dev/null
fi
aws cloudtrail start-logging --name "$TRAIL"
aws cloudtrail get-trail --name "$TRAIL" --query 'Trail.[Name,IsMultiRegionTrail,IncludeGlobalServiceEvents]' --output text

# 3) EventBridge 가 토픽에 발행할 수 있게 한다.
python3 - "$TOPIC" <<'PY'
import json, subprocess, sys
topic = sys.argv[1]
def aws(*a): return subprocess.run(["aws", *a], check=True, capture_output=True, text=True).stdout
policy = json.loads(json.loads(aws("sns", "get-topic-attributes", "--topic-arn", topic, "--output", "json"))["Attributes"]["Policy"])
if not any((s.get("Principal") or {}).get("Service") == "events.amazonaws.com" for s in policy["Statement"]):
    policy["Statement"].append({"Sid": "EventBridge", "Effect": "Allow", "Principal": {"Service": "events.amazonaws.com"}, "Action": "SNS:Publish", "Resource": topic})
    aws("sns", "set-topic-attributes", "--topic-arn", topic, "--attribute-name", "Policy", "--attribute-value", json.dumps(policy))
PY

# 4) 규칙. 본문은 Slack 전달 Lambda(infra/lambda/slack-forwarder)가 읽는 모양이다 — urgent=true 면 긴급 채널에도 올린다.
rule() {  # 이름  패턴(JSON)  제목  urgent(true|false)  "키=JSONPath ..."
  local NAME=$1 PATTERN=$2 TITLE=$3 URGENT=$4; shift 4
  aws events put-rule --name "$NAME" --event-pattern "$PATTERN" --state ENABLED \
    --description "RuleUp security - infra/security/alerts.sh" >/dev/null
  python3 - "$NAME" "$TOPIC" "$TITLE" "$URGENT" "$@" <<'PY'
import json, subprocess, sys
name, topic, title, urgent, *fields = sys.argv[1:]
paths = dict(f.split("=", 1) for f in fields)
body = "\\n".join(f"{k}=<{k}>" for k in paths) + "\\n탐지는 침해 확정이 아니다 — 본인이 한 변경이면 무시한다"
template = '{"urgent":%s,"content":{"title":"%s","description":"%s"}}' % (urgent, title, body)
targets = [{"Id": "slack", "Arn": topic, "InputTransformer": {"InputPathsMap": paths, "InputTemplate": template}}]
subprocess.run(["aws", "events", "put-targets", "--rule", name, "--targets", json.dumps(targets)], check=True, capture_output=True)
print("rule", name)
PY
}
rule ruleup-SEC-02-guardduty '{"source":["aws.guardduty"],"detail-type":["GuardDuty Finding"],"detail":{"severity":[{"numeric":[">=",4,"<",7]}]}}' \
  ':shield: [SEC-02] GuardDuty finding' false 'type=$.detail.type' 'severity=$.detail.severity' 'title=$.detail.title' 'id=$.detail.id' 'updatedAt=$.detail.updatedAt'
rule ruleup-SEC-02-guardduty-high '{"source":["aws.guardduty"],"detail-type":["GuardDuty Finding"],"detail":{"severity":[{"numeric":[">=",7]}]}}' \
  ':rotating_light: [SEC-02] GuardDuty finding (높음)' true 'type=$.detail.type' 'severity=$.detail.severity' 'title=$.detail.title' 'id=$.detail.id' 'updatedAt=$.detail.updatedAt'
rule ruleup-SEC-03-sg-change '{"source":["aws.ec2"],"detail-type":["AWS API Call via CloudTrail"],"detail":{"eventSource":["ec2.amazonaws.com"],"eventName":["AuthorizeSecurityGroupIngress","AuthorizeSecurityGroupEgress","RevokeSecurityGroupIngress","RevokeSecurityGroupEgress","ModifySecurityGroupRules"]}}' \
  ':shield: [SEC-03] 보안 그룹 변경' false 'event=$.detail.eventName' 'actor=$.detail.userIdentity.arn' 'ip=$.detail.sourceIPAddress' 'time=$.detail.eventTime'
rule ruleup-SEC-04-root-activity '{"detail-type":["AWS API Call via CloudTrail","AWS Console Sign In via CloudTrail"],"detail":{"userIdentity":{"type":["Root"]}}}' \
  ':rotating_light: [SEC-04] 루트 계정 사용' true 'event=$.detail.eventName' 'source=$.detail.eventSource' 'ip=$.detail.sourceIPAddress' 'time=$.detail.eventTime'
rule ruleup-SEC-04-login-failed '{"detail-type":["AWS Console Sign In via CloudTrail"],"detail":{"responseElements":{"ConsoleLogin":["Failure"]}}}' \
  ':shield: [SEC-04] 콘솔 로그인 실패' false 'user=$.detail.userIdentity.arn' 'ip=$.detail.sourceIPAddress' 'time=$.detail.eventTime'
