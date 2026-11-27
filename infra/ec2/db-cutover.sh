#!/usr/bin/env bash
# DB 를 복제 대상(새 RDS)으로 넘긴다. 앱 컨테이너를 잠깐 멈춰 들어오는 요청을 붙잡아 두고(실패가 아니라 지연),
# 복제가 끝까지 따라온 것을 확인한 뒤 dbproxy 만 새 대상으로 다시 띄운다.
#   db-cutover.sh <새 DB 호스트>          --dry-run 을 붙이면 사전 점검만 한다.
# 전제: 이 서버 말고는 기존 DB 에 쓰는 것이 없어야 한다(기존 ECS 서비스 0).
# 실패 시: 복제를 끊기 전이면 앱을 그대로 풀어 기존 DB 로 계속 간다.
set -euo pipefail
NEW_DB=${1:?새 DB 호스트}; DRY=${2:-}
cd /opt/ruleup
A=$(cat run/active); C=ruleup-api-$A-1
docker rm -f dbtool >/dev/null 2>&1 || true
docker run -d --name dbtool --network host -v /opt/ruleup/run/db:/cnf:ro mysql:8.4 sleep 900 >/dev/null
m() { docker exec -i dbtool mysql --defaults-extra-file=/cnf/$1.cnf -N -B -e "$2" 2>/dev/null; }
st() { docker exec -i dbtool mysql --defaults-extra-file=/cnf/new.cnf -e 'show replica status\G' 2>/dev/null | awk -v k="$1:" '$1==k{print $2}'; }
src_pos() { m old 'show binary log status' | awk '{print $1":"$2}'; }
rep_pos() { echo "$(st Relay_Source_Log_File):$(st Exec_Source_Log_Pos)"; }
ts() { date +%T.%3N; }

echo "[$(ts)] precheck: io=$(st Replica_IO_Running) sql=$(st Replica_SQL_Running) lag=$(st Seconds_Behind_Source)s source=$(src_pos) replica=$(rep_pos)"
[ "$(st Replica_IO_Running)" = Yes ] && [ "$(st Replica_SQL_Running)" = Yes ] || { echo "복제가 돌고 있지 않다"; exit 1; }
[ "$(st Seconds_Behind_Source)" = 0 ] || { echo "복제 지연이 0 이 아니다"; exit 1; }
if [ "$DRY" = --dry-run ]; then docker rm -f dbtool >/dev/null; echo "dry-run 끝"; exit 0; fi

T0=$(date +%s.%N)
docker pause "$C" >/dev/null; echo "[$(ts)] app paused"
SWITCHED=0
trap '[ $SWITCHED = 1 ] || { docker unpause "$C" >/dev/null 2>&1; echo "중단 — 앱을 풀었다(기존 DB 그대로)"; }' EXIT
ok=0
for i in $(seq 1 40); do
  S=$(src_pos); R=$(rep_pos)
  if [ "$S" = "$R" ]; then ok=1; break; fi
  sleep 0.25
done
[ $ok = 1 ] || { echo "복제가 따라오지 못했다 source=$S replica=$R"; exit 1; }
echo "[$(ts)] caught up at $S"
m new 'call mysql.rds_stop_replication' >/dev/null
SWITCHED=1
sed -i "s|^DB_HOST=.*|DB_HOST=$NEW_DB|" .env
docker compose up -d --force-recreate --no-deps dbproxy >/dev/null 2>&1
docker unpause "$C" >/dev/null
T1=$(date +%s.%N); echo "[$(ts)] app resumed on $NEW_DB — held $(echo "$T1 - $T0" | bc)s"
m new 'call mysql.rds_reset_external_source' >/dev/null || true
export AWS_DEFAULT_REGION=ap-northeast-2
DEPLOY=$(aws ssm get-parameter --name /ruleup/prod/deploy --query Parameter.Value --output text | python3 -c 'import sys,json; d=json.load(sys.stdin); d["db_host"]=sys.argv[1]; print(json.dumps(d))' "$NEW_DB")
aws ssm put-parameter --name /ruleup/prod/deploy --type String --overwrite --value "$DEPLOY" >/dev/null
docker rm -f dbtool >/dev/null
sleep 3; curl -s -o /dev/null -w "health %{http_code}\n" http://127.0.0.1:8000/health/public
