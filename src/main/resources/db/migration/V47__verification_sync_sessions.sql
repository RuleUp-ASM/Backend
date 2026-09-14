-- =====================================================================
-- 인트로가 발급한 sync 세션을 남긴다 (인증 백엔드 4-4 「신호 sync — AT + 활성 기기 검증」)
--
-- 지금까지 인트로는 sessionId 를 즉석에서 만들어 내려보내고 어디에도 적지 않았다. 그러면
-- 세션은 이름만 있고 아무것도 붙잡지 못한다 — 어느 기기·앱 버전이 어떤 수집 정책을 받아
-- 갔는지 알 수 없고, 올라온 신호를 그 정책과 대조할 수도 없다.
--
-- 행 하나가 「이 기기가 이 정책으로 수집을 시작했다」는 선언이다. sync 는 자기 세션을
-- 들고 와 마지막 접촉 시각을 갱신하고, 그 값으로 <b>오래 신호가 없는 기기</b>를 골라
-- FCM 기동을 건다(백엔드 4-5).
--
-- 유저당 여러 행이 쌓인다(기기 교체·재설치). 활성 기기는 users.device_id 가 정하고
-- 여기는 이력이라, 오래된 세션은 보관 기간이 지나면 정리한다.
-- =====================================================================

CREATE TABLE `verification_sync_sessions` (
    `id`         binary(16)  NOT NULL COMMENT '세션 ID — 인트로 응답의 sessionId',
    `userId`     binary(16)  NOT NULL,
    `deviceId`   varchar(64) NULL COMMENT '인트로 당시 기기. 활성 기기 판정은 users.device_id 가 원본',
    `appVersion` varchar(32) NULL,
    `sdkInt`     int         NULL COMMENT '수집 주기 산정 근거 — 기종별 차이를 나중에 되짚는다',
    `issuedAt`   datetime(3) NOT NULL,
    `lastSeenAt` datetime(3) NULL COMMENT '이 세션으로 마지막 sync 가 들어온 시각',
    PRIMARY KEY (`id`),
    -- 「이 유저의 최근 세션」 조회 — 신호 미수신 감지가 이 순서로 읽는다.
    KEY `idx_sync_session_user` (`userId`, `issuedAt` DESC),
    CONSTRAINT `fk_sync_sessions_user` FOREIGN KEY (`userId`)
        REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='인트로가 발급한 sync 세션 — 어느 기기가 어떤 정책으로 수집을 시작했는지';
