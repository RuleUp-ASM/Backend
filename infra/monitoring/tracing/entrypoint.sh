#!/bin/sh
# OTEL_ENABLED=true 일 때만 ADOT 에이전트를 붙인다. 값이 없거나 다른 값이면 예전과 똑같이 뜬다 —
# 에이전트 문제로 기동이 막히면 태스크 정의에서 이 값만 내리면 된다.
if [ "$OTEL_ENABLED" = "true" ]; then
  exec java -javaagent:/app/otel/aws-opentelemetry-agent.jar -jar /app/app.jar
fi
exec java -jar /app/app.jar
