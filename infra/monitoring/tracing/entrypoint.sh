#!/bin/sh
# OTEL_ENABLED=true 일 때만 ADOT 에이전트를 붙인다. 값이 없거나 다른 값이면 예전과 똑같이 뜬다 —
# 에이전트 문제로 기동이 막히면 태스크 정의에서 이 값만 내리면 된다.
# 태스크 정의의 command(일회성 태스크 인자 — 예: 심사 계정 시드 --mode=create)는 "$@" 로 그대로 넘긴다.

# Java4Shell(CVE-2026-114938): 패치 전 JRE(25.0.5.1 미만)는 AVX-512 VBMI 를 지원하는 x64 CPU 에서
# Base64 인트린직이 메모리를 깨뜨린다. 지금 도는 Fargate arm64 는 조건에 해당하지 않지만, 이 이미지가
# x64(EC2·Lambda·일회성 태스크)에서 뜨는 순간 인증 전 요청(JWT 디코딩)으로 닿는다.
# 옵션을 태스크 정의 env 가 아니라 여기 두는 이유 — 어디서 띄우든 이미지와 함께 따라가야 한다.
# Dockerfile.deploy 의 JRE 를 25.0.5.1 이상으로 올리면 이 옵션은 지워도 된다.
JVM_HARDENING="-XX:-UseBASE64Intrinsics"

if [ "$OTEL_ENABLED" = "true" ]; then
  exec java $JVM_HARDENING -javaagent:/app/otel/aws-opentelemetry-agent.jar -jar /app/app.jar "$@"
fi
exec java $JVM_HARDENING -jar /app/app.jar "$@"
