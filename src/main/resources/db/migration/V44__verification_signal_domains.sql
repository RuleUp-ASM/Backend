-- =====================================================================
-- 판정 원본 신호를 3개 저장 도메인으로 분리 + 일별 파티셔닝
--   (인증 백엔드 테크스펙 4-1 · 4-1-1, 공통 5-3)
--
-- API 입력 타입은 LOCATION · HEALTH · SCREEN_TIME · WAKE · SLEEP 5종 그대로 두고,
-- **저장만** 수집 출처·조회 특성이 가까운 셋으로 합친다.
--   · LOCATION       — GPS · 지오펜스
--   · DEVICE_USAGE   — 앱 사용(SCREEN_TIME) · 화면 켜짐/잠금해제(WAKE)
--   · HEALTH_CONNECT — 걸음 · 거리 · 수면
--
-- 왜 나누는가 — 한 테이블에 몰면 유형별 nullable 컬럼과 불필요한 인덱스가 쌓이고,
-- 최단 1분 sync 상한(사용자 기준 약 2,880만 sync/일)에서 그 비용이 그대로 곱해진다.
--
-- 왜 파티션인가 — raw 는 **현재 귀속일과 직전 유예 귀속일**만 필요한 hot storage 다.
-- D일 신호는 D+2 00:00 KST 확정이 끝나면 판정 원본으로서의 목적이 끝나므로, 행 단위
-- 대량 DELETE 가 아니라 **만료된 일자 파티션 DROP** 으로 걷어낸다.
--
-- ⚠️ MySQL 파티션 테이블은 **외래 키를 가질 수 없다.** users FK 를 포기한 대가로 파티션
-- 단위 파기를 얻는다 — 신호는 판정 입력이지 관계 데이터가 아니고, 유저 삭제 시 잔여
-- 신호는 짧은 retention 안에 파티션과 함께 사라진다.
--
-- ⚠️ 유일 키에는 파티션 키가 포함돼야 한다 — `(observedDate, userId, dedupKey)` 형태다.
-- 같은 dedupKey 가 다른 발생일로 재전송되는 비정상 요청은 애플리케이션이 거른다.
--
-- 초기 파티션은 `pFuture`(MAXVALUE) 하나뿐이다. 마이그레이션 SQL 은 실행 시점을 모르므로
-- 날짜를 박아 둘 수 없고, 유지 잡이 기동 시와 매일 03:20 KST 에 일자 파티션을 잘라낸다.
-- =====================================================================

