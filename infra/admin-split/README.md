# 관리자 API 분리 — 구성·권한·전환·복구

관리자 API(`/api/v1/admin/**`)를 공개 API 와 **다른 실행 환경·인증·DB 계정·네트워크 경로**로 나눈다.
같은 jar 를 역할(`app.runtime.role`)만 바꿔 띄운다 — 도메인 로직(제재·신고·문의·공지·통계 재계산)과
일관성 처리(커밋 후 SQS 발행, 아웃박스, Redis 투영)는 한 벌이다.

## 1. 구성도

```
[Android] ─► Cloudflare(WAF) ─► 공개 ALB ─► ruleup-<env>-api (public 서브넷, role=api)
                                  │            · /api/v1/admin/** 매핑 없음(404) + ALB 404 규칙(이중)
                                  │            · 배치·SQS 컨슈머(알림 FCM·심사)·아웃박스 스윕·공지 팬아웃
                                  └─ SG: Cloudflare IP 대역만(alb-lockdown 단계)

[운영자 브라우저] ─► Cloudflare Access(MFA) ─► Worker adminpage (admin.ruleup.co.kr, workers.dev 꺼짐)
                                                   │  /api/* → cf-access-token 으로 같은 신원 전달
                                                   ▼
                     Cloudflare Access(같은 앱) ─► Tunnel ─┐   admin-api(.stg).ruleup.co.kr
                                                           │   (outbound-only, 인바운드 포트 없음)
  ┌──────────────── VPC (private app 서브넷, 공인 IP 없음) ─┼────────────────────────────────┐
  │  ruleup-<env>-admin 태스크 (FARGATE_SPOT, role=admin)  ▼                                │
  │   ├ cloudflared 사이드카 ──localhost:8080──► admin 앱 ── Access JWT 검증·허용 목록·Origin    │
  │   └ SG: 인바운드 0 / 아웃바운드 443·7844·RDS 3306·Redis 6379                              │
  │        │ 0.0.0.0/0                                                                       │
  │        ▼                                                                                 │
  │  NAT 인스턴스 t4g.nano (public 서브넷, SG: 관리자 SG 에서만)   S3 게이트웨이 엔드포인트(무료) │
  └──────────────────────────────────────────────────────────────────────────────────────────┘
        RDS(공유) ◄─ ruleup_app(공개) / ruleup_admin(관리자) / ruleup_migrator(마이그레이션 태스크)
        Redis(공유) · SQS 알림 큐(관리자는 Send 만, 소비는 공개 API)
```

## 2. 결정과 이유

| 항목 | 결정 | 이유 |
|---|---|---|
| 실행 단위 | 같은 VPC·클러스터의 **별도 ECS 서비스** | 보안 경계는 SG·IAM 역할·시크릿·DB 계정으로 나뉜다. 별도 클러스터/EC2 는 경계를 더하지 않고 운영 비용만 는다 |
| 코드 분리 | 같은 저장소·같은 이미지, **실행 역할** 설정 | 별도 모듈은 도메인 로직이 두 벌이 되는 위험. 역할별로 핸들러 매핑 자체를 만들지 않아 "숨김"이 아니라 "부재" |
| 터널 커넥터 | 관리자 태스크의 **사이드카** | 원본이 localhost 라 관리자 SG 에 인바운드 0. 커넥터가 죽으면(essential) 태스크째 교체 |
| 외부 통신 | **NAT 인스턴스 t4g.nano** | Tunnel 은 인터넷 출구가 필요하다(VPC 엔드포인트로 대체 불가). NAT GW(월 ~$45) 대비 월 ~$8. 사용자 트래픽이 아니라 관리자 콘솔만 쓰므로 단일 인스턴스로 충분 |
| 용량 | **FARGATE_SPOT**, 0.5vCPU/1GB | 회수돼도 1~2분 콘솔 중단뿐, 사용자 영향 없음 |
| 공개 ALB | 관리자용 ALB 추가 안 함 | 터널로 충분. 추가 공개 진입점은 공격면 |

## 3. 권한 분리

