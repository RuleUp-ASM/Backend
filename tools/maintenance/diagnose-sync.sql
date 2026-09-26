-- ======================================================================
-- sync 진단 — 「신호가 안 들어온 것」과 「들어왔는데 안 쓰인 것」을 가른다
--
-- 전부 읽기 전용이다. 쓰기 문장은 하나도 없다.
-- 위에서 아래로 차례대로 돌리고 챌린지가 요구하는 신호에 맞춰 해석한다.
-- 배제 신호 0건처럼 정상인 결과도 있으므로 0건만으로 조사를 중단하지 않는다.
--
--   1) 요청이 오는가        verification_sync_sessions.lastSeenAt
--   2) 동의가 있는가        user_agreement_states (위치·건강 신호의 적재 전제)
--   3) 신호가 쌓이는가      verification_*_signals
--   4) 판정에 쓰이는가      excludeReason (게이트가 새긴 배제 사유)
--   5) 멤버가 평가 대상인가 challenge_members.setup_status / status
--   6) 판정이 움직이는가    VerificationDaily / VerificationMethodResult.evidence
--
-- 스테이징의 KST JDBC 설정에서는 두 무리의 시각이 <b>9시간 어긋나 보인다</b>. JPA 가 쓰는 표(VerificationDaily ·
-- VerificationMethodResult …)는 UTC 로, JdbcTemplate 가 쓰는 표(verification_*_signals ·
-- verification_sync_sessions · signal_exclusions)는 <b>KST 로</b> 저장된다. 같은 sync 요청의
-- receivedAt 이 11:25:18, lastEvaluatedAt 이 02:25:18 로 찍히는 것이 그 때문이다(실제 UTC 는
-- 02:25:18 — 서버 로그와 맞다). 두 무리의 시각을 SQL 안에서 직접 빼지 말 것.
-- UTC JDBC 설정의 로컬·CI에는 이 차이가 없다. 실행 환경의 JDBC 타임존부터 확인한다.
-- observedDate·targetDate 는 양쪽 다 KST 날짜다.
--
-- 세션을 UTC 로 고정하고 읽는다 — 클라이언트 타임존에 따라 값이 달리 보이지 않게.
-- ======================================================================

SET time_zone = '+00:00';
-- SOURCE 전에 같은 세션에서 SET @user := UUID_TO_BIN('유저 UUID'); 를 실행한다.
-- 호출자가 지정한 값을 덮어쓰지 않는다.
-- 닉네임만 안다면 먼저:
--   SELECT BIN_TO_UUID(id) AS userId, nickname, created_at FROM users WHERE nickname = '닉네임';
--
-- CURRENT_DATE 는 UTC 날짜다(위에서 세션을 UTC 로 고정했다). KST 09시 전에 돌리면
-- 범위가 하루 당겨지므로, 어제·오늘을 꼭 봐야 하면 INTERVAL 을 하루 늘려 잡는다.

-- ----------------------------------------------------------------------
-- 1) sync 요청 자체가 서버에 닿는가
--    lastSeenAt 이 비어 있거나 오래됐으면 앱이 요청을 못 보내고 있는 것이다.
--    issuedAt 만 있고 lastSeenAt 이 없으면 인트로만 하고 sync 를 한 번도 안 쳤다.
-- ----------------------------------------------------------------------
SELECT BIN_TO_UUID(id) AS sessionId, deviceId, appVersion, sdkInt,
       issuedAt, lastSeenAt -- 저장 타임존 확인 전 UTC_TIMESTAMP()와 직접 차이를 계산하지 않는다
FROM verification_sync_sessions
WHERE userId = @user
ORDER BY issuedAt DESC
LIMIT 5;

-- 활성 기기. 여기 값과 sync 가 들고 온 deviceId 가 다르면 그 요청의 신호는
-- 전부 UNTRUSTED_SOURCE 로 새겨져 판정에서 빠진다(4단계에서 드러난다).
SELECT BIN_TO_UUID(id) AS userId, nickname, device_id AS 활성기기, country_code, status
FROM users WHERE id = @user;

-- ----------------------------------------------------------------------
-- 2) 개별 동의 — 없으면 위치·건강 신호는 저장조차 되지 않는다
--    LOCATION_INFO 가 없으면 GPS·지오펜스가, HEALTH_INFO 가 없으면 걸음·수면이
--    적재 이전에 떨어진다(응답 consentRequired 로 내려간다). 앱 사용은 대상이 아니다.
-- ----------------------------------------------------------------------
SELECT agreement_type, agreed, version, agreed_at
FROM user_agreement_states
WHERE user_id = @user AND agreement_type IN ('LOCATION_INFO', 'HEALTH_INFO');

-- ----------------------------------------------------------------------
-- 3) 신호가 실제로 쌓였는가 — 최근 7일, 도메인별·귀속일별
--    행이 0이면 「안 들어온 것」이다. 1·2단계와 앱 로그를 본다.
--    행이 있는데 판정이 안 움직이면 4단계로 간다.
-- ----------------------------------------------------------------------
SELECT 'LOCATION' AS domain, observedDate, signalType,
       COUNT(*) AS 건수, SUM(excludeReason IS NULL) AS 판정입력,
       MIN(occurredAt) AS 최초발생, MAX(occurredAt) AS 최종발생, MAX(receivedAt) AS 최종수신
FROM verification_location_signals
WHERE userId = @user AND observedDate >= CURRENT_DATE - INTERVAL 7 DAY
GROUP BY observedDate, signalType
UNION ALL
SELECT 'DEVICE_USAGE', observedDate, signalType,
       COUNT(*), SUM(excludeReason IS NULL), MIN(occurredAt), MAX(occurredAt), MAX(receivedAt)
