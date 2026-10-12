# 인프라 · 모니터링 구조

노션 「RuleUp 모니터링」의 구성을 그림으로 옮긴 것. prod 기준이고 stg 도 모양은 같다(태스크 1개, P0 긴급 채널 없음, 사용자 지표 없이 `ruleup-stg-ops`·`ruleup-stg-observe`).
감시(계산기·자원 감시기·외부 점검)는 API 와 다른 리전(us-east-1)의 Scheduler+Lambda 에서 돈다 — 서울 API 가 멈춰도 감시가 같이 멈추지 않게.
**수용 리스크**: us-east-1 장애면 SLO·EDGE·NATIVE-01 경보 전달이 함께 사라질 수 있다. 그때 남는 것은 서울 NATIVE-02 와 (계약 후) 외부 watchdog 이다.

> 트레이스는 2026-10-05 에 stg·prod 모두 켰다(`tracing/enable_tracing.py`). Application Signals 는 조직 SCP 가 막아 X-Ray 로 보낸다.
> 끄기: `python3 infra/monitoring/tracing/enable_tracing.py <env> --disable`
> 샘플링: `/api/*` 전부 · 헬스체크 0 · 그 밖(배치·지표 전송·고아 DB 쿼리) 1%. 배치 트레이스는 이름이 `LockableRunnable.run` 으로만 보인다(ShedLock 래퍼).

```mermaid
flowchart LR
  app[Android 앱] -->|HTTPS| cf[Cloudflare] --> alb[ALB ruleup-prod-alb]
  subgraph seoul[서울 ap-northeast-2]
    subgraph ecs[ECS Fargate ruleup-prod-api · 태스크 2~6]
      api[api 컨테이너<br/>Spring Boot + ADOT 에이전트<br/>SliFilter · OpsSignals · 분 버퍼]
      col[otel-collector 사이드카]
      api -->|OTLP 스팬| col
    end
    alb --> api
    api --> rds[(RDS MySQL)]
    api --> redis[(ElastiCache Redis)]
    api --> sqs[[SQS 알림·심사 + DLQ]]
    cwm[CloudWatch 지표<br/>RuleUp/SLI·Ops·DB·Traffic·Security<br/>RuleUp/App/prod · AWS/*]
    cwl[CloudWatch Logs /ecs/ruleup-prod-api]
    a2{P1 경보 · NATIVE-02}
    sns1[SNS ruleup-prod-alerts / p0-alerts]
  end
  api -->|PutMetricData 매분, 0 포함| cwm
  api -->|stdout| cwl
  col --> xr[X-Ray]
  cwm --> a2 --> sns1

  subgraph virginia[중앙 감시 us-east-1]
    sch[Scheduler 1분] --> slo[λ slo-calculator<br/>SLO-01~04·LAT·API-01/02 Flag<br/>버짓 · 이상 탐지]
    sch --> res[λ resource-monitor<br/>ECS·RES-01~04·RDS·백업]
    probe2[λ edge-probe 버지니아]
    cwv[CloudWatch RuleUp/SLO·Edge·Observe·Ops CriticalCapacityFlag]
    a1{P0: SLO·API-01·EDGE-01·NATIVE-01·RES<br/>P1: 하트비트·TLS·EDGE-02}
    r53[Route 53 헬스체크 /health/public]
    sns2[SNS us-east-1]
  end
  probe1[λ edge-probe 오리건] -->|ProbeFailure| cwv
  probe1 & probe2 -->|GET /health/public| cf
  r53 --> cf
  slo -->|GetMetricData 72h| cwm
  res -->|Describe* · GetMetricData| seoul
  slo & res & probe2 --> cwv --> a1
  r53 --> a1 --> sns2

  sns1 & sns2 --> q[Amazon Q] --> s1[Slack #ruleup-alert]
  a1 & a2 -.P0 상태 변경.-> eb[EventBridge P0 규칙 서울·버지니아] --> q0[Amazon Q] --> s0[Slack #ruleup-alert-urgent 멘션]
  sns1 & sns2 -.전달 실패 OBS-02.-> fb[SNS ruleup-alert-fallback-email] --> mail[이메일]
  gha[GitHub Actions 배포] -->|사전 점검 G04| cwm & cwv
```

## 장애 확인 흐름 (노션 4절)

| 단계 | 어디서 |
|---|---|
| 1. 감지 | CloudWatch 경보 (`alerting/catalog.py` → `p0_p1.py`) — 감시망 자체는 `ruleup-<env>-observe` |
| 2. 전달 | P0 → 긴급 채널 멘션 + 운영 채널 / P1 → 운영 채널 |
| 3. 영향 범위 | 대시보드 `ruleup-prod-ops`(서버) · `ruleup-prod-business`(사용자) |
| 4. 병목 구간 | X-Ray 트레이스(CloudWatch 콘솔 → X-Ray traces, 그룹 `ruleup-<env>-api` 를 고르면 API 만) — 서비스 `ruleup-api-<env>`, API 는 주석 `http_route`·`http_request_method`·`http_response_status_code` 로 거른다. 요청 하나의 API → DB/Redis → 외부 API 구간별 소요 |
| 5. 원인 | CloudWatch Logs `/ecs/ruleup-<env>-api`(Logs Insights·Live Tail) — 로그 줄의 `[requestId traceId]` 로 트레이스와 잇는다. traceId `abcd1234…`(32자) 는 X-Ray 에서 `1-abcd1234-…` |

대시보드 `ruleup-<env>-ops` 상단에 로그·트레이스 바로가기가 있고, 맨 아래에 최근 ERROR 로그 50줄이 있다.