| | 공개 API | 관리자 API |
|---|---|---|
| 인증 | 앱 JWT(HS256, 서버 시크릿) | Cloudflare Access JWT(RS256, 팀 JWKS): 서명·iss·aud·exp + 허용 이메일 + Origin. 앱 JWT 는 무시 |
| 실행 역할 | `ruleup-<env>-ecs-exec`(기존) | `ruleup-<env>-admin-exec`: ruleup-api 이미지·관리자 로그 그룹·`ruleup-<env>/admin` 시크릿만 |
| 앱 역할 | `ruleup-<env>-ecs-task`(기존) | `ruleup-<env>-admin-task`: 알림 큐 SendMessage, 미디어 GetObject, `RuleUp/App/*` 지표만. Bedrock·S3 쓰기·SQS 수신·ECS Exec 없음 |
| 시크릿 | `ruleup-<env>/app` | `ruleup-<env>/admin`(DB_PASSWORD·BAN_LIST_SALT·TUNNEL_TOKEN). 공개 API 의 JWT·OAuth·FCM·Gemini 키 없음 |
| DB 계정 | 현재 **마스터(ruleup)** → `ruleup_app`(전 테이블 DML + 파티션 테이블 6개 ALTER/CREATE/DROP) | `ruleup_admin`: 34개 테이블의 실행 근거 있는 작업만(`db/admin-grants.txt`). DDL·GRANT 없음, 연결 상한 20 |
| 마이그레이션 | `ruleup_migrator`: 일회성 태스크에만. 스키마 DDL, GRANT OPTION 없음 | — |
| SG | `ruleup-<env>-ecs-sg`(기존, ALB 에서만 8080) | `ruleup-<env>-admin-sg`: 인바운드 0 |
| 배경 작업 | 배치·SQS 컨슈머·아웃박스 스윕·기동 워밍업 | 없음(스케줄러 꺼짐, 컨슈머 꺼짐, 아웃박스는 쌓기만) |

DB 권한 근거: `AdminSqlCapture` 가 관리자 IT 6종 실행 중 관리자 요청 구간의 SQL 을 수집했다. 관리자 역할 IT 는 실제로
`grants.sql` 로 만든 `ruleup_admin` 계정으로 접속해 통과한다(`RestrictedAdminDb`). 관리자 기능이 목록 밖 테이블을
쓰면 그 IT 들이 실패한다.

### 분리로 줄어드는 위험 / 남는 위험

줄어드는 것
- 공개 인터넷·공개 ALB 에서 관리자 기능에 닿는 경로가 사라진다(매핑 부재 + ALB 404 + WAF 규칙 + Access).
- 공개 API 가 털려도(RCE·SSRF) 관리자 시크릿·터널 토큰이 없다. 관리자 서비스가 털려도 JWT 서명키·OAuth·FCM 키가 없다.
- 비밀번호 하나를 운영자 전원이 공유하던 진입이 사람 단위 신원 + MFA 로 바뀌고, 감사 로그가 사람을 가리킨다.
- 관리자 계정으로는 DDL·세션 토큰·위치 신호 같은 테이블에 접근하지 못한다.

남는 것
- **같은 RDS**: DB 장애·성능 저하는 둘 다 멈춘다. 관리자 계정 권한 안의 테이블(users·sanctions·challenges 등)은
  관리자 서비스 침해 시 변조될 수 있다. 공개 API 계정은 전 테이블 DML 이라 공개 API 침해 시 관리자 테이블(감사 로그 포함)도 변조 가능.
- **같은 Redis**: 인증·ACL 이 없는 ElastiCache 라 SG 를 통과하면 키 전체에 접근한다(AUTH/RBAC 는 후속).
- Cloudflare 계정 탈취는 Access·Tunnel·WAF 를 모두 바꿀 수 있다 — Cloudflare 계정 MFA·최소 관리자 수가 전제.
- NAT 인스턴스는 단일 AZ·단일 인스턴스다(장애 시 관리자 콘솔만 중단, 복구 절차 7절).

## 4. 비용(서울 리전, 월, 대략)

| 리소스 | stg | prod |
|---|---|---|
| NAT 인스턴스 t4g.nano + EBS 8GB + 공인 IPv4 | ~$8.2 | ~$8.2 |
| 관리자 Fargate ARM 0.5vCPU/1GB, Spot 상시 1 | ~$5 | ~$5 |
| Secrets Manager 2개 | $0.8 | $0.8 |
| CloudWatch 로그·경보 2개 | <$1 | <$1 |
| S3 게이트웨이 엔드포인트 | $0 | $0(기존) |
| Cloudflare Zero Trust(≤50명)·Tunnel | $0 | $0 |
| **합계** | **~$15** | **~$15** |

