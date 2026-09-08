-- =====================================================================
-- V36: 기기 토큰 비활성 플래그 — 알림 백엔드 6절(토큰 위생)
--
--  지금까지는 FCM 이 UNREGISTERED 를 주면 토큰 행을 **지웠다**. 지우면 CS 대응 근거가 사라진다 —
--  「알림이 안 와요」를 받았을 때 그 기기가 언제 왜 빠졌는지 볼 자리가 없다.
--
--  발송 대상은 is_active = 1 뿐이고, 비활성 행은 남겨 둔다.
-- =====================================================================

ALTER TABLE `DeviceToken`
    ADD COLUMN `isActive` TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '0 이면 발송 대상에서 제외. 지우지 않고 남겨 CS 근거로 쓴다' AFTER `platform`;

-- 컨슈머가 묶음 조회로 「이 유저들에게 활성 기기가 있는가」를 한 번에 묻는다.
-- userId 선행 + isActive 로 커버링이 되어 테이블을 읽지 않는다.
CREATE INDEX `ixDeviceTokenActive` ON `DeviceToken` (`userId`, `isActive`);
