# 부하·성능 테스트 (k6)

2026-10 부하테스트에서 쓴 시나리오. 결과·개선 내역은 노션 「부하테스트 및 성능개선」.

## 준비 (stg 만)

| 무엇 | 어떻게 |
|---|---|
| 로그인 우회 | 태스크 정의 env `OAUTH_LOAD_TEST_ENABLED=true` — IdP 호출만 빼고 실제 로그인 트랜잭션을 탄다(`LoadTestOAuthGate`, prod 프로파일엔 빈이 없다). `code=loadtest-<id>`, `codeVerifier=<DEV_TOKEN_SECRET>` |
| AI 초안 한도 | `APP_CHALLENGE_DRAFT_RATE_LIMIT_PER_MINUTE` (기본 10) |
| prod 와 같은 조건 | 태스크 0.5 vCPU/2GB·2대(오토스케일 2~4, CPU 60%·메모리 75%), `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60`, Hikari 15, RDS Multi-AZ, **DB 접속 `sslMode=REQUIRED`**(stg 기본 주소는 `useSSL=false` 라 그대로 재면 prod 보다 좋게 나온다) |
| Cloudflare 건너뛰기 | `-e ALB_IP=<stg ALB IP>` — TLS SNI·Host 는 도메인 그대로. 서버만 잰다 |

끝나면 반드시 되돌린다 — 우회 스위치 끄기, 스펙·Multi-AZ·오토스케일 원복.

## 실행

```sh
export LT_SECRET=...   # stg 시크릿 ruleup-stg/app 의 DEV_TOKEN_SECRET. 저장소에 두지 않는다
k6 run -e LT_SECRET=$LT_SECRET -e ALB_IP=3.37.221.3 -e RUN_ID=lt2 -e USERS=3000 -e SCENARIO=seed -e VUS=25 infra/loadtest/k6/ruleup.js
k6 run ... -e SCENARIO=seedjoin ...           # 가입·방 생성 뒤 남의 방 2곳 가입(사용자당 참여 방 3개 안팎)
k6 run ... -e SCENARIO=peak -e SCALE=1 -e DURATION=5m infra/loadtest/k6/ruleup.js
```

| SCENARIO | 내용 |
|---|---|
| `smoke.js` | 주요 API 를 한 번씩 — 계약 오류부터 거른다 |
| `seed` / `seedjoin` | 가짜 사용자 가입·공개 방 생성 / 남의 방 가입 |
| `login` | 아침 몰림 — 로그인 + 홈 + 리프레시, 초당 `RATE` |
| `browse` | 앱 열기·탐색(같은 정렬로 2페이지)·상세·내 방·마이페이지, 동시 `VUS` |
| `create` | 템플릿 초안 → 생성, 초당 `RATE` |
| `aidraft` | AI 초안(실제 LLM), 동시 `VUS` |
| `sync` | 건강정보 동의 → 인증 신호 업로드(backlog=true, 사용자당 10초 간격 — 429 는 정상 거절) |
| `peak` | 위를 실사용 비율로 섞음: 평상시(5분) → 피크(동시 400·로그인 15/s·sync 20/s·생성 3/s·가입 4/s, `DURATION`) → 마무리. `SCALE=2` 면 2배 |
| `breakpoint` | 동시 사용자를 200→2000 까지 2분씩 올리며 오류 5% 에서 멈춘다 |

임계값은 노션 SLO(로그인 3초·리프레시 1초·탐색/상세 1초·가입 1.5초·sync 3초·오늘/진행률 1초, p95)와 5xx 0건.

## 함정

- 시작일이 「내일」인 방을 대량으로 만들면 **자정에 한꺼번에 ACTIVE** 가 되어 그 뒤 측정의 데이터 모양이 바뀐다. 비교는 같은 데이터 상태에서 연달아 돈다.
- 같은 사용자 묶음으로 피크를 여러 번 돌리면 사용자당 참여 방이 계속 늘어난다(생성·가입). 비교마다 새 `RUN_ID` 로 시드하거나 순서를 붙여 돈다.
- Hikari `connection-timeout` 은 env 로 넣으면 「pool is sealed」로 기동 실패한다(풀이 먼저 시작됨).
- 비교 중에는 오토스케일 최대를 2로 묶어 2대에서 출발시키고, 시험 직전에 4로 푼다(앞 시험이 늘린 태스크가 남으면 출발점이 달라진다).
- 측정 중 로컬에서 gradle·docker 빌드를 돌리지 않는다(부하 발생기와 CPU 를 나눠 쓴다).
- 액세스 토큰은 30분이다. VU 가 오래 쥐는 세션은 `mine()` 이 25분마다 갱신한다 — 안 그러면 30분 넘는 시험 끝물이 전부 401 이다.
- 부하를 한 번 크게 받은 태스크는 힙을 다 커밋한 채 메모리 90% 에 머문다(누수 아님 — 장시간 시험에서 평평). 메모리 75% 오토스케일 정책은 그 뒤 CPU 가 한가해도 태스크를 늘린다. 태스크 수 비교는 새로 뜬 태스크에서 한다.
- 피크에서 X-Ray 전송이 계정 한도(429)에 걸리고 에이전트 스팬 큐가 넘친다 — `ruleup-api` 샘플링 규칙이 100% 면 그렇다.
