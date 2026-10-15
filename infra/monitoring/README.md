# 모니터링

노션 「모니터링 · 리뷰 요약」과 하위 문서(알람 카탈로그 · SLO 구현 계약 / 운영 경보 상세 / 장애 대응 · 사용자 조치 / 기술 부록)를
이 저장소로 옮긴 것. **경보 임계값의 원본은 `alerting/catalog.py`**, ID 별 진행 상태는 `alarm-catalog.yaml`, 리소스는 `env/<env>.json`.
노션 수치를 바꾸면 catalog.py·alarm-catalog.yaml 을 같이 고친다.

> 이 저장소 변경은 AWS 를 건드리지 않는다. 아래 스크립트는 IAM 관리자 자격으로 로컬에서 직접 돌린다(경보·대시보드 관례 그대로).
> 「경보가 만들어졌다」는 「실수신·오탐 검증(G01~G08)이 끝났다」가 아니다.

## 구성

| 층 | 위치 | 하는 일 |
|---|---|---|
| 앱 원천 | `observability/sli` · `observability/ops` · `observability/metric` | CUJ 10개 Operation 의 `RuleUp/SLI`(Eligible·Bad·LatencyBad·DurationMs, **0 포함 매분**), `RuleUp/Ops`(JVM·Hikari·Tomcat·하드 메모리·공급자 호출·토큰·캐시·불변식), `RuleUp/DB`(연결·락·데드락·장기 트랜잭션), `RuleUp/Traffic`(FLOW·4xx), `RuleUp/Security`. 태스크마다 1분 버퍼 → PutMetricData |
| 공개 점검 경로 | `GET /health/public` | 무인증·고정 본문 `ruleup-ok`·no-store. DB·Redis 를 묻지 않는다 |
| 감시 Lambda | `lambdas/` | 버지니아 `slo-calculator`(Rule Flag·버짓·이상 탐지) · `resource-monitor`(ECS·RES·RDS·백업), 오리건·버지니아 `edge-probe` |
| 경보 | `alerting/catalog.py` → `p0_p1.py` | 카탈로그 ID 그대로. P0 = SLO-01/02·API-01·EDGE-01·NATIVE-01/02·RES-01~04·DB-10/11·OBS-04. 1분 주기 평가, P0 는 충족 후 1~2분 안에 호출(10/15 리뷰) |
| 전달 | `alerting/routing.py` · `slack_channels.py` · `p0_mention.py` · `slack_fallback.py` · `security_events.py` | 서울·버지니아 토픽 → Amazon Q Slack, P0 멘션, 전달 실패 → 이메일, 보안 이벤트 |
| 대시보드 | `alerting/dashboard.py` · `observe_dashboard.py` · `business_dashboard.py` | `ruleup-<env>-ops`(OPS-1~6) · `ruleup-<env>-observe`(OBS-1~4) · `ruleup-prod-business` |
| 배포 보호 | `.github/scripts/monitoring_preflight.py` | ALARM·stale·EligibleCount 누락·계측 불일치·BudgetFreeze (기본 warn) |
| 재무장 | `lambdas/rearm.py` | 저트래픽 예외 — 독립 정상 증거가 있을 때만 P0 Flag=0 1회 + 감사 로그 |

## 적용 순서 (env 마다, stg 먼저)

1. **앱 배포** — 이 PR 이 stg 에 나가면 `RuleUp/SLI` 등이 쌓이기 시작한다. 태스크 역할에 새 네임스페이스 권한이 없으면 전송이 거절된다(로그 `metric_publish_failed`) → 2 의 `taskrole`.
2. `python3 infra/monitoring/lambdas/deploy.py stg` — IAM·Lambda·Scheduler·DLQ·태스크 역할 권한·Route 53 헬스체크. 출력된 헬스체크 ID 를 `env/stg.json` `route53_health_check_id` 에 적는다.
3. `python3 infra/monitoring/alerting/routing.py` — 버지니아 토픽·Slack·P0 멘션(한 번).
4. `python3 infra/monitoring/alerting/p0_p1.py stg --dry-run` 으로 목록 확인 → `p0_p1.py stg`. 옛 이름 경보는 새 경보를 만든 뒤 지운다.
5. `python3 infra/monitoring/alerting/slack_fallback.py` · `security_events.py`.
6. `python3 infra/monitoring/alerting/dashboard.py stg` · `observe_dashboard.py stg`.
7. stg 에서 G02·G03 재현(아래) → prod 에 같은 순서. 배포 역할에 `preflight-policy.json` 을 붙이고 하루 이상 warn 출력을 본 뒤 저장소 변수 `MONITORING_PREFLIGHT=enforce`.

권한 한 줄: `aws iam put-role-policy --role-name ruleup-<env>-gha-deploy --policy-name monitoring-preflight --policy-document file://infra/monitoring/preflight-policy.json`

