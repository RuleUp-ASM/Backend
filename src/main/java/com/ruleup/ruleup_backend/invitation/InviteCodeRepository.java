package com.ruleup.ruleup_backend.invitation;

import com.ruleup.ruleup_backend.invitation.domain.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InviteCodeRepository extends JpaRepository<InviteCode, UUID> {
    Optional<InviteCode> findByUserId(UUID userId);
    Optional<InviteCode> findByCode(String code);
    boolean existsByCode(String code);
}
