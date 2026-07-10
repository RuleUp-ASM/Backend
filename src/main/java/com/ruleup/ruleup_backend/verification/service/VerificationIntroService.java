package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroRequest;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse.Backoff;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse.Cadence;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 0 인트로(§0.3): 디바이스 프로필·권한 스냅샷을 받아 sync 정책을 회신.
 * flushIntervalSec은 sync ACK와 동일하게 {@link FlushIntervalPolicy}(기기 스펙 기반)로 산정 — 두 엔드포인트 일관.
 */
@Service
@RequiredArgsConstructor
public class VerificationIntroService {

    private static final int BACKOFF_MAX_SEC = 14400;     // 4시간
    private static final double BACKOFF_FACTOR = 2.0;

    private final UserRepository userRepository;

    public VerificationIntroResponse resolve(UUID userId, VerificationIntroRequest req) {
        Cadence on = new Cadence(true, null);
        Collection collection = new Collection(on, on, on, on);
        int flushIntervalSec = FlushIntervalPolicy.forUser(userRepository.findById(userId).orElse(null));
        return new VerificationIntroResponse(
                Instant.now().toEpochMilli(),
                flushIntervalSec,
                collection,
                new Backoff(BACKOFF_MAX_SEC, BACKOFF_FACTOR),
                UUID.randomUUID().toString()
        );
    }
}