CREATE TABLE `verification_location_signals` (
    `id`           binary(16)  NOT NULL COMMENT 'UUIDv7',
    `observedDate` date        NOT NULL COMMENT 'KST 귀속일 — 파티션 키',
    `userId`       binary(16)  NOT NULL,
    -- sync 계약에 기기 식별자가 없어 채우지 않는다. 활성 기기 검증은 sync 진입에서 이미 한다.
    `deviceId`     varchar(64) NULL COMMENT '출처 기기 — 계약에 생기면 채운다',
    `signalType`   varchar(20) NOT NULL COMMENT 'LOCATION / GEOFENCE',
    `occurredAt`   datetime(3) NULL COMMENT '발생 시각 — 날짜 귀속의 기준. 파싱 불가면 NULL',
    `receivedAt`   datetime(3) NOT NULL COMMENT '서버 수신 시각',
    `payload`      json        NOT NULL COMMENT '원본 — 좌표·정확도·mock 여부·출처. 압축·요약하지 않는다',
    `dedupKey`     char(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (`observedDate`, `id`),
    UNIQUE KEY `uq_location_signal_dedup` (`observedDate`, `userId`, `dedupKey`),
    KEY `idx_location_signal_judge` (`userId`, `occurredAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='GPS·지오펜스 원본. 위치정보라 별도 도메인 — 파티션 DROP 이 곧 파기다'
PARTITION BY RANGE (TO_DAYS(`observedDate`)) (
    PARTITION `pFuture` VALUES LESS THAN MAXVALUE
);

CREATE TABLE `verification_device_usage_signals` (
    `id`           binary(16)  NOT NULL COMMENT 'UUIDv7',
    `observedDate` date        NOT NULL COMMENT 'KST 귀속일 — 파티션 키',
    `userId`       binary(16)  NOT NULL,
    `deviceId`     varchar(64) NULL COMMENT '출처 기기 — 계약에 생기면 채운다',
    `signalType`   varchar(20) NOT NULL COMMENT 'SCREEN_TIME / WAKE',
    `occurredAt`   datetime(3) NULL COMMENT '발생 시각 — 날짜 귀속의 기준',
    `receivedAt`   datetime(3) NOT NULL,
    `payload`      json        NOT NULL COMMENT '원본 — 앱 사용 시작·종료, 화면 켜짐·잠금해제',
    `dedupKey`     char(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (`observedDate`, `id`),
    UNIQUE KEY `uq_device_usage_signal_dedup` (`observedDate`, `userId`, `dedupKey`),
    KEY `idx_device_usage_signal_judge` (`userId`, `occurredAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='앱 사용·화면 이벤트 원본. 전송량의 대부분을 차지하는 테이블이다'
PARTITION BY RANGE (TO_DAYS(`observedDate`)) (
    PARTITION `pFuture` VALUES LESS THAN MAXVALUE
);

CREATE TABLE `verification_health_connect_signals` (
    `id`           binary(16)  NOT NULL COMMENT 'UUIDv7',
    `observedDate` date        NOT NULL COMMENT 'KST 귀속일 — 파티션 키',
    `userId`       binary(16)  NOT NULL,
    `deviceId`     varchar(64) NULL COMMENT '출처 기기 — 계약에 생기면 채운다',
    `signalType`   varchar(20) NOT NULL COMMENT 'HEALTH / SLEEP',
    `occurredAt`   datetime(3) NULL COMMENT '발생 시각 — 수면은 밤이 시작된 날짜로 귀속한다',
    `receivedAt`   datetime(3) NOT NULL,
    `payload`      json        NOT NULL COMMENT '원본 — metric·recordingMethod·originPackage 등 위생 검증 정보 포함',
    `dedupKey`     char(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (`observedDate`, `id`),
    UNIQUE KEY `uq_health_connect_signal_dedup` (`observedDate`, `userId`, `dedupKey`),
    KEY `idx_health_connect_signal_judge` (`userId`, `occurredAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Health Connect 걸음·거리·수면 원본'
PARTITION BY RANGE (TO_DAYS(`observedDate`)) (
    PARTITION `pFuture` VALUES LESS THAN MAXVALUE
);

-- ---------------------------------------------------------------------
-- 기존 원본 이관. 귀속일은 발생 시각(없으면 수신 시각)의 KST 날짜다.
-- CONVERT_TZ 를 쓰지 않는다 — 타임존 테이블이 적재돼 있지 않으면 NULL 이 된다.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `verification_location_signals`
    (`id`, `observedDate`, `userId`, `signalType`, `occurredAt`, `receivedAt`, `payload`, `dedupKey`)
SELECT `id`, DATE(COALESCE(`observedAt`, `receivedAt`) + INTERVAL 9 HOUR), `userId`,
       `signalType`, `observedAt`, `receivedAt`, `payload`, `dedupKey`
FROM `verification_signals`
WHERE `signalType` IN ('LOCATION', 'GEOFENCE', 'GEOFENCE_TRANSITION', 'RUNNING_SESSION');

INSERT IGNORE INTO `verification_device_usage_signals`
    (`id`, `observedDate`, `userId`, `signalType`, `occurredAt`, `receivedAt`, `payload`, `dedupKey`)
SELECT `id`, DATE(COALESCE(`observedAt`, `receivedAt`) + INTERVAL 9 HOUR), `userId`,
       `signalType`, `observedAt`, `receivedAt`, `payload`, `dedupKey`
FROM `verification_signals`
WHERE `signalType` IN ('SCREEN_TIME', 'WAKE', 'UNLOCK', 'APP_USAGE');

INSERT IGNORE INTO `verification_health_connect_signals`
    (`id`, `observedDate`, `userId`, `signalType`, `occurredAt`, `receivedAt`, `payload`, `dedupKey`)
SELECT `id`, DATE(COALESCE(`observedAt`, `receivedAt`) + INTERVAL 9 HOUR), `userId`,
       `signalType`, `observedAt`, `receivedAt`, `payload`, `dedupKey`
FROM `verification_signals`
WHERE `signalType` IN ('HEALTH', 'SLEEP', 'STEPS', 'DISTANCE');

-- 구 테이블은 남겨 둔다. 새 적재는 전부 3개 도메인으로 가고, 이관 결과를 운영에서
-- 확인한 뒤 별도 마이그레이션으로 떨어뜨린다 — 데이터가 있는 테이블을 같은 배포에서
-- 만들고 지우면 되돌릴 방법이 없다.
