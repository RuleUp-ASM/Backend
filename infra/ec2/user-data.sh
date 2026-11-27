#!/bin/bash
# 인스턴스 첫 기동: Docker·스왑을 준비하고 S3 의 구성 파일을 받아 스택을 올린다.
set -euxo pipefail
exec > >(tee /var/log/ruleup-bootstrap.log) 2>&1
dnf install -y docker python3
systemctl enable --now docker
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/download/v2.40.3/docker-compose-linux-aarch64" -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
# 배포 중 JVM 두 개가 겹치는 1~2분을 넘기기 위한 스왑.
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo 'vm.swappiness=10' > /etc/sysctl.d/99-ruleup.conf && sysctl --system >/dev/null
fi
ACC=$(aws sts get-caller-identity --query Account --output text)
mkdir -p /opt/ruleup/run
aws s3 sync "s3://ruleup-prod-config-$ACC/ec2/" /opt/ruleup/ --exclude 'run/*'
chmod +x /opt/ruleup/*.sh
/opt/ruleup/up.sh
