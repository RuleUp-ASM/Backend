package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.user.domain.NicknameReleaseLock;
import org.springframework.data.jpa.repository.JpaRepository;

/** nickname_release_locks 접근 — 조회는 PK(nickname) 하나로 끝난다. */
public interface NicknameReleaseLockRepository extends JpaRepository<NicknameReleaseLock, String> {
}
