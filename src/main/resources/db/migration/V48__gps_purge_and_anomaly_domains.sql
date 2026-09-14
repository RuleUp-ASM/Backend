-- =====================================================================
-- ① GPS 원본의 건별 파기 타이머  ② 이상탐지 입력 3개 저장 도메인
--    (인증 공통 5-3 · 5-6 · 백엔드 4-1 「이상탐지 입력」 · 정합화 1절)
--
-- ① 왜 파티션 파기로 충분하지 않은가
--    판정 원본 파티션은 3일이면 떨어진다. 그것만 보면 위치정보법의 「목적 달성 시 즉시
--    파기」보다 오히려 짧다. 그런데 스펙이 요구하는 것은 <b>건별 확정 시각 + 보관 기간</b>
--    이다 — 고정 일괄 시각으로 잡으면 확정 전인 건까지 지워질 수 있기 때문이다. 확정이
--    밀리거나 보관 기간을 이상탐지 윈도우에 맞춰 늘리는 순간, 일괄 삭제는 아직 판정도
--    끝나지 않은 좌표를 먼저 지운다. 그래서 확정 시각을 기준으로 한 타이머를 행에 둔다.
--
--    verification_id 는 그 좌표를 소비한 판정이다. 한 좌표가 여러 챌린지 판정에 공유되므로
--    「마지막으로 확정된 판정」을 적는다 — 파기 타이머의 기준 시각을 읽기 위한 것이지
--    소유 관계가 아니다. 파티션 테이블이라 FK 는 걸 수 없다.
--
-- ② 이상탐지 입력을 원본에서 떼어 낸다
--    지금까지는 배제 근거만 범용 signal_exclusions 에 쌓였고, 성공 인증에서 뽑은 feature 는
--    어디에도 없었다. 그러면 온라인 이상탐지는 원본을 뒤질 수밖에 없는데 원본은 사흘이면
--    사라진다. 성공 판정에서 <b>탐지에 필요한 값만</b> 골라 30일 보관한다.
--    좌표 원본은 넣지 않는다 — mock 여부·정확도·이동 이상 구간 같은 파생 feature 만 둔다
--    (GPS 조기 파기 정책이 30일 anomaly 보관보다 우선한다).
-- =====================================================================

-- ① GPS 원본 파기 타이머 ------------------------------------------------
ALTER TABLE `verification_location_signals`
    ADD COLUMN `verificationId` binary(16) NULL
        COMMENT '이 좌표를 소비한 판정 — 파기 타이머의 기준 확정 시각을 여기서 읽는다' AFTER `userId`,
    ADD COLUMN `purgeAfter` datetime(3) NULL
        COMMENT '확정 시각 + 보관 기간. 고정 일괄 시각이 아니라 확정 전 건이 지워지지 않는다' AFTER `payload`,
    ADD COLUMN `purgedAt` datetime(3) NULL
        COMMENT '실제 파기 시각 — 이후에는 판정 결과와 요약만 남는다' AFTER `purgeAfter`;

-- 파기 배치의 핵심 인덱스. `purgedAt IS NULL AND purgeAfter <= now` 가 이 인덱스를 탄다.
-- 미파기 행이 소수이므로 purgedAt 을 선행에 둔다. 파티션 로컬 인덱스라 파티션마다 작다.
CREATE INDEX `idx_location_purge` ON `verification_location_signals` (`purgedAt`, `purgeAfter`);

-- ② 이상탐지 입력 3개 도메인 --------------------------------------------
-- 원본 저장 구조와 같은 셋으로 유지해 변환 경로를 단순하게 둔다(백엔드 4-1).
-- observedDate 일별 RANGE 파티셔닝 — 만료분은 행 삭제가 아니라 파티션 DROP 으로 걷는다.

CREATE TABLE `anomaly_location_events` (
    `id`             binary(16)  NOT NULL COMMENT 'UUIDv7',
    `observedDate`   date        NOT NULL COMMENT 'KST 발생일 — 파티션 키',
    `userId`         binary(16)  NOT NULL,
    `verificationId` binary(16)  NULL COMMENT '근거가 된 판정. 게이트 단계 이상은 NULL',
    `eventType`      varchar(10) NOT NULL COMMENT 'FEATURE(성공 인증 추출) / HYGIENE(신호 위생 이상)',
    `signalType`     varchar(20) NOT NULL,
    `anomalyType`    varchar(20) NULL COMMENT 'MOCK / VPN / UNTRUSTED_SOURCE / ACCURACY_LOW / MANUAL_ENTRY',
    `signalCount`    int         NOT NULL DEFAULT 1,
    `features`       json        NULL COMMENT '탐지 feature — 좌표 원본이 아니라 파생값만',
    `observedAt`     datetime(3) NOT NULL,
    PRIMARY KEY (`observedDate`, `id`),
    KEY `idx_anomaly_location_user` (`userId`, `observedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='위치 이상탐지 입력 — 좌표 원본이 아니라 파생 feature 만 30일'
PARTITION BY RANGE (TO_DAYS(`observedDate`)) (
    PARTITION `pFuture` VALUES LESS THAN MAXVALUE
);

CREATE TABLE `anomaly_device_usage_events` (
    `id`             binary(16)  NOT NULL COMMENT 'UUIDv7',
    `observedDate`   date        NOT NULL COMMENT 'KST 발생일 — 파티션 키',
    `userId`         binary(16)  NOT NULL,
    `verificationId` binary(16)  NULL,
    `eventType`      varchar(10) NOT NULL COMMENT 'FEATURE / HYGIENE',
    `signalType`     varchar(20) NOT NULL,
    `anomalyType`    varchar(20) NULL,
    `signalCount`    int         NOT NULL DEFAULT 1,
    `features`       json        NULL,
    `observedAt`     datetime(3) NOT NULL,
    PRIMARY KEY (`observedDate`, `id`),
    KEY `idx_anomaly_device_usage_user` (`userId`, `observedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='앱 사용·화면 이벤트 이상탐지 입력'
PARTITION BY RANGE (TO_DAYS(`observedDate`)) (
    PARTITION `pFuture` VALUES LESS THAN MAXVALUE
);

CREATE TABLE `anomaly_health_connect_events` (
    `id`             binary(16)  NOT NULL COMMENT 'UUIDv7',
    `observedDate`   date        NOT NULL COMMENT 'KST 발생일 — 파티션 키',
    `userId`         binary(16)  NOT NULL,
    `verificationId` binary(16)  NULL,
    `eventType`      varchar(10) NOT NULL COMMENT 'FEATURE / HYGIENE',
    `signalType`     varchar(20) NOT NULL,
    `anomalyType`    varchar(20) NULL,
    `signalCount`    int         NOT NULL DEFAULT 1,
    `features`       json        NULL,
    `observedAt`     datetime(3) NOT NULL,
    PRIMARY KEY (`observedDate`, `id`),
    KEY `idx_anomaly_health_connect_user` (`userId`, `observedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='걸음·거리·수면 이상탐지 입력'
PARTITION BY RANGE (TO_DAYS(`observedDate`)) (
    PARTITION `pFuture` VALUES LESS THAN MAXVALUE
);
