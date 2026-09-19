#!/usr/bin/env bash
set -euo pipefail
monitoring_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export AWS_PAGER=""
aws logs put-metric-filter --region ap-northeast-2 \
  --cli-input-json "file://${monitoring_dir}/sync-rejections-filter.json"
aws cloudwatch put-metric-alarm --region ap-northeast-2 \
  --cli-input-json "file://${monitoring_dir}/sync-rejections-alarm.json"
