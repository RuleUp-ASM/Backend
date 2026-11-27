#!/usr/bin/env bash
# 저장소 infra/ec2 → S3 구성 버킷 → 서버 /opt/ruleup. 로컬에서 실행한다(프로필 ruleup-new).
#   sync-config.sh [서버에서 이어서 돌릴 명령]   예: sync-config.sh 'docker compose up -d alloy'
set -euo pipefail
export AWS_PAGER="" AWS_PROFILE=ruleup-new AWS_DEFAULT_REGION=ap-northeast-2
HERE=$(cd "$(dirname "$0")" && pwd)
ACC=$(aws sts get-caller-identity --query Account --output text)
aws s3 sync "$HERE/" "s3://ruleup-prod-config-$ACC/ec2/" --only-show-errors
IID=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names ruleup-prod-api --query 'AutoScalingGroups[0].Instances[0].InstanceId' --output text)
CMD="aws s3 sync s3://ruleup-prod-config-$ACC/ec2/ /opt/ruleup/ --exclude 'run/*' --only-show-errors && chmod +x /opt/ruleup/*.sh && cd /opt/ruleup && ${1:-true}"
P=$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]]}))' "$CMD")
CID=$(aws ssm send-command --instance-ids "$IID" --document-name AWS-RunShellScript --parameters "$P" --query Command.CommandId --output text)
aws ssm wait command-executed --command-id "$CID" --instance-id "$IID" || true
aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" --query '[Status,StandardOutputContent,StandardErrorContent]' --output text
