# 관리자 API — Lambda (prod, 2026-11-27~)

관리자 API(`/api/v1/admin/**`)는 공개 API 와 **같은 이미지**를 실행 역할만 바꿔(`SPRING_PROFILES_ACTIVE=prod,admin`)
Lambda `ruleup-prod-admin` 으로 띄운다. 역할·인증·DB 계정을 나누는 설계의 근거는 `infra/admin-split/README.md` 그대로이고,
이 문서는 ECS 서비스 대신 Lambda 로 올린 구성만 적는다.

```
[운영자 브라우저] ─► Worker adminpage (admin.ruleup.co.kr) — 비밀번호 로그인
                                                    │  /api/* 를 그대로 넘긴다
                                                    ▼
                                    Lambda 함수 URL (ruleup-prod-admin, 인스턴스 1개)
                                    Lambda Web Adapter → Spring(role=admin, 비밀번호 진입 → 관리자 전용 토큰)
  ┌──────────── VPC private 서브넷(공인 IP 없음) ────────────┼──────────────────────────────┐
  │   SG ruleup-prod-admin-lambda (인바운드 0)               │                              │
  │      ├─► RDS 3306        계정 ruleup_admin (DML 만, 연결 상한 20, TLS)                   │
  │      ├─► ec2.ruleup.internal:6379   서버의 redis-gw 컨테이너 → Redis                     │
  │      └─► 0.0.0.0/0 → 서버(EC2)가 NAT   SQS·S3                                           │
  └─────────────────────────────────────────────────────────────────────────────────────────┘
```

## 결정과 이유

| 항목 | 결정 | 이유 |
|---|---|---|
| 패키징 | 공개 API 와 같은 컨테이너 이미지 + Lambda Web Adapter(`Dockerfile.deploy`) | 코드·이미지가 한 벌. 어댑터는 Lambda 밖에서는 읽히지 않는다 |
| 진입 | 함수 URL(인증 NONE) | 문지기는 앱이다. 토큰 없이는 401 이고 관리자 경로 외에는 매핑이 없다. Cloudflare 가 함수 URL 을 원본으로 삼으려면 Host 재작성(Enterprise)이 필요해 Worker 가 직접 부른다 |
| 로그인 | **비밀번호**(`ADMIN_LOGIN=passcode`) | 사용자 결정(2026-11-27) — Cloudflare Access 로그인 대신 예전처럼 비밀번호 하나. 토큰은 관리자 전용 키(`ADMIN_JWT_SECRET`)로 서명해 공개 API 토큰과 서로 통하지 않는다. 시도 제한은 10분 10회. Access 로 바꾸는 길은 남아 있다(`LOGIN=access`) |
| 외부 통신 | 서버(EC2)를 NAT 로(`infra/ec2/nat.sh`) | NAT 게이트웨이는 월 ~45달러. 관리자만 쓰는 길이라 서버 한 대에 얹는다. 서버가 교체되면 새 서버가 경로와 내부 이름을 자기 쪽으로 돌린다 |
| 동시 실행 | 예약 동시성 **1** + 4분마다 깨우기 | 아래 「콜드 스타트」 |
| DB 계정 | `ruleup_admin` — 전 테이블 DML, DDL·GRANT 없음 | 마스터 계정을 쓰지 않는다. 테이블 단위 목록(`infra/admin-split/db`)은 10/27 이후 기능이 빠져 있어, 갱신·검증 뒤에 좁힌다 |
| 시크릿 | 함수 환경변수(배포 시 `provision.sh` 가 Secrets Manager 에서 읽어 넣음) | 런타임에 시크릿을 조회하지 않는다. 공개 API 의 JWT·OAuth·FCM·Gemini 키는 들어가지 않는다 |
| 로그 | CloudWatch `/aws/lambda/ruleup-prod-admin`(30일) | 요청 한 줄 감사 로그(actor·경로·결과). Grafana 로는 보내지 않는다 |

## 콜드 스타트

