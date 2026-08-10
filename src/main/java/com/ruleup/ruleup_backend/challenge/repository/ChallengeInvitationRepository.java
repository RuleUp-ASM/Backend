package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChallengeInvitationRepository extends JpaRepository<ChallengeInvitation, UUID> {
}
