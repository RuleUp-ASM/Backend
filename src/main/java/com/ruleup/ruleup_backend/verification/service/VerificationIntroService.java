package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.dto.VerificationIntroRequest;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse.Backoff;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse.Cadence;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse.Collection;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 0 인트로(§0.3): 디바이스 프로필·권한 스냅샷을 받아 sync 정책을 회신.
 * MVP: 정적 기본 정책 + 디바이스 세션 식별자. (권한 차등 정책은 추후.)
 */
@Service
public class VerificationIntroService {

    private static final int FLUSH_INTERVAL_SEC = 1800;   // 30분 (sync 주기와 동일)
    private static final int BACKOFF_MAX_SEC = 14400;     // 4시간
    private static final double BACKOFF_FACTOR = 2.0;

    public VerificationIntroResponse resolve(UUID userId, VerificationIntroRequest req) {
        Cadence on = new Cadence(true, null);
        Collection collection = new Collection(on, on, on, on);
        return new VerificationIntroResponse(
                Instant.now().toEpochMilli(),
                FLUSH_INTERVAL_SEC,
                collection,
                new Backoff(BACKOFF_MAX_SEC, BACKOFF_FACTOR),
                UUID.randomUUID().toString()
        );
    }
}
