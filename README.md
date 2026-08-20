# RuleUp Backend

> 목표를 만드는 데서 끝나지 않고, 실제 행동을 인증하고 함께 완주하도록 돕는 챌린지 플랫폼

RuleUp은 사용자가 루틴을 챌린지로 만들고 여러 사람과 함께 실천하는 모바일 서비스입니다. 백엔드는 OAuth 로그인부터 챌린지 생성·운영, 센서 기반 활동 인증, 랭킹·추천, 알림과 운영 배치까지 서비스의 전체 도메인을 REST API로 제공합니다.

단순 CRUD보다 다음 세 가지 문제에 집중했습니다.

- 휴대폰·Health Connect 신호를 신뢰 가능한 일별 인증 결과로 바꾸는 방법
- 동시 참여, 방장 위임, 탈퇴와 종료가 얽힌 그룹 챌린지의 일관성을 지키는 방법
- 외부 OAuth와 LLM이 느리거나 실패해도 핵심 요청을 안정적으로 처리하는 방법

## 프로젝트 한눈에 보기

| 구분 | 내용 |
| --- | --- |
| 서비스 | 함께 목표를 실천하고 자동·수동 인증으로 완주하는 챌린지 플랫폼 |
| 백엔드 범위 | 로그인·회원, 챌린지, 인증, 추천, 랭킹, 알림, 감시자, 신고·검수, 배치 |
| API | 26개 Controller, 88개 Endpoint |
| 데이터 관리 | MySQL 8.4, Flyway Migration 18개, JPA `ddl-auto: none` |
| 품질 관리 | 테스트 클래스 40개, Testcontainers, JaCoCo 커버리지 게이트 |
| 문서화 | Swagger UI, OpenAPI JSON, 도메인별 기술 스펙 |
| 배포 | Docker, GitHub Actions, Amazon ECR/ECS |

위 수치는 현재 저장소의 소스 코드를 기준으로 합니다.

## 핵심 기능

### 1. 챌린지 생성과 운영

- AI 프롬프트, 루틴 템플릿, 기존 챌린지 복제로 초안 생성
- 카테고리·기간·반복 일정·인증 방법을 조합한 챌린지 생성
- 인기·카테고리·참여자·완주율 등 조건을 활용한 탐색
- 초대 링크, 참여·탈퇴, 강퇴, 공동 관리자, 방장 위임
- `UPCOMING → ACTIVE → COMPLETED` 라이프사이클 자동 전환

### 2. 활동 인증

- 휴대폰, Health Connect, 외부 서비스 신호 일괄 동기화
- GPS 방문·회피, 걸음·거리·운동 시간, 수면, 스크린타임 등 정책 판정
- 자동 인증이 불가능한 경우 사진·그룹 승인 방식으로 fallback
- 인증 실패에 대한 이의 제기와 관리자 승인·반려
- 사용자가 설정해야 하는 위치·앱·권한 상태를 API로 안내

### 3. 참여를 유지하는 피드백

- 챌린지 방, 인증 이벤트 피드, 방별·챌린지 간 랭킹
- 활동 캘린더, 통계 리포트, 연속 달성 기록
- 챌린지 성과와 활동 기간을 반영한 매너 온도
- 사용자 세그먼트와 행동 결과를 반영한 루틴 추천
- 인앱 알림, FCM 푸시, 회원·비회원 감시자 알림

### 4. 서비스 안전장치

- 닉네임·프로필·챌린지 이미지 비동기 검수
- 사용자·챌린지 신고와 차단
- 앱 최소 버전과 약관 버전을 서버에서 통제
- 계정 상태별 API 접근 제어와 개인정보 암호화

## 시스템 아키텍처

```mermaid
flowchart LR
    App[Mobile Client] --> Security[Spring Security<br/>JWT · Account Status]
    Security --> API[REST Controllers]
    API --> Domain[Domain Services]
    Domain --> JPA[Spring Data JPA]
    JPA --> DB[(MySQL 8.4)]

    Domain --> Cache[(Caffeine Cache)]
    Domain --> Event[Domain Events]
    Event --> Async[Async Workers]
    Async --> Outbox[(Notification / Push Outbox)]

    Domain --> OAuth[Kakao · Google OAuth]
    Async --> AI[Gemini · AWS Bedrock]
    Domain --> Places[Naver Places]
    Outbox --> FCM[Firebase FCM]

    Flyway[Flyway] --> DB
    Scheduler[Scheduled Jobs] --> Domain
```

애플리케이션은 도메인별 패키지 구조를 사용합니다. 각 도메인에 controller, service, repository, domain, dto를 가깝게 두고, 공통 응답·예외·이미지·검증 코드는 `common`으로 분리했습니다.

## 주요 기술 과제와 해결

