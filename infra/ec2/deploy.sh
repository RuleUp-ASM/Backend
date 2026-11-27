#!/usr/bin/env bash
# 블루그린 배포: 쉬는 색에 새 이미지를 띄우고, 헬스가 통과하면 프록시를 넘긴 뒤 옛 색을 내린다.
#   deploy.sh <이미지 URI>     실패하면 프록시는 그대로이고 새 컨테이너만 지운다.
set -euo pipefail
export AWS_DEFAULT_REGION=ap-northeast-2
IMAGE=${1:?이미지 URI}
cd /opt/ruleup
ACTIVE=$(cat run/active 2>/dev/null || echo blue)
[ "$ACTIVE" = blue ] && NEXT=green || NEXT=blue
UP=$(echo "$NEXT" | tr a-z A-Z)
log() { echo "[$(date +%T)] $*"; }

/opt/ruleup/render-env.sh >/dev/null
sed -i "s|^IMAGE_${UP}=.*|IMAGE_${UP}=${IMAGE}|" .env
aws ecr get-login-password | docker login --username AWS --password-stdin "${IMAGE%%/*}" >/dev/null
log "pull $IMAGE"
docker compose --profile "$NEXT" pull -q "api-$NEXT"
log "start api-$NEXT (active=$ACTIVE)"
docker compose --profile "$NEXT" up -d --no-deps "api-$NEXT"

ok=0
for i in $(seq 1 90); do      # 최대 7분 30초
  if docker compose exec -T caddy wget -qO- -T 3 "http://api-$NEXT:8081/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then ok=1; break; fi
  if [ "$(docker inspect -f '{{.State.Running}}' "ruleup-api-$NEXT-1" 2>/dev/null)" != true ]; then break; fi
  sleep 5
done
if [ $ok != 1 ]; then
  log "FAILED: api-$NEXT 가 헬스를 통과하지 못했다 — 프록시는 $ACTIVE 그대로"
  docker compose --profile "$NEXT" logs --tail 60 "api-$NEXT" || true
  docker compose --profile "$NEXT" rm -sf "api-$NEXT" >/dev/null
  exit 1
fi
log "healthy — 워밍업 20s"
sleep 20
sed "s/__TARGET__/api-$NEXT:8080/g" upstream.caddy.tmpl > run/upstream.caddy
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | tail -1
echo "$NEXT" > run/active
log "switched → $NEXT. 옛 색 요청이 끝나길 15s 기다린다"
sleep 15
docker compose --profile "$ACTIVE" rm -sf "api-$ACTIVE" >/dev/null
# 다음에 서버가 새로 뜰 때 같은 이미지로 올라오게 기록한다.
DEPLOY=$(aws ssm get-parameter --name /ruleup/prod/deploy --query Parameter.Value --output text | python3 -c 'import sys,json; d=json.load(sys.stdin); d["image"]=sys.argv[1]; print(json.dumps(d))' "$IMAGE")
aws ssm put-parameter --name /ruleup/prod/deploy --type String --overwrite --value "$DEPLOY" >/dev/null
docker image prune -af --filter "until=72h" >/dev/null 2>&1 || true
log "done — active=$NEXT image=$IMAGE"
free -m | sed -n 2,3p
