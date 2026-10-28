#!/bin/bash
# ALB 대상 그룹 slow start — 새로 붙은 태스크에 트래픽을 N초에 걸쳐 0 → 정상 비율로 늘린다.
#
# 롤링 배포에서 새 태스크가 헬스체크를 넘긴 뒤 옛 태스크가 빠지기까지 약 1분 20초가 겹친다(prod 10/23 실측).
# 그 사이 새 태스크가 한꺼번에 제 몫을 받지 않게 60초로 잡는다. 옛 태스크가 빠진 뒤에는 slow start 중이어도
# 남은 대상이 트래픽을 다 받는다 — 그래서 겹침보다 길게 잡아도 효과가 없다.
# JVM 을 미리 데우는 것은 앱의 StartupWarmup 이 맡는다. 둘은 보완 관계다.
#
# 사용: bash infra/alb/apply_slow_start.sh <stg|prod> [초=60]   (0 이면 끈다)
set -euo pipefail
ENV="${1:?stg 또는 prod}"; SECS="${2:-60}"; R=ap-northeast-2
case "$ENV" in stg|prod) ;; *) echo "env 는 stg 또는 prod" >&2; exit 1 ;; esac
TG=$(aws elbv2 describe-target-groups --region "$R" --names "ruleup-${ENV}-tg" --query 'TargetGroups[0].TargetGroupArn' --output text)
echo "before: $(aws elbv2 describe-target-group-attributes --region "$R" --target-group-arn "$TG" \
  --query 'Attributes[?Key==`slow_start.duration_seconds`].Value' --output text)s"
aws elbv2 modify-target-group-attributes --region "$R" --target-group-arn "$TG" \
  --attributes "Key=slow_start.duration_seconds,Value=${SECS}" >/dev/null
echo "after:  $(aws elbv2 describe-target-group-attributes --region "$R" --target-group-arn "$TG" \
  --query 'Attributes[?Key==`slow_start.duration_seconds`].Value' --output text)s  (ruleup-${ENV}-tg)"