### 1. Refresh Token 동시 재발급과 재사용 탐지

**문제**

- Access Token 만료 시 여러 API가 동시에 401을 받으면 같은 Refresh Token이 거의 동시에 제출될 수 있습니다.
- 회전 대상 토큰을 일반 조회하면 두 요청이 모두 활성 토큰으로 판단해 새로운 토큰을 중복 발급할 가능성이 있습니다.
- 재사용 탐지 뒤 예외를 던질 때 트랜잭션이 롤백되면 보안 흔적과 token family 폐기가 사라질 수 있습니다.

**해결**

- Refresh Token 원문은 저장하지 않고 SHA-256 해시만 저장합니다.
- 재발급 조회에 비관적 쓰기 잠금(`PESSIMISTIC_WRITE`)을 적용해 동일 토큰의 회전을 직렬화했습니다.
- 선행 요청이 토큰을 회전하면 후행 요청은 폐기 상태를 읽고 재사용 탐지 경로로 이동합니다.
- 인증 예외와 별개로 `reuse_detected_at`과 family revoke가 커밋되도록 트랜잭션 경계를 설계했습니다.
- 일반 만료·폐기 토큰과 보안 감사 대상 토큰의 보관 기간을 분리하고, 작은 DELETE batch로 정리해 장시간 DB lock을 피했습니다.

```mermaid
sequenceDiagram
    participant C as Client
    participant A as Auth API
    participant D as MySQL

    C->>A: Refresh Token 제출
    A->>D: token hash 조회 + FOR UPDATE
    D-->>A: 활성 토큰
    A->>D: 기존 토큰 revoke + 새 토큰 저장
    A-->>C: 새 Access / Refresh Token

    Note over C,D: 같은 토큰이 다시 제출된 경우
    C->>A: 폐기된 Refresh Token 재사용
    A->>D: reuse 기록 + token family revoke
    A-->>C: 401 SESSION_EXPIRED
```

### 2. OAuth 외부 지연을 서버 장애와 분리

**문제**

- OAuth 로그인은 provider의 토큰 교환과 사용자 조회를 직렬로 수행하므로 외부 지연이 로그인 응답 시간에 그대로 더해집니다.
- 커넥션 풀 대기, TCP 연결, 응답 대기에 상한이 없으면 provider 장애가 서버 요청 스레드 고갈로 번질 수 있습니다.

**해결**

- Apache HttpClient 5 기반 전용 connection pool을 구성해 TCP·TLS 연결을 재사용합니다.
- pool 획득, connect, response timeout을 각각 분리하고 환경 변수로 조정할 수 있게 했습니다.
- provider host·HTTP method·결과별 `oauth_http_client_duration` Timer를 수집해 어느 단계가 느린지 관측할 수 있게 했습니다.
- 토큰·인가 코드·전체 URI는 metric tag에서 제외해 민감정보 노출과 high cardinality를 방지했습니다.
- transport 실패와 provider 4xx/5xx를 분리해 클라이언트가 재로그인과 일시 장애를 구분할 수 있도록 했습니다.

### 3. 다양한 신호를 하나의 인증 모델로 통합

**문제**

- GPS, 걸음 수, 수면, 앱 사용시간은 데이터 형태와 성공 조건이 모두 다릅니다.
- 모바일이 성공 여부까지 결정하면 클라이언트 버전마다 정책이 달라지고 조작에 취약해집니다.

**해결**

- 클라이언트는 신호 수집과 동기화만 담당하고, 성공 여부는 서버 정책이 판정하는 `client-initiated + server-policy` 구조를 사용합니다.
- 외부 입력인 Config와 내부 판정 단위인 Signal을 분리하고 타입별 evaluator로 확장 지점을 만들었습니다.
- 도달형과 제약형 조건을 같은 polarity 모델로 표현하고, 일별 결과를 하나의 인증 상태로 집계합니다.
- 신호 품질이 부족하거나 자동 인증이 불가능한 상황은 수동 인증·관리자 승인·이의 제기로 연결합니다.

```mermaid
flowchart LR
    Sensor[Phone / Health / External Signal] --> Sync[Verification Sync API]
    Sync --> Normalize[Normalize & Validate]
    Normalize --> Evaluator{Policy Evaluator}
    Evaluator --> Daily[Daily Verification]
    Daily -->|Success| Score[Progress · Ranking · Reputation]
    Daily -->|Insufficient| Manual[Manual Review / Objection]
```

### 4. 챌린지 상태와 운영 권한의 일관성

**문제**

- 챌린지는 시간 상태, 검수 상태, 인증 설정 상태가 독립적으로 변합니다.
- 가입·탈퇴·삭제·방장 위임을 단순 CRUD로 처리하면 경계 시점의 중복 처리와 잘못된 권한 변경이 발생하기 쉽습니다.

