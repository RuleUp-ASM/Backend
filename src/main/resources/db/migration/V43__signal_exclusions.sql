-- =====================================================================
-- 신호 위생 배제 로그 (인증 공통 5-3 `signal_exclusions` · 백엔드 4-1-1)
--
-- 스펙이 검증을 두 층으로 갈랐다.
--   ① 신호 위생  — VPN · 좌표 튀김 · 센서 이상 같은 **단일 신호의 단순 이상**.
--                 부정행위로 판정하지 않고 그 신호만 빼고 나머지로 판정한다.
--   ② 이상패턴 탐지 — 여러 인증 건에 걸친 누적 패턴. 여기서만 검출로 확정한다.
-- 이 테이블은 ①의 기록이고 ②의 입력이다. **제재가 아니다** — 회사 VPN 을 켜 둔 사람과
-- 위치를 속이는 사람을 신호 하나로는 구분할 수 없기 때문이다.
--
-- 원본을 통째로 남기지 않는다. 최소 근거(누가·어떤 신호·왜·언제)만 남겨 개인정보 보관
-- 범위를 좁힌다(백엔드 4-1-1).
-- =====================================================================

CREATE TABLE `signal_exclusions` (
    `id`                  binary(16)  NOT NULL COMMENT 'UUIDv7',
    `userId`              binary(16)  NOT NULL,
    -- 게이트 단계 배제는 챌린지별 판정 이전이라 귀속할 판정이 없다. 판정 단계 배제만 채워진다.
    `verificationDailyId` binary(16)  NULL COMMENT '배제가 영향을 준 판정. 게이트 단계면 NULL',
    `signalType`          varchar(20) NOT NULL COMMENT 'LOCATION / GEOFENCE / HEALTH / SCREEN_TIME / WAKE / SLEEP',
    `reason`              varchar(20) NOT NULL
        COMMENT 'MOCK / VPN / UNTRUSTED_SOURCE / ACCURACY_LOW / MANUAL_ENTRY',
    -- 신호 하나에 한 행이면 sync 한 번에 수백 행이 된다. 같은 사유·같은 타입은 묶어 세고,
    -- 탐지가 보는 것도 "반복되는가"라 건수면 충분하다.
    `signalCount`         int         NOT NULL DEFAULT 1 COMMENT '이번에 배제한 신호 수',
    `excludedAt`          datetime(3) NOT NULL,
    PRIMARY KEY (`id`),
    -- 이상탐지 입력 집계 — 특정 유저에게 배제가 반복되는지 기간으로 본다.
    KEY `idx_exclusion_anomaly` (`userId`, `excludedAt`, `reason`),
    CONSTRAINT `fk_signal_exclusions_user` FOREIGN KEY (`userId`)
        REFERENCES `users` (`id`) ON DELETE CASCADE,
    -- 판정이 지워져도 배제 근거는 남는다 — 탐지 입력이지 판정의 부속물이 아니다.
    CONSTRAINT `fk_signal_exclusions_daily` FOREIGN KEY (`verificationDailyId`)
        REFERENCES `VerificationDaily` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='신호 위생 배제 로그 — 제재가 아니라 이상패턴 탐지의 입력';
