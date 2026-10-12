#!/usr/bin/env bash
# DB 계정 3종(ruleup_app·ruleup_admin·ruleup_migrator)을 만들고 권한을 맞춘다 — 재실행 안전.
#
#   bash infra/admin-split/db/apply_grants.sh stg
#
# RDS 는 사설망이라 로컬에서 붙을 수 없다. 같은 VPC 의 일회성 Fargate 태스크(ruleup-dbops-<env>, mysql 클라이언트)가
# 마스터 계정으로 grants.sql 을 실행한다.
#  · RunTask 요청(=CloudTrail)에는 GRANT 문만 실린다. 비밀번호는 Secrets Manager 에서 컨테이너로 주입되고
#    컨테이너 안에서만 SQL 에 들어간다.
#  · 출력은 /ecs/ruleup-<env>-admin 의 dbops/ 스트림 — SHOW GRANTS 결과(비밀번호 없음)로 확인한다.
# 사전: provision.py <env> --apply (network·iam·secrets·logs·ecs)
set -euo pipefail

ENV_NAME="${1:?stg|prod}"
HERE="$(cd "$(dirname "$0")" && pwd)"
python3 "$HERE/render_grants.py" --check

case "$ENV_NAME" in
  stg)  SUBNETS="subnet-0fcd4ba3b49d1c1f0,subnet-0abeb77bcb1454295"; VPC="vpc-0ef4c35f726c26f91" ;;
  prod) SUBNETS="subnet-0409244069c5d17c0,subnet-07468be31ddf92006"; VPC="vpc-0a7005d07813b0a5a" ;;
  *) echo "unknown env"; exit 2 ;;
esac
export AWS_REGION=ap-northeast-2 AWS_PAGER=""
SG=$(aws ec2 describe-security-groups --filters Name=vpc-id,Values="$VPC" Name=group-name,Values="ruleup-${ENV_NAME}-admin-sg" \
  --query 'SecurityGroups[0].GroupId' --output text)

# 운영 DB 는 TLS 로만 접속하게 계정에 REQUIRE SSL 을 건다. JDBC 는 sslMode=REQUIRED 로 붙는다.
SQL_B64=$(grep -v '^--' "$HERE/grants.sql" | grep -v '^$' \
  | sed -e 's/__SCHEMA__/RuleUp7/g' -e 's/__REQUIRE_SSL__/REQUIRE SSL/g' | base64 | tr -d '\n')
if [ "${#SQL_B64}" -gt 7000 ]; then
  echo "grants.sql 이 RunTask 재정의 한도(8KB)에 가깝다 — 압축 전달로 바꿔야 한다"; exit 1
fi

TASK=$(aws ecs run-task --cluster "ruleup-${ENV_NAME}-cluster" --task-definition "ruleup-dbops-${ENV_NAME}" \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=DISABLED}" \
  --overrides "{\"containerOverrides\":[{\"name\":\"dbops\",\"environment\":[{\"name\":\"SQL_B64\",\"value\":\"$SQL_B64\"}]}]}" \
  --query 'tasks[0].taskArn' --output text)
echo "dbops task=$TASK"
aws ecs wait tasks-stopped --cluster "ruleup-${ENV_NAME}-cluster" --tasks "$TASK"
CODE=$(aws ecs describe-tasks --cluster "ruleup-${ENV_NAME}-cluster" --tasks "$TASK" --query 'tasks[0].containers[0].exitCode' --output text)
echo "exitCode=$CODE"
aws logs tail "/ecs/ruleup-${ENV_NAME}-admin" --log-stream-name-prefix dbops --since 15m | tail -60
[ "$CODE" = "0" ]