## 켜는 순서가 정해진 것 (원장 `rules.*`)

| 플래그 | 기본 | 켜는 조건 |
|---|---|---|
| `res_enabled` | prod false · stg true | stg 에서 RES-01(마지막 정상 태스크 메모리)·RES-03(확장 불능)·RES-04(저장공간) 모의 P0 와 실수신 확인(G02). 꺼져 있어도 Flag 는 계산·발행된다(그림자 평가 — 대시보드 OPS-3) |
| `detectors_enabled` | false | 출시 D+14 — 밴드 학습·최소 표본 확인. 켜면 `deploy.py --only anomaly` 로 모델을 만든다 |
| `slo_rules` | prod all · stg SLO-01/API-01/API-02 | stg 는 시험에 필요한 규칙만(경보 수 과금) |

## 노션과 다르게 한 것 (이유)

- **분 버킷 DynamoDB 없음** — CloudWatch 가 1분 해상도를 15일 보관하고 GetMetricData 는 지표 개수로 과금돼, 72시간을 매분 다시 읽는 비용이 노션 추정(월 약 130만 조회 단위)과 같다. 30일 버짓은 1시간 해상도로 15분마다.
- **DB 수집기는 앱 안**(ShedLock 1분) — 서울 전용 VPC Lambda·읽기 계정 대신. 앱과 함께 멈추는 한계는 DB 지표가 전부 P1 이라 받아들인다. `information_schema.innodb_trx`·`INNODB_METRICS` 는 PROCESS 권한이 필요하다 — 없으면 db-collector 하트비트가 빠져 OBS-01 이 운다.
- **30초 간격 점검은 한 실행 안에서 두 번** — Scheduler 최소 주기가 1분이라 edge-probe 가 30초 쉬고 한 번 더 부른다(함수 timeout 50초). EDGE-01 leaf 는 「그 분 두 번 모두 실패」, EDGE-02 는 「2분(4회) 중 2회」 — 노션의 「3회 중 2회」와 같은 민감도.
- **RDS CPU·메모리 P0(DB-10/11)** — 10/15 리뷰 결정. 카탈로그 §4.2 는 「RDS CPU 99% 는 단독 P0 아님」이었다. 메모리는 resource-monitor 가 Freeable ≤RAM 5% 1분 + Swap 증가를 `DbMemoryCritical` Flag 로 낸다(CloudWatch 지연 탓에 실제 호출은 충족 후 2~3분).
- **OBS-04 는 CloudWatch 로 대신** — Healthchecks.io 미계약. slo-calculator·resource-monitor 하트비트 5분 누락이 P0. 버지니아 리전 전체 장애는 못 잡는다.
- **외부 점검은 도쿄 대신 오리건(us-west-2)** — 조직 SCP 가 아시아 리전(ap-northeast-1·3, ap-southeast-1)을 전부 막는다. 버지니아와 다른 리전이라 EDGE-01 의 「두 지점 독립 확인」은 그대로다.
- **점검 릴레이 함수 없음** — edge-probe 가 결과를 검증하고 버지니아로 직접 PutMetricData.
- **하드 메모리는 태스크가 직접 잰다**(ECS 메타데이터 v4) — Container Insights 태스크 지표 대신. TaskId 차원 없이 같은 시계열에 쓰고 Maximum/Minimum/SampleCount 로 「가장 찬·가장 여유 있는·보고한 태스크 수」를 본다.
- **EXT-01·TOKEN-01·EXT-02 는 경보 metric math**(5분 창·표본 게이트) — 별도 Flag 계산기 없이 같은 의미.
- **OR 조건은 행마다 경보를 따로**(Composite 과금 없이). EDGE-01 만 AND Composite.
- **사전 점검의 계측 불일치는 ALB 요청 수 대신 앱의 `RuleUp/Traffic ApiRequestCount`**(`/api/**`, 합성 점검 제외)와 비교 — ALB 는 경로를 못 나눠 Route 53·외부 점검의 `/health/public` 호출(분당 십수 건)이 섞이고, 트래픽이 적을 때 늘 「계측 누락」으로 걸렸다.
- **OPS-4 Redis 칸에 히트율** — 노션 §4.2 에는 없다. 경보 없이 화면에만. 앱 캐시(`@Cacheable`)는 Caffeine 이라 탐색 ZSET·rate limit 같은 Redis 직접 조회의 적중률이다.

## 아직 못 한 것 — 막는 것

