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
 *
 * <p>발급하는 {@code sessionId} 는 <b>저장한다</b>. 즉석에서 만들어 내려보내기만 하면 세션은
 * 이름뿐이고, 어느 기기가 어떤 정책을 받아 갔는지도 그 뒤로 신호가 오는지도 알 수 없다.
 */
@Service
@RequiredArgsConstructor
public class VerificationIntroService {

    private static final int BACKOFF_MAX_SEC = 14400;     // 4시간
    private static final double BACKOFF_FACTOR = 2.0;

    private final com.ruleup.ruleup_backend.verification.service.DeviceSyncPolicyService syncPolicy;
    private final UserRepository userRepository;
    private final VerificationSyncSessionStore sessionStore;

    public VerificationIntroResponse resolve(UUID userId, VerificationIntroRequest req) {
        Cadence on = new Cadence(true, null);
        Collection collection = new Collection(on, on, on, on, on);
        int flushIntervalSec = syncPolicy.forUser(userRepository.findById(userId).orElse(null));
        Instant now = Instant.now();
        UUID sessionId = sessionStore.issue(userId,
                (req != null) ? req.deviceId() : null,
                (req != null) ? req.appVersion() : null,
                (req != null && req.deviceProfile() != null) ? req.deviceProfile().sdkInt() : null,
                now);
        return new VerificationIntroResponse(
                now.toEpochMilli(),
                flushIntervalSec,
                collection,
                new Backoff(BACKOFF_MAX_SEC, BACKOFF_FACTOR),
                sessionId.toString()
        );
    }
}