이 계정은 Lambda 메모리 상한이 **512MB**(새 계정 제한)라 CPU 가 약 0.3 vCPU 다. 앱 기동이 70~120초 걸린다.
- 인스턴스를 **하나만** 두고(예약 동시성 1) EventBridge Scheduler `ruleup-prod-admin-warm` 이 4분마다 헬스 요청으로 깨워 둔다.
- Lambda 는 인스턴스당 요청을 한 번에 하나만 받는다. 화면이 동시에 보낸 요청과 깨어나는 중에 온 요청은 429 로 튕기고,
  AdminPage Worker 가 다시 보낸다(최대 ~90초). 동시성을 풀면 겹친 요청마다 새 인스턴스가 따로 콜드 스타트를 한다.
- 콜드 스타트가 실제로 보이는 때: 배포 직후(워크플로가 미리 한 번 깨운다) · 설정 변경 직후 · AWS 가 인스턴스를 회수한 직후.
- 메모리는 기동 때 최고 ~500MB 로 빠듯하다. JVM 옵션(`-Xmx176m` 등)과 지연 초기화로 맞춰 두었다.
- **상한이 풀리면**(AWS 지원 케이스) `provision.sh` 의 `MEMORY=2048`, JVM 옵션의 `-Xmx` 를 늘리고 지연 초기화를 빼면 기동이 20~30초대로 준다.

## 배포

- main 머지 → `deploy-prod.yml` 이 공개 API 를 올린 뒤 같은 이미지로 `update-function-code` 하고 헬스로 깨운다.
- 설정(환경변수·메모리) 변경: `bash infra/admin-lambda/provision.sh function <이미지 URI>`.
- **비밀번호 변경**: 시크릿 `ruleup-prod/app` 의 `ADMIN_PASSCODE` 를 바꾸고 위 명령을 다시 돌린다. 이미 로그인한 세션까지 끊으려면
  `ruleup-prod/admin` 의 `JWT_SECRET` 을 지우고 돌린다(새로 만들어진다).
- Cloudflare Access 로 바꾸려면: Access 앱을 만들고 `LOGIN=access ACCESS_TEAM_DOMAIN=… ACCESS_AUD=… ACCESS_EMAILS=a@x,b@y` 를 주고 다시 돌린다.
- DB 를 다른 인스턴스로 옮기면(`infra/ec2/db-cutover.sh`) `provision.sh` 의 `DB_HOST` 도 바꿔 다시 돌린다 — Lambda 는 dbproxy 를 거치지 않는다.

## 장애 대응

| 증상 | 볼 곳 |
|---|---|
| 콘솔이 「깨어나는 중」(503 BACKEND_WAKING) | 콜드 스타트가 90초를 넘겼다. 1분 뒤 새로고침. 반복되면 Lambda 로그의 기동 오류·메모리(`Max Memory Used`) |
| 로그인이 401 INVALID_PASSCODE | 비밀번호가 다르거나, 함수 env 의 `ADMIN_LOGIN` 이 passcode 가 아니다(그때는 비밀번호 진입이 닫혀 있다) |
| 429 TOO_MANY_ATTEMPTS | 10분에 10회 실패. 인스턴스가 바뀌면 초기화된다 |
| 기동이 DB 에서 멈춤·SQS 발행 실패 | 서버 교체 직후면 `nat.sh` 가 돌았는지(`systemctl status ruleup-nat`, private 라우트 테이블의 0.0.0.0/0 대상) |
| 제재·공지 뒤 Redis 관련 오류 | 서버의 `redis-gw` 컨테이너, 보안 그룹 6379 규칙, `ec2.ruleup.internal` 레코드 |

## 파일

- `provision.sh` — 네트워크 규칙·내부 DNS·서버 역할 권한(`net`), DB 계정(`db`, 서버에서 `db-user.sh` 실행), 함수·URL·깨우기 스케줄(`function`)
- `infra/ec2/nat.sh` · `redis-gw.cfg` · `docker-compose.yml`(redis-gw) — 서버 쪽
- `.github/workflows/deploy-prod.yml` — 「Deploy admin API (Lambda)」 단계
- AdminPage `worker/index.ts` — 요청 전달·429 재시도
