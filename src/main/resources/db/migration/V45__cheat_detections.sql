-- =====================================================================
-- 부정행위 검출 확정 기록 (인증 공통 5-3 `cheat_detections` · 5-7 판정 신호 외부 발행)
--
-- **이상패턴 탐지로 확정된 건만 들어온다.** 단일 신호의 이상(mock 좌표 하나, VPN 한 구간)은
-- 여기 오지 않고 `signal_exclusions` 에서 끝난다 — 회사 VPN 을 켠 사람과 위치를 속이는
-- 사람을 신호 하나로 구분할 수 없기 때문이다.
--
-- **누적 카운트가 없다.** 확정된 검출 1건이 곧바로 해당 챌린지 강퇴 · 영구 차단 · −50 이다.
-- 그래서 이 테이블은 "몇 번째인가"를 세는 용도가 아니라 **무엇을 근거로 확정했는가**를 남긴다.
--
-- 이력은 지우지 않고 계정에 누적 보존한다. 다만 이 값으로 계정 제재가 자동 승격되지는 않는다 —
-- 계정 단위 제재는 운영자가 이 이력을 보고 직권으로 판단한다.
-- =====================================================================

CREATE TABLE `cheat_detections` (
    `id`                  binary(16)  NOT NULL COMMENT 'UUIDv7',
    `userId`              binary(16)  NOT NULL,
    -- 집계 단위가 챌린지다. 영구 차단도 계정이 아니라 그 방에 걸린다.
    `challengeId`         binary(16)  NOT NULL COMMENT '집계 단위 — 차단은 이 방에만 걸린다',
    -- 검출로 무효가 된 판정. 같은 판정으로 두 번 확정되면 강퇴·감점이 두 번 나가므로 UNIQUE 다.
    `verificationDailyId` binary(16)  NOT NULL COMMENT '무효화된 인증',
    `pattern`             json        NOT NULL COMMENT '탐지 근거 — 반복 위조·불가능한 이동량·자동화 도구 패턴',
    `detectedAt`          datetime(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_cheat_verification` (`verificationDailyId`),
    -- 마이페이지 검출 이력 · 운영자가 반복·상습성을 판단하는 근거.
    KEY `idx_cheat_user` (`userId`, `detectedAt` DESC),
    -- 해당 챌린지 영구 차단 판정 — 가입 게이트가 참조한다.
    KEY `idx_cheat_challenge` (`challengeId`, `userId`),
    CONSTRAINT `fk_cheat_detections_user` FOREIGN KEY (`userId`)
        REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='이상패턴 탐지로 확정된 부정행위 — 1건이 곧 강퇴·영구 차단·−50';