| 항목 | 막는 것 | 그 전까지 |
|---|---|---|
| PagerDuty(G01·G03), OBS-03/05/06 | 계약·계정 없음 | P0 = Slack 긴급 채널 멘션. 원장 `sns.pagerduty` 에 토픽 이름을 넣으면 P0 경보에 붙는다 |
| OBS-04 외부 watchdog | Healthchecks.io 없음 | CloudWatch 하트비트 5분 누락 P0 로 대신(같은 리전 장애는 못 잡음). 원장 `healthchecks.obs04_ping_url` 이 생기면 계산기가 외부 ping 도 한다 |
| APP-01~04·ERR-01 (G06) | Crashlytics·Sentry 연결 미검증 | SLO·ERROR 로그 패널 |
| AUTH-01 | 지점별 격리 시험 계정·Secrets | C01 SLO(실사용 로그인) |
| COST-01/02 (G07) | 승인된 일 예산 없음 | — (LLM 호출 실패·지연은 EXT-01/02) |
| DB-09 | 노션: 확장 불가 확인 시만 | StorageMinutesToFull 은 이미 발행, DB-05·RES-04 |
| ECS deployment alarms(rollback) | 연결 경보는 서비스와 같은 리전이어야 한다 — SLO Flag 는 버지니아 | 사전 점검이 배포 전에 막는다. NATIVE-02 연결은 G04 리허설 후 |
| `ops/runbooks/*.sh`·`ops/releases/approved-rollback.json` | 노션 템플릿 원문이 이 저장소에 없다 | 노션 RB-01~06 절차 |

## G02·G03 재현 (stg)

- API-01: stg 에 2분간 같은 Operation 으로 요청 ≥20·실패 ≥10건(예: 계약 회귀 400 을 내는 잘못된 sync 본문) → `ruleup-stg-P0-API-01-sync` ALARM → 정상 요청 20건 이상 → Flag=0 → OK 2회.
- 입력 중단 유지: slo-calculator 스케줄을 끄면 P0 는 ALARM 그대로(ignore), OBS-01(slo-calculator) 이 2분 뒤, OBS-04(P0) 가 5분 뒤 운다.
- 저표본 재무장: `lambdas/rearm.py stg --operation sync --rule API-01 --incident … --approver … --evidence …`.
- EDGE-01: 한 지점만 실패 → EDGE-02 만, 두 지점 → EDGE-01.

---

# sync 연속 400 경보

`VerificationSyncService`는 같은 계정에서 봉투 검증이 3회 이상 연속 실패하면 `sync_envelope_rejected`를 WARN으로 남긴다. 정상 봉투가 들어오면 연속 횟수가 초기화된다. 인스턴스별 근사치이며 재시작/다중 인스턴스 분산에 걸친 연속 횟수는 합산하지 않는다.

CloudWatch 로그 필터는 일반 Spring 로그의 **WARN + 이벤트 이름**을 함께 검사한다. JSON 필터를 사용하면 시각·레벨 접두어 때문에 매치되지 않는다. 5분 동안 매치 1건 이상이면 `ruleup-stg-alerts` SNS 토픽(이메일·Slack #ops)에 경보를 보내고, 정상 복귀도 같은 채널에 알린다. 로그가 없으면 정상으로 취급한다. 사용자 ID는 메트릭 차원에 넣지 않는다.

`deploy-monitoring.yml`은 이 설정이 dev에 반영될 때와 수동 실행 때 적용한다. 앱 배포와 분리해 경보 설정 실패가 실행 중인 API 배포를 방해하지 않게 한다. 동일 이름의 필터·알람을 갱신하므로 재실행해도 중복 생성하지 않는다.

## 최초 권한 설정

현재 `ruleup-stg-gha-deploy` 역할에는 ECR/ECS 권한만 있다. IAM 관리 권한이 있는 계정에서 아래 정책을 **별도 inline 정책**으로 한 번 추가한 후 워크플로를 실행한다. 기존 `deploy-perms` 정책을 덮어쓰지 않는다.

```sh
aws iam put-role-policy --role-name ruleup-stg-gha-deploy \
  --policy-name sync-monitoring \
  --policy-document file://infra/monitoring/deploy-policy.json
```

## 검증·적용

```sh
# 읽기 전용: INFO 1·2회와 무관한 WARN은 제외하고 3·4회 WARN만 매치하는지 검사
python3 infra/monitoring/verify-sync-filter.py
# AWS 설정 변경: 서울 리전 stg 로그 그룹에 필터 및 SNS 경보 적용
bash infra/monitoring/apply-sync-alarm.sh
```

설정 적용 후 세 번 연속 잘못된 봉투를 보내는 QA 계정으로 경보와 SNS 수신을 확인한다. 실제 경보 발송은 수신자의 구독 상태에도 의존한다. 이 저장소 변경 자체는 IAM·CloudWatch 리소스를 수정하지 않는다.

문법 근거: [CloudWatch 텍스트 필터](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html), [PutMetricAlarm](https://docs.aws.amazon.com/cli/latest/reference/cloudwatch/put-metric-alarm.html).
