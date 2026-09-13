package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.event.CheatDetectionConfirmed;
import com.ruleup.ruleup_backend.verification.domain.CheatDetection;
import com.ruleup.ruleup_backend.verification.repository.CheatDetectionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 부정행위 검출 <b>확정</b>의 단일 진입점 (인증 공통 5-7).
 *
 * <h4>인증 모듈은 신호를 내는 데까지만 책임진다</h4>
 * 확정을 기록하고 이벤트를 낸다. 강퇴·영구 차단은 방 내부가, −50 은 티어·점수가, 필수(A) 통지는
 * 알림이 한다. 이 경계를 코드로 고정해 두지 않으면 같은 집행이 여러 곳에 복제되고, 한 곳이
 * 빠지면 「강퇴는 됐는데 점수는 그대로」 같은 상태가 생긴다.
 *
 * <h4>확정 1건이 곧 제재다</h4>
 * 누적 카운트가 없다. 그래서 <b>같은 판정으로 두 번 확정되면 강퇴와 감점이 두 번 나간다</b> —
 * {@code uq_cheat_verification} 이 그것을 막고, 여기서는 그 충돌을 예외가 아니라 「이미 확정됨」
 * 으로 다룬다(재시도는 오류가 아니다).
 *
 * <h4>탐지 자체는 이 클래스의 일이 아니다</h4>
 * 어떤 패턴을 부정행위로 볼지는 실데이터 관측 후 확정할 값이고(오픈 이슈), 스펙도 탐지 엔진
 * 구현을 범위 밖으로 뒀다. 이 클래스는 <b>확정이 내려졌을 때 무슨 일이 일어나는가</b>를 고정한다.
 */
@Service
@RequiredArgsConstructor
public class CheatDetectionService {

    private static final Logger log = LoggerFactory.getLogger(CheatDetectionService.class);

    private final CheatDetectionRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 검출을 확정한다 — 기록 + 집행 신호 발행.
     *
     * @param pattern 탐지 근거. <b>비워 두지 않는다</b> — 설명할 수 없는 제재는 남기지 않는다
     * @return 새로 확정했으면 그 기록, 이미 확정된 판정이면 기존 기록
     */
    @Transactional
    public CheatDetection confirm(UUID userId, UUID challengeId, UUID verificationDailyId,
                                  Map<String, Object> pattern, Instant detectedAt) {
        return repository.findByVerificationDailyId(verificationDailyId)
                .map(existing -> {
                    // 재시도이지 오류가 아니다. 집행 신호를 다시 내면 강퇴·감점이 두 번 나간다.
                    log.info("부정행위 검출이 이미 확정돼 있다 — 집행을 다시 내지 않는다. verificationId={}",
                            verificationDailyId);
                    return existing;
                })
                .orElseGet(() -> record(userId, challengeId, verificationDailyId, pattern, detectedAt));
    }

    private CheatDetection record(UUID userId, UUID challengeId, UUID verificationDailyId,
                                  Map<String, Object> pattern, Instant detectedAt) {
        CheatDetection detection = repository.save(CheatDetection.of(
                userId, challengeId, verificationDailyId, pattern, detectedAt));

        // 집행은 커밋 이후에 각 도메인이 한다 — 수신측 장애가 검출 기록을 되돌리면 안 된다.
        eventPublisher.publishEvent(new CheatDetectionConfirmed(
                userId, challengeId, verificationDailyId, detection.getId(), detectedAt));

        log.info("부정행위 검출 확정 userId={} challengeId={} verificationId={}",
                userId, challengeId, verificationDailyId);
        return detection;
    }
}
