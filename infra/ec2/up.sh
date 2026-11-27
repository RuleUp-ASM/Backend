#!/usr/bin/env bash
# 서버가 새로 뜨거나 설정을 바꿨을 때: env 를 다시 만들고 전체 스택을 올린다.
# 앱은 지금 색(/opt/ruleup/run/active, 없으면 blue)만 올린다.
set -euo pipefail
export AWS_DEFAULT_REGION=ap-northeast-2
cd /opt/ruleup
mkdir -p run
/opt/ruleup/render-env.sh
ACTIVE=$(cat run/active 2>/dev/null || echo blue)
[ -f run/upstream.caddy ] || sed "s/__TARGET__/api-$ACTIVE:8080/g" upstream.caddy.tmpl > run/upstream.caddy
# 이미지가 있는 레지스트리에 로그인한다(.env 의 IMAGE_* 에서 읽는다).
for REG in $(grep -E '^IMAGE_' .env | cut -d= -f2 | cut -d/ -f1 | sort -u); do
  aws ecr get-login-password | docker login --username AWS --password-stdin "$REG" >/dev/null
done
docker compose --profile "$ACTIVE" up -d --remove-orphans
echo "$ACTIVE" > run/active
docker compose --profile "$ACTIVE" ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
