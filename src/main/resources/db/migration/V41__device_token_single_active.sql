-- =====================================================================
-- V41: 이미 쌓인 중복 활성 기기 토큰 정리 — 알림 백엔드 6절(토큰 위생)
--
--  V36 이 `isActive` 를 DEFAULT 1 로 붙이면서, 그때까지 쌓여 있던 행이 전부 활성으로 켜졌다.
--  단일 활성 기기 정책은 V36 이후의 등록 경로(`deactivateOthers`)에서만 지켜지므로, 그 전에 기기를
--  바꾼 사용자는 지금도 옛 기기 토큰이 활성으로 남아 있다 — 한 사람의 알림이 두 기기에 동시에 뜬다.
--  플래그만 추가하고 과거분을 정리하지 않으면 정책이 신규 등록자에게만 적용되는 셈이다.
--
--  유저마다 가장 최근에 쓰인 토큰 하나만 남기고 나머지를 내린다. 지우지 않는 이유는 V36 과
--  같다 — 「그 기기가 언제 왜 빠졌는지」를 CS 가 볼 자리가 있어야 한다.
--
--  순서 기준은 lastSeenAt, 동시각이면 createdAt, 그것도 같으면 id 로 끊는다. 기준이 하나라도
--  비면 임의의 행이 남아 「방금 쓰던 기기가 조용히 죽는」 결과가 된다.
--
--  파생 테이블을 두 겹으로 감싼 것은 의도적이다. MySQL 은 갱신 대상 테이블을 FROM 절에서 그대로
--  참조하면 1093 으로 거절하는데, 한 겹 더 감싸면 구체화(materialize)돼 그 제약을 피한다.
-- =====================================================================

UPDATE `DeviceToken` d
    JOIN (
        SELECT `id` FROM (
            SELECT k.`id`,
                   ROW_NUMBER() OVER (
                       PARTITION BY k.`userId`
                       ORDER BY k.`lastSeenAt` DESC, k.`createdAt` DESC, k.`id` DESC
                   ) AS rn
              FROM `DeviceToken` k
             WHERE k.`isActive` = 1
        ) ranked
        WHERE ranked.rn > 1
    ) stale ON stale.`id` = d.`id`
SET d.`isActive` = 0;
