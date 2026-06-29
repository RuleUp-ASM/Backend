package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WatcherOtpRepository extends JpaRepository<WatcherOtp, UUID> {

    /** 해당 초대의 가장 최근 OTP(재발송 쿨다운 검사용). */
    Optional<WatcherOtp> findTopByInvitationIdOrderByCreatedAtDesc(UUID invitationId);
}