FROM verification_device_usage_signals
WHERE userId = @user AND observedDate >= CURRENT_DATE - INTERVAL 7 DAY
GROUP BY observedDate, signalType
UNION ALL
SELECT 'HEALTH_CONNECT', observedDate, signalType,
       COUNT(*), SUM(excludeReason IS NULL), MIN(occurredAt), MAX(occurredAt), MAX(receivedAt)
FROM verification_health_connect_signals
WHERE userId = @user AND observedDate >= CURRENT_DATE - INTERVAL 7 DAY
GROUP BY observedDate, signalType
ORDER BY observedDate DESC, domain;

-- ----------------------------------------------------------------------
-- 4) 들어왔는데 판정에서 빠졌는가 — 배제 사유 분포
--    판정 입력은 excludeReason IS NULL 인 행뿐이다. 여기 값이 차 있으면
--    「저장은 됐지만 안 쓴다」는 뜻이고, 사유가 어디서 붙었는지가 곧 원인이다.
--      UNTRUSTED_SOURCE — 비활성 기기·무결성 실패 (1단계 활성기기 대조)
--      VPN / MOCK       — 봉투 게이트
--      ACCURACY_LOW     — 정확도 미달 좌표
-- ----------------------------------------------------------------------
SELECT 'LOCATION' AS domain, observedDate, excludeReason, COUNT(*) AS 건수
FROM verification_location_signals
WHERE userId = @user AND observedDate >= CURRENT_DATE - INTERVAL 7 DAY
GROUP BY observedDate, excludeReason
UNION ALL
SELECT 'DEVICE_USAGE', observedDate, excludeReason, COUNT(*)
FROM verification_device_usage_signals
WHERE userId = @user AND observedDate >= CURRENT_DATE - INTERVAL 7 DAY
GROUP BY observedDate, excludeReason
UNION ALL
SELECT 'HEALTH_CONNECT', observedDate, excludeReason, COUNT(*)
FROM verification_health_connect_signals
WHERE userId = @user AND observedDate >= CURRENT_DATE - INTERVAL 7 DAY
GROUP BY observedDate, excludeReason
ORDER BY observedDate DESC, domain;

-- 평가 단계에서 뺀 신호(신호 위생) — 성공 확정 시 한 번 옮겨 적는다.
SELECT signalType, reason, signalCount, excludedAt,
       BIN_TO_UUID(verificationDailyId) AS verificationId
FROM signal_exclusions
WHERE userId = @user AND excludedAt >= UTC_TIMESTAMP() - INTERVAL 7 DAY
ORDER BY excludedAt DESC
LIMIT 50;

-- ----------------------------------------------------------------------
-- 5) 그 멤버가 평가 대상인가
--    setup_status='PENDING_SETUP' 이면 신호는 받되 평가를 건너뛴다(권한 없는데
--    FAILED 를 만들지 않으려고). status 가 ACTIVE 가 아니거나 방이 ACTIVE 가
--    아니면 오늘 판정 자체가 열리지 않는다. verification_config 가 수동이면
--    자동 평가 대상이 아니다.
-- ----------------------------------------------------------------------
SELECT BIN_TO_UUID(cm.id) AS memberId, BIN_TO_UUID(c.id) AS challengeId, c.title,
       c.status AS 방상태, c.start_date, c.end_date, c.verification_type,
       c.verification_config, c.params,
       cm.status AS 멤버상태, cm.setup_status, cm.target_days, cm.success_days,
       cm.fail_days, cm.today_status, cm.last_synced_at,
       cm.anchors IS NOT NULL AS 앵커있음, cm.screen_apps IS NOT NULL AS 앱목록있음
FROM challenge_members cm
JOIN challenges c ON c.id = cm.challenge_id
WHERE cm.user_id = @user AND cm.status = 'ACTIVE' AND c.deleted_at IS NULL
ORDER BY c.start_date DESC;

-- ----------------------------------------------------------------------
-- 6) 판정이 움직였는가 — 최근 7일 판정 행과 평가 근거
--    status/failureReason 은 「무엇으로 끝났는가」이고, evidence 는
--    「평가기가 무엇을 보고 그렇게 판단했는가」다. 신호는 쌓였는데 evidence 가
--    비어 있으면 그 날의 원본이 평가기에 닿지 않은 것이다(3·4단계로 되돌아간다).
--    lastEvaluatedAt 이 안 움직이면 애초에 평가가 안 돌았다.
-- ----------------------------------------------------------------------
SELECT BIN_TO_UUID(vd.id) AS verificationId, c.title, vd.targetDate,
       vd.status, vd.method, vd.failureReason, vd.gapReason, vd.verifiedVia,
       vd.windowClosesAt, vd.finalizeAfter, vd.appealClosesAt, vd.verifiedAt,
       vd.createdAt, vd.updatedAt,
       mr.status AS 방식판정, mr.lastEvaluatedAt, mr.evidence
FROM VerificationDaily vd
JOIN challenges c ON c.id = vd.challengeId
LEFT JOIN VerificationMethodResult mr ON mr.verificationDailyId = vd.id
WHERE vd.userId = @user AND vd.targetDate >= CURRENT_DATE - INTERVAL 7 DAY
ORDER BY vd.targetDate DESC, c.title;

-- 권한 공백 대기(있으면 앱이 gaps 를 보내고 있다는 뜻이다).
SELECT BIN_TO_UUID(challenge_id) AS challengeId, signal_type, waiting_from_on,
       first_observed_at, resolved_at, dispatched_at
FROM verification_permission_waits
WHERE user_id = @user
ORDER BY first_observed_at DESC
LIMIT 20;