**해결**

- 라이프사이클, 검수, 인증 준비 상태를 서로 다른 축으로 모델링해 한 enum에 복잡도를 몰아넣지 않았습니다.
- 참여 한도, 재가입 제한, 종료 후 변경 금지 같은 규칙을 service의 단일 진입점에서 검사합니다.
- 활성화·완료·위임 만료 scheduler는 DB의 현재 상태를 조건으로 갱신해 재실행 가능한 멱등 작업으로 설계했습니다.
- 이벤트 기반 counter 갱신 뒤 reconciliation batch를 수행해 비동기 처리 누락이나 운영 오차를 보정합니다.

### 5. 느린 부가 작업을 핵심 요청에서 분리

**문제**

- LLM·이미지 검수와 FCM 전송은 응답 속도가 일정하지 않고 실패할 수 있습니다.
- 생성·수정 트랜잭션 안에서 처리하면 외부 장애가 사용자 요청 실패로 이어집니다.

**해결**

- 프로필·챌린지 검수와 신고 검토를 domain event와 `@Async` listener로 분리했습니다.
- 푸시와 감시자 알림은 outbox에 먼저 기록하고 dispatcher가 재처리 가능한 형태로 전송합니다.
- LLM은 Gemini 단독, Bedrock 단독, Gemini 우선 후 Bedrock fallback을 설정으로 선택할 수 있습니다.
- 추천·카테고리처럼 반복 조회되는 결과는 Caffeine에 캐시하고 배치로 갱신합니다.

## 기술 선택과 이유

| 선택 | 이유 |
| --- | --- |
| Spring Boot + 도메인 패키지 | 기능 경계를 코드 구조에 반영하고, 관련 계층을 한 위치에서 탐색하기 위해 |
| MySQL + JPA | 회원·참여·토큰 회전처럼 트랜잭션 일관성이 중요한 관계형 도메인을 처리하기 위해 |
| Flyway | 개발·CI·운영의 스키마 변경 이력을 동일하게 재현하기 위해 |
| 비관적 잠금 | 동일 Refresh Token의 경쟁처럼 충돌은 드물지만 발생 시 보안 영향이 큰 구간을 직렬화하기 위해 |
| Caffeine | 현재 운영 규모에서 네트워크 캐시 의존성 없이 읽기 비용을 줄이기 위해 |
| Rule-based 추천 | 행동 데이터가 적은 초기 서비스에서 결과를 설명하고 정책을 빠르게 조정하기 위해 |
| Event + Outbox | 외부 API 실패를 핵심 트랜잭션에서 분리하고 재처리 가능성을 확보하기 위해 |
| Testcontainers | H2 대체 동작이 아닌 실제 MySQL 8.4의 enum, lock, native query를 검증하기 위해 |

## 보안과 데이터 보호

- Stateless JWT 인증과 Spring Security filter chain
- Access Token 30분, Refresh Token 7일, Signup Token 5분
- Refresh Token rotation과 reuse detection
- 외부 OAuth access/refresh token AES-GCM 암호화 저장
- 비회원 감시자 연락처 암호화
- `LOCKED` 계정은 읽기 전용, `BANNED` 계정은 허용된 종료 경로 외 접근 차단
- 로그인 기기·설치 기준 활성 세션 정책
- 환경 변수와 Secret 기반 DB·JWT·OAuth·FCM 자격증명 주입

## 테스트와 품질 관리

```bash
./gradlew clean build
```

- JUnit 5 단위·통합·API 계약 테스트
- Testcontainers로 로컬과 CI에서 MySQL 8.4 사용
- 인증·OAuth·회원·약관·검수·점수·온보딩·보안 범위 instruction coverage 80% gate
- Flyway migration을 포함한 Spring context 기동 검증
- Swagger/OpenAPI에 공통 응답과 오류 계약 문서화
- GitHub Actions에서 PR과 주요 branch push마다 전체 build 수행

JaCoCo HTML 리포트는 `build/reports/jacoco/test/html/index.html`에 생성됩니다.

## API 구성

모든 응답은 성공과 실패가 같은 envelope를 사용하며 HTTP 상태 코드는 실제 결과를 유지합니다.

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

