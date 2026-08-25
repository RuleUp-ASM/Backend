package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.VerificationAckResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 판정 결과 모달을 봤다는 확인(ack). 호출하면 이후 today 응답에서 {@code unacknowledgedResult} 가 사라진다.
 * <b>멱등</b> — 중복 호출은 첫 확인 시각을 유지하고 그대로 성공한다.
 */
@Service
@RequiredArgsConstructor
public class VerificationAckService {

    private final VerificationDailyRepository dailyRepo;

    @Transactional
    public VerificationAckResponse acknowledge(UUID userId, UUID verificationId) {
        VerificationDaily daily = dailyRepo.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));
        if (!daily.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);   // 본인 건이 아님 — 존재를 알리지 않는다
        }
        daily.acknowledge(Instant.now());
        return new VerificationAckResponse(true);
    }
}
