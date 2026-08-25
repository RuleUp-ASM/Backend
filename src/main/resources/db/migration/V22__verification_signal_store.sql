-- 인증 신호 원본 저장 + 영속 멱등(백엔드 테크스펙 §4-3, 2026-08-25).
--
-- 지금까지 신호는 평가에만 쓰이고 버려졌다. 멱등도 평가 결과 JSON(method_result.evidence) 안의
-- 워터마크에만 기대고 있어서, 그 처리를 갖추지 않은 평가기(앱 사용 시간)는 구간 재전송에
-- 사용 시간을 두 번 더했다.
--
-- 원본을 보관하는 이유는 세 가지다.
--   · 정확도·mock 여부·기록 방식·출처 같은 부정행위 검증의 근거가 원본에만 있다
--   · GPS 반경·기상 허용 범위 같은 기준값을 배포 없이 조정하려면 과거 신호로 다시 계산할 수 있어야 한다
--   · 확정 이후 도착한 신호도 판정에는 안 쓰지만 이상탐지 자료로는 쓴다
--
-- 보관 기간·저장 위치(오브젝트 스토리지 이관)는 개인정보·인프라 정책과 함께 확정할 후속 작업이다.

CREATE TABLE `verification_signals` (
    `id`         binary(16)  NOT NULL,
    `userId`     binary(16)  NOT NULL,
    `dedupKey`   char(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                 COMMENT '멱등 키(SHA-256 hex). recordId 가 있으면 그것으로, 없으면 신호 내용 전체로 만든다',
    `signalType` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `observedAt` datetime(6) NULL COMMENT '신호 관측 시각(클라 선언). 파싱 불가면 NULL',
    `receivedAt` datetime(6) NOT NULL COMMENT '서버 수신 시각',
    `payload`    json        NOT NULL COMMENT '신호 원본. 압축·요약하지 않는다',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_verification_signals_dedup` (`userId`, `dedupKey`),
    KEY `idx_verification_signals_user_observed` (`userId`, `observedAt`),
    KEY `idx_verification_signals_received` (`receivedAt`),
    CONSTRAINT `fk_verification_signals_user` FOREIGN KEY (`userId`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='수신한 인증 신호 원본. uq(userId, dedupKey)가 재전송 중복을 끊는다';