| 영역 | 주요 경로 |
| --- | --- |
| 인트로·앱 정책 | `GET /api/v1/intro` |
| OAuth·세션 | `/api/v1/auth/**` |
| 회원·프로필 | `/api/v1/users/me`, `/api/v1/profile` |
| 챌린지·탐색 | `/api/v1/challenges/**` |
| 인증 | `/api/v1/verifications/**`, `/api/v1/challenges/{id}/verification` |
| 룸·랭킹 | `/api/v1/challenges/{id}/room`, `/ranking`, `/threads` |
| 추천·마이 | `/api/v1/recommendations/**`, `/api/v1/me/**` |
| 알림·푸시 | `/api/v1/notifications/**`, `/api/v1/devices` |
| 신고·감시자 | `/api/v1/reports`, `/api/v1/watchers/**` |

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language | Java 25 |
| Framework | Spring Boot 4.1, Spring MVC, Spring Security, Spring Data JPA |
| Database | MySQL 8.4, Flyway |
| Cache | Caffeine |
| Auth | JWT, Kakao OAuth, Google OAuth, AES-GCM |
| External API | Apache HttpClient 5, Naver Search, Firebase FCM |
| AI | Google Gemini, AWS Bedrock Nova |
| Observability | Spring Boot Actuator, Micrometer, OSHI |
| Test | JUnit 5, Testcontainers, JaCoCo |
| Infra | Gradle, Docker, GitHub Actions, Amazon ECR/ECS |
| API Docs | springdoc OpenAPI, Swagger UI |

## 로컬 실행

JDK 25와 Docker가 필요합니다.

```bash
cp .env.example .env
docker compose up -d mysql
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Compose는 `ruleup` 데이터베이스를 생성하므로 `.env`에 다음 JDBC URL을 추가해야 합니다.

```dotenv
DB_URL=jdbc:mysql://localhost:3306/ruleup?serverTimezone=UTC&characterEncoding=UTF-8&rewriteBatchedStatements=true
JWT_SECRET=replace_with_at_least_32_random_bytes
```

`local` 프로필은 실제 Kakao·Google 서버 대신 Mock OAuth client를 사용합니다. 전체 환경 변수 예시는 [.env.example](./.env.example), 기본값과 운영 튜닝 항목은 [application.yaml](./src/main/resources/application.yaml)에서 확인할 수 있습니다.

## 프로젝트 구조

```text
src/main/java/com/ruleup/ruleup_backend/
├── auth, oauth, security       # 로그인, JWT, 세션, 보안 필터
├── user, onboarding            # 회원, 약관, 온보딩
├── challenge                   # 생성, 탐색, 참여, 운영, 라이프사이클
├── routine, verification       # 루틴 템플릿, 신호 수집, 인증 판정
├── room, recommendation        # 룸, 랭킹, 개인화 추천
├── me, profile, reputation     # 홈, 캘린더, 프로필, 매너 온도
├── notification, push          # 인앱 알림과 FCM outbox
├── watcher                     # 회원·비회원 감시자
├── report, moderation          # 신고, 차단, 콘텐츠 검수
├── places, llm                 # 외부 API와 AI provider
├── observability               # 애플리케이션·시스템 지표
├── common                      # 공통 응답, 오류, 이미지, 검증
└── config                      # Security, OpenAPI, Cache, HTTP 설정
```

## 현재 한계와 확장 방향

| 현재 선택 | 확장 시 고려할 점 |
| --- | --- |
| JVM local Caffeine cache | 다중 인스턴스 간 즉시 일관성이 필요해지면 Redis 또는 event 기반 invalidation 도입 |
| 로컬 파일 시스템 이미지 저장 | 컨테이너 수평 확장 전 S3 같은 object storage와 CDN으로 이전 |
| DB 상태 기반 멱등 scheduler | 배치 규모가 커지면 분산 lock 또는 별도 job orchestrator 도입 |
| 규칙 기반 추천 | 충분한 행동 데이터가 쌓이면 offline evaluation을 거쳐 학습 기반 ranking과 혼합 |
| 프로세스 내부 `@Async` | 유실 허용이 어려운 작업은 message broker 기반 비동기 처리로 이전 |

현재 구조가 전제하는 운영 규모를 명확히 두고, 복잡한 인프라를 먼저 도입하기보다 데이터와 트래픽이 요구하는 시점에 확장할 수 있도록 경계를 분리했습니다.

## 상세 설계 문서

- [챌린지 생성 및 라이프사이클](./docs/challenge/챌린지%20생성%20및%20라이프사이클.md)
- [활동 인증 설계](./docs/check/인증구현.md)
- [챌린지 룸·관리 기능](./docs/manage/챌린지공지_방내부기능_테크스펙.md)
- [프로필·캘린더](./docs/profile/마이프로필_캘린더_테크스펙.md)
- [루틴 카테고리 추천](./docs/recommend/추천%20시스템%20-%20루틴%20카테고리%20추천.md)
- [매너 온도 계산](./docs/score/온도%20계산%20테크스펙%20V1.md)
- [챌린지 탐색](./docs/search/챌린지탐색%20테크스펙.md)

API 요청·응답 계약은 실행 중인 Swagger와 현재 코드를 기준으로 합니다.
