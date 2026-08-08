-- 경로: src/main/resources/db/migration/V2__nickname_release_lock.sql
-- 닉네임 변경 시 이전 닉네임 1주일 잠금 (회원 정책 §3 "이전 닉네임 처리" — 사칭 방지).
--   · 잠금 기간 동안 "타인이" 등록·변경에 사용 불가 → released_by 는 본인 예외 판정에 쓴다.
--   · 탈퇴는 잠금 대상이 아니다(회원 정책 §6 — 복원 시 타인 선점 충돌 케이스를 전제한다).
--   · 임시 닉네임도 대상이 아니다 — 잠그는 값은 항상 사용자가 직접 고른 닉네임이다.

CREATE TABLE `nickname_release_locks` (
  -- users.nickname 과 동일한 collation 이어야 대소문자 판정이 갈리지 않는다
  `nickname`      VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  -- 잠금을 만든 사람 = 이 닉네임을 버린 사람. 본인은 잠금 기간에도 되돌릴 수 있다.
  -- 탈퇴로 유저 행이 사라져도 잠금 자체는 남아야 하므로 ON DELETE SET NULL.
  `released_by`   BINARY(16) NULL,
  `released_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `locked_until`  DATETIME(3) NOT NULL,
  PRIMARY KEY (`nickname`),
  CONSTRAINT `fk_nickname_release_locks_user`
      FOREIGN KEY (`released_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='변경으로 버려진 닉네임의 1주일 잠금 (회원 정책 §3)';

-- 만료 행 일괄 정리용 — 잠금 조회 자체는 PK(nickname)로 끝난다
CREATE INDEX `idx_nickname_release_locks_until` ON `nickname_release_locks` (`locked_until`);
