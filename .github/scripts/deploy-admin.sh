#!/usr/bin/env bash
# 관리자 API 서비스 배포 — 공개 API 와 따로 배포·롤백한다.
#
#   deploy-admin.sh <stg|prod> <image-uri>
#
# 태스크 정의의 admin 컨테이너 이미지만 바꾼 새 리비전을 등록하고 서비스를 그 리비전으로 옮긴다.
# 롤백은 같은 스크립트에 이전 커밋 이미지를 주거나(deploy-admin.yml 수동 실행), 이전 리비전으로 update-service 한다.
# 어떤 경우에도 관리자 API 는 공개 ALB 에 붙지 않는다 — 이 서비스에는 로드밸런서가 없다.
set -euo pipefail

ENV_NAME="$1"
IMAGE="$2"
CLUSTER="ruleup-${ENV_NAME}-cluster"
SERVICE="ruleup-${ENV_NAME}-admin"
FAMILY="ruleup-admin-${ENV_NAME}"

# 서비스가 없거나(프로비저닝 전) 배포 역할에 아직 관리자 서비스 권한이 없으면 건너뛴다 — 공개 API 배포를 붉게 만들지 않는다.
if ! aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
      --query 'services[?status==`ACTIVE`].serviceName' --output text 2>/dev/null | grep -q "$SERVICE"; then
  echo "::notice::${SERVICE} 가 아직 없다 — infra/admin-split/provision.py 를 먼저 적용한다. 건너뛴다."
  exit 0
fi

CURRENT=$(aws ecs describe-task-definition --task-definition "$FAMILY" --query taskDefinition --output json)
NEW=$(echo "$CURRENT" | jq --arg img "$IMAGE" '
  .containerDefinitions |= map(if .name == "admin" then .image = $img else . end)
  | {family, taskRoleArn, executionRoleArn, networkMode, containerDefinitions, volumes, placementConstraints,
     requiresCompatibilities, cpu, memory, runtimePlatform}
  | with_entries(select(.value != null))')
ARN=$(aws ecs register-task-definition --cli-input-json "$NEW" --query taskDefinition.taskDefinitionArn --output text)
echo "taskDefinition=$ARN"

DEPLOYMENT_ID=$(aws ecs update-service --cluster "$CLUSTER" --service "$SERVICE" --task-definition "$ARN" \
  --query 'service.deployments[?status==`PRIMARY`].id | [0]' --output text)
DESIRED=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" --query 'services[0].desiredCount' --output text)
if [ "$DESIRED" = "0" ]; then
  echo "::notice::desiredCount=0 — 태스크 정의만 갱신했다(전환 전 단계). 켜는 것은 README 의 전환 절차를 따른다."
  exit 0
fi

aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
for i in $(seq 1 60); do
  STATE=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
    --query "services[0].deployments[?id=='$DEPLOYMENT_ID'].rolloutState | [0]" --output text)
  [ "$STATE" != "IN_PROGRESS" ] && break
  sleep 10
done
echo "rolloutState=$STATE"
if [ "$STATE" != "COMPLETED" ]; then
  echo "::error::관리자 서비스 새 태스크가 뜨지 못해 이전 리비전으로 되돌려졌다(rolloutState=$STATE). CloudWatch /ecs/ruleup-${ENV_NAME}-admin 을 확인한다."
  exit 1
fi
