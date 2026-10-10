#!/usr/bin/env bash
# 스키마 마이그레이션 일회성 태스크 — 서비스 갱신 전에 돌린다. 실패하면 서비스를 건드리지 않고 끝난다.
#
#   run-migration.sh <stg|prod> <image-uri>
#
# DDL 권한 계정(ruleup_migrator)은 이 태스크에만 있다. 공개 API·관리자 서비스는 DDL 없이 뜬다.
# 저장소 변수 MIGRATION_TASK_ENABLED=true 일 때만 워크플로가 부른다(README「마이그레이션 분리」).
set -euo pipefail

ENV_NAME="$1"
IMAGE="$2"
CLUSTER="ruleup-${ENV_NAME}-cluster"
FAMILY="ruleup-migrate-${ENV_NAME}"

case "$ENV_NAME" in
  stg)  SUBNETS="subnet-0fcd4ba3b49d1c1f0,subnet-0abeb77bcb1454295"; VPC="vpc-0ef4c35f726c26f91" ;;
  prod) SUBNETS="subnet-0409244069c5d17c0,subnet-07468be31ddf92006"; VPC="vpc-0a7005d07813b0a5a" ;;
  *) echo "unknown env $ENV_NAME"; exit 2 ;;
esac
SG=$(aws ec2 describe-security-groups --filters Name=vpc-id,Values="$VPC" Name=group-name,Values="ruleup-${ENV_NAME}-admin-sg" \
  --query 'SecurityGroups[0].GroupId' --output text)

CURRENT=$(aws ecs describe-task-definition --task-definition "$FAMILY" --query taskDefinition --output json)
NEW=$(echo "$CURRENT" | jq --arg img "$IMAGE" '
  .containerDefinitions |= map(.image = $img)
  | {family, taskRoleArn, executionRoleArn, networkMode, containerDefinitions, requiresCompatibilities, cpu, memory, runtimePlatform}
  | with_entries(select(.value != null))')
ARN=$(aws ecs register-task-definition --cli-input-json "$NEW" --query taskDefinition.taskDefinitionArn --output text)

TASK=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$ARN" --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text)
echo "migration task=$TASK"
aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$TASK"
CODE=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" --query 'tasks[0].containers[0].exitCode' --output text)
echo "exitCode=$CODE"
if [ "$CODE" != "0" ]; then
  echo "::error::마이그레이션 실패 — 서비스는 갱신하지 않는다. CloudWatch /ecs/ruleup-${ENV_NAME}-admin (migrate/) 를 확인한다."
  exit 1
fi