비교: NAT Gateway 를 쓰면 환경당 +$43 + GB 당 $0.059. 인터페이스 엔드포인트 5종(ecr.api·ecr.dkr·logs·secretsmanager·sqs)은
2AZ 기준 환경당 ~$95 이고 그래도 Tunnel 출구는 따로 필요하다. stg 는 쓰지 않을 때 서비스 desired 0 으로 $5 를 더 아낄 수 있다.

## 5. 전환 절차 (stg 먼저, 같은 순서로 prod)

각 단계는 이전 단계 검증 후에 진행한다. **5단계 전까지 기존 비밀번호 콘솔은 계속 동작한다.**

### 0단계 — 공개 API 를 전환 모드로 (코드 배포 전)
공개 API 태스크 정의에 `APP_RUNTIME_LEGACY_PUBLIC_ADMIN=true` 를 넣은 리비전으로 서비스를 옮긴 **뒤** 이 브랜치를 머지한다.
없이 머지하면 배포 즉시 공개 API 에서 관리자 경로가 사라져 콘솔이 끊긴다.

### 1단계 — DB 계정·IAM·네트워크·시크릿 (유료 리소스 생성 — 승인 후)
```
python3 infra/admin-split/provision.py stg                       # 계획 확인
python3 infra/admin-split/provision.py stg --apply --only network,iam,secrets,logs
bash   infra/admin-split/db/apply_grants.sh stg   # 일회성 태스크 이미지 등록이 필요하면 먼저 --only ecs
```
검증: `SHOW GRANTS` 로그(dbops/), NAT 인스턴스 running, app 서브넷 라우트 0.0.0.0/0→NAT.

### 2단계 — Cloudflare (대시보드, 사람이)
wrangler 토큰에는 Access·Tunnel·WAF 권한이 없다. Zero Trust 대시보드에서:
1. 팀 이름 정하기 → 팀 도메인 `https://<team>.cloudflareaccess.com`.
2. 로그인 수단: **MFA 를 강제할 수 있는 IdP** — Google Workspace(2단계 인증 강제) 또는 GitHub(조직 2FA 필수) 중 택1.
   이메일 OTP 단독은 MFA 가 아니다. 계정에서 Access 자체 MFA(독립 MFA)가 제공되면 정책에서 함께 요구한다.
3. Access 애플리케이션(자체 호스팅) 하나에 호스트 셋: `admin.ruleup.co.kr`, `admin-api.ruleup.co.kr`, `admin-api-stg.ruleup.co.kr`.
   세션 8시간 이하, 쿠키 SameSite=Strict·HttpOnly·바인딩 쿠키 켬. 정책: Allow / Include 이메일 목록 / Require 인증 방법 mfa(가능하면).
   **AUD 태그**를 복사한다.
4. 터널 `ruleup-stg-admin` 생성 → 토큰을 시크릿에 넣는다(값이 터미널에 남지 않게, 기존 키 보존 병합):
   ```
   python3 - <<'PY'
   import boto3, json, getpass
   sm = boto3.client('secretsmanager', region_name='ap-northeast-2'); n = 'ruleup-stg/admin'
   cur = json.loads(sm.get_secret_value(SecretId=n)['SecretString'])
   cur['TUNNEL_TOKEN'] = getpass.getpass('tunnel token: ').strip()
   sm.put_secret_value(SecretId=n, SecretString=json.dumps(cur)); print('keys', sorted(cur))
   PY
   ```
   (echo 파이프로 시크릿을 고치다 키가 손상된 사고가 있었다 — 위 방식만 쓴다.)
5. 터널 공개 호스트명 `admin-api-stg.ruleup.co.kr` → `http://localhost:8080`, Access 보호(팀·AUD) 켬 — 커넥터에서도 JWT 를 검증한다.
6. WAF 사용자 규칙(영역 ruleup.co.kr): `http.host in {"prod.ruleup.co.kr" "staging-api.ruleup.co.kr"} and starts_with(http.request.uri.path, "/api/v1/admin")` → Block.

