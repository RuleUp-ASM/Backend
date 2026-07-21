package com.ruleup.ruleup_backend.invitation;

import com.ruleup.ruleup_backend.invitation.domain.InvitationSignup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvitationSignupRepository extends JpaRepository<InvitationSignup, UUID> {
    List<InvitationSignup> findByInviterUserIdOrderByOccurredAtAsc(UUID inviterUserId);
    boolean existsByInviteeUserId(UUID inviteeUserId);
}
