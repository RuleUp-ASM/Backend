#!/usr/bin/env bash
# 이 서버를 관리자 Lambda(private 서브넷, 공인 IP 없음)의 외부 출구(NAT)로 만든다. up.sh 가 부른다 — 재실행 안전.
#   · private 라우트 테이블의 기본 경로를 이 인스턴스로 돌린다(서버가 교체되면 새 서버가 자기 쪽으로 다시 돌린다)
#   · private 서브넷에서 온 패킷을 밖으로 내보낸다(MASQUERADE). Docker 가 FORWARD 를 기본 DROP 으로 두므로 DOCKER-USER 에 허용을 넣는다
#   · 내부 이름 ec2.ruleup.internal 을 이 서버의 사설 IP 로 맞춘다 — Lambda 가 Redis(redis-gw:6379)를 찾는 주소
# NAT 게이트웨이(월 ~45달러) 대신이다. 이 서버가 내려가 있는 동안은 관리자 콘솔만 멈춘다.
set -euo pipefail
export AWS_DEFAULT_REGION=ap-northeast-2
PRIVATE_RT=rtb-0bff0d49713be879a
PRIVATE_CIDRS="10.20.10.0/24 10.20.11.0/24"
ZONE_NAME=ruleup.internal

T=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')
md() { curl -fsS -H "X-aws-ec2-metadata-token: $T" "http://169.254.169.254/latest/meta-data/$1"; }
IID=$(md instance-id); IP=$(md local-ipv4)

aws ec2 modify-instance-attribute --instance-id "$IID" --no-source-dest-check
aws ec2 replace-route --route-table-id "$PRIVATE_RT" --destination-cidr-block 0.0.0.0/0 --instance-id "$IID" 2>/dev/null \
  || aws ec2 create-route --route-table-id "$PRIVATE_RT" --destination-cidr-block 0.0.0.0/0 --instance-id "$IID" >/dev/null

sysctl -qw net.ipv4.ip_forward=1
OUT=$(ip route show default | awk '{print $5; exit}')
for C in $PRIVATE_CIDRS; do
  iptables -t nat -C POSTROUTING -s "$C" -o "$OUT" -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s "$C" -o "$OUT" -j MASQUERADE
  iptables -C DOCKER-USER -s "$C" -j ACCEPT 2>/dev/null || iptables -I DOCKER-USER -s "$C" -j ACCEPT
  iptables -C DOCKER-USER -d "$C" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \
    || iptables -I DOCKER-USER -d "$C" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
done

ZONE=$(aws route53 list-hosted-zones-by-name --dns-name "$ZONE_NAME" --query "HostedZones[?Name=='$ZONE_NAME.'].Id | [0]" --output text)
aws route53 change-resource-record-sets --hosted-zone-id "$ZONE" --change-batch \
  "{\"Changes\":[{\"Action\":\"UPSERT\",\"ResourceRecordSet\":{\"Name\":\"ec2.$ZONE_NAME\",\"Type\":\"A\",\"TTL\":30,\"ResourceRecords\":[{\"Value\":\"$IP\"}]}}]}" >/dev/null
echo "nat: $IID $IP → $PRIVATE_RT, ec2.$ZONE_NAME"

# 재부팅하면 iptables 규칙이 사라진다 — 부팅 때마다 다시 넣는다(Docker 가 DOCKER-USER 체인을 만든 뒤).
if [ ! -f /etc/systemd/system/ruleup-nat.service ]; then
  cat > /etc/systemd/system/ruleup-nat.service <<'UNIT'
[Unit]
Description=RuleUp admin Lambda egress (NAT)
After=docker.service network-online.target
Requires=docker.service
[Service]
Type=oneshot
ExecStart=/opt/ruleup/nat.sh
[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload && systemctl enable ruleup-nat.service >/dev/null 2>&1
fi