### 3단계 — 관리자 서비스 배포·제한 경로
```
python3 infra/admin-split/provision.py stg --apply --only ecs \
  --access-team-domain https://<team>.cloudflareaccess.com --access-aud <AUD> --access-emails a@x.com,b@y.com
aws ecs update-service --cluster ruleup-stg-cluster --service ruleup-stg-admin --desired-count 1
```
이후 dev 머지마다 `deploy-admin` 잡이 같은 이미지로 자동 배포한다. 검증:
- `/ecs/ruleup-stg-admin` 에 `실행 역할 role=ADMIN`, cloudflared `Registered tunnel connection` 4개.
- 브라우저에서 `https://admin-api-stg.ruleup.co.kr/api/v1/admin/auth/session` → Access 로그인 → 200.
- 허용 목록 밖 계정 → Access 단계에서 거부. 토큰 없이 `curl` → Access 로그인 페이지(302/403).

> **stg 에서 확인해야 할 가정(아직 미검증)** — Worker 가 콘솔에서 받은 Access JWT 를 `cf-access-token` 헤더로 관리자 API
> 호스트에 넘기면 같은 Access 앱이 그 사람으로 통과시킨다는 것. 통과하지 않으면(예: 바인딩 쿠키가 헤더 토큰을 막는 경우)
> ① 해당 앱의 바인딩 쿠키를 끄거나 ② 관리자 API 호스트를 별도 Access 앱(서비스 토큰 정책)으로 두고 Worker 가 서비스 토큰과
> 함께 사용자 JWT 를 별도 헤더로 보내고, 원본이 두 JWT 를 모두 검증하도록 바꾼다(`ADMIN_ACCESS_AUDIENCES` 에 두 AUD).

### 4단계 — 관리자 웹 연결
AdminPage `feat/cloudflare-access-admin-api` 배포(`workers_dev=false`), Worker 시크릿:
```
npx wrangler secret put STG_BACKEND_ORIGIN --name adminpage   # https://admin-api-stg.ruleup.co.kr
```
신고 처리·문의 답변·공지 등록/취소·제재 집행/해제·대시보드를 실제로 수행하고, 알림이 공개 API 컨슈머를 통해 FCM 으로 나가는지
(`/ecs/ruleup-stg-api` 의 `notification.push`) 확인한다.

### 5단계 — 공개 API 에서 관리자 엔드포인트 제거
공개 API 태스크 정의에서 `APP_RUNTIME_LEGACY_PUBLIC_ADMIN` 과 `ADMIN_PASSCODE` 시크릿 참조를 뺀 리비전으로 옮기고, ALB 가드:
```
python3 infra/admin-split/provision.py stg --apply --only alb-guard
```

### 6단계 — 우회 경로 재검증
| 경로 | 기대 |
|---|---|
| `https://staging-api.ruleup.co.kr/api/v1/admin/auth/login` | WAF 403 또는 404 |
| ALB DNS 직접(`curl -k -H 'Host: staging-api.ruleup.co.kr' https://<alb-dns>/api/v1/admin/...`) | 404(ALB 규칙). alb-lockdown 후에는 연결 불가 |
| 공개 API 태스크 공인 IP:8080 | 연결 불가(ECS SG 는 ALB SG 에서만) |
| 관리자 태스크 IP | 공인 IP 없음, SG 인바운드 0 |
| `adminpage.<acct>.workers.dev`, 미리보기 URL | 비활성(404) |
| `admin-api-stg` 에 위조/만료 JWT 를 `Cf-Access-Jwt-Assertion` 으로 | Access 가 먼저 차단, 통과해도 원본 401 |

마지막으로 공개 ALB 를 Cloudflare 대역으로 잠근다(Android 는 Cloudflare 프록시를 통하므로 영향 없음 — prod/staging 도메인 DNS 가 프록시 켜짐인지 먼저 확인):
```
python3 infra/admin-split/provision.py stg --apply --only alb-lockdown
```

### 마이그레이션 분리 (관리자 전환 후 별도 진행)
1. `apply_grants.sh` 로 `ruleup_app`·`ruleup_migrator` 가 이미 만들어져 있다.
2. 저장소 변수 `MIGRATION_TASK_ENABLED=true` → 배포 때 서비스 갱신 전에 `ruleup-migrate-<env>` 가 Flyway 를 돌린다.
3. 공개 API 태스크 정의: `SPRING_DATASOURCE_USERNAME=ruleup_app`, 비밀번호는 `ruleup-<env>/db-users:APP_DB_PASSWORD`
   (실행 역할 `ruleup-<env>-ecs-exec` 에 그 시크릿 읽기 추가), `SPRING_FLYWAY_ENABLED=false`, `sslMode=REQUIRED`.
4. 이후 마스터 계정(ruleup)은 사람이 계정 관리에만 쓴다.

## 6. 롤백

| 상황 | 방법 | 관리자 API 가 공개되지 않는 이유 |
|---|---|---|
| 관리자 서비스 새 버전 실패 | 배포 회로차단기가 자동 롤백. 수동: `deploy-admin.yml` 에 이전 SHA | 관리자 서비스에는 공개 진입점이 없다 |
| 관리자 서비스 자체를 포기 | AdminPage 시크릿을 옛 오리진으로, 공개 API 에 `APP_RUNTIME_LEGACY_PUBLIC_ADMIN=true` + `ADMIN_PASSCODE` 복구 | 이때는 의도적 공개 — WAF 규칙을 함께 풀어야 하므로 실수로 열리지 않는다 |
| 공개 API 를 분리 이전 이미지로 롤백 | 이미지 롤백 | ALB 404 규칙 + WAF 규칙이 `/api/v1/admin` 을 계속 막는다 |
| 역할 설정 실수(`all`) | — | stg·prod 프로필에서 `role=all` 은 기동 실패(RuntimeRoleGuard) |
| 역할 env 누락 | — | 기본값 `api` = 관리자 경로 없음 |

## 7. 장애 복구

| 장애 | 증상 | 복구 |
|---|---|---|
| Tunnel 커넥터 다운 | 콘솔 502/1033 | cloudflared 는 essential → 태스크 자동 교체. 반복되면 `/ecs/ruleup-<env>-admin` tunnel/ 로그, 토큰 만료·폐기 확인 후 시크릿 갱신 → `aws ecs update-service --force-new-deployment` |
| NAT 인스턴스 장애 | 새 태스크가 이미지·시크릿을 못 받음(기존 태스크의 터널도 끊김) | 하드웨어: AWS 자동 복구(같은 ENI). OS 멈춤: 재부팅 경보. AZ 장애: 옛 인스턴스 종료 보호 해제·종료 후 `provision.py <env> --apply --only network --nat-az c`(새 인스턴스 + 라우트를 새 ENI 로 교체) |
| Access 설정 오류(AUD·팀 불일치) | 원본 401, 로그 `admin_access_denied reason=WRONG_AUDIENCE/WRONG_ISSUER` | 태스크 정의 env `ADMIN_ACCESS_*` 수정 후 새 리비전 |
| 허용 목록 누락 | 403 `NOT_ALLOWLISTED` | `ADMIN_ACCESS_ALLOWED_EMAILS` 갱신(Access 정책과 둘 다) |
| JWKS 조회 실패 | 기존 키로 계속 검증, 키 교체 시점에만 401 | NAT·443 아웃바운드 확인. 로그 `Access 공개키를 받아 오지 못했다` |
| DB 권한 부족(새 기능) | 500, 로그 `command denied to user 'ruleup_admin'` | `admin-grants.txt` 추가 → `render_grants.py` → `apply_grants.sh` (IT 가 먼저 잡는 것이 정상 경로) |
| 거부 급증 | 경보 `ruleup-<env>-admin-access-denied` | 로그의 reason·actor 로 공격/오설정 구분 |

## 8. 연결 수

prod RDS db.t4g.small 의 max_connections ≈ 130. 공개 API 15 × 최대 4 태스크 × 롤링 2배 = 120, 관리자 5 × 2 = 10,
마이그레이션 2. **최대 확장 상태에서 배포하면 한도에 닿는다.** 관리자 트래픽이 빠지는 5단계 이후 공개 API 풀을 12 로 낮추거나
(4×12×2=96) `ruleup_app` 에 `MAX_USER_CONNECTIONS 110` 을 거는 것을 권한다. 관리자 계정은 이미 20 으로 묶여 있다.

## 9. 파일

- `provision.py` — 인프라(계획/적용), `db/render_grants.py`·`db/grants.sql`·`db/admin-grants.txt`·`db/apply_grants.sh` — DB 계정
- `.github/scripts/deploy-admin.sh`·`run-migration.sh`, `.github/workflows/deploy-admin.yml` — 배포
- 코드: `config/runtime/*`(역할), `admin/access/*`(Access 검증·감사 요청 로그), `application-admin.yaml`·`application-migrate.yaml`
