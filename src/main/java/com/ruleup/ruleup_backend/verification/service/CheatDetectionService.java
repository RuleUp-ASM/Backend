package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.service.CheatKickOutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.score.CheatScoreOutboxHandler;
import com.ruleup.ruleup_backend.verification.domain.CheatDetection;
import com.ruleup.ruleup_backend.verification.repository.CheatDetectionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h4>집행 신호는 아웃박스로 낸다</h4>
 * 인메모리 이벤트로 내면 수신측이 한 번 실패했을 때 주울 근거가 없다. 게다가 기록이 이미 있는
 * 재호출은 이벤트를 다시 내지 않아 <b>최초 실패가 영구 미집행으로 굳었다</b>. 발행 의사를 검출
 * 기록과 같은 커밋에 적고, 이미 확정된 건에도 같은 키로 적재를 시도한다 — 아웃박스 dedup 이
 * 중복은 막고, 적재되지 않았던 건은 이때 복구된다.
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
    private final OutboxService outbox;
    private final OutboxDispatcher outboxDispatcher;

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
                    // 재시도이지 오류가 아니다. 그래도 적재는 다시 시도한다 — 아웃박스 dedup 이
                    // 중복을 막고, 최초 확정 때 적재되지 않았던 건은 여기서 복구된다.
                    log.info("부정행위 검출이 이미 확정돼 있다 — 집행 적재만 보강한다. verificationId={}",
                            verificationDailyId);
                    enqueueEnforcement(existing);
                    return existing;
                })
                .orElseGet(() -> record(userId, challengeId, verificationDailyId, pattern, detectedAt));
    }

    private CheatDetection record(UUID userId, UUID challengeId, UUID verificationDailyId,
                                  Map<String, Object> pattern, Instant detectedAt) {
        CheatDetection detection = repository.save(CheatDetection.of(
                userId, challengeId, verificationDailyId, pattern, detectedAt));
        enqueueEnforcement(detection);

        log.info("부정행위 검출 확정 userId={} challengeId={} verificationId={}",
                userId, challengeId, verificationDailyId);
        return detection;
    }

    /**
     * 집행 신호 적재 — 강퇴와 감점을 <b>따로</b> 적는다.
     *
     * <p>한 메시지로 묶으면 감점이 실패할 때마다 강퇴 경로가 함께 다시 돌고, 반대도 마찬가지다.
     * 검출 id 를 dedup 키로 써서 같은 검출이 두 번 적재되지 않는다 — 누적 카운트가 없는 제재라
     * 두 번 나가면 −100 이 된다.
     */
    private void enqueueEnforcement(CheatDetection detection) {
        String detectionId = detection.getId().toString();
        String userId = detection.getUserId().toString();
        String challengeId = detection.getChallengeId().toString();
        outbox.enqueue(CheatKickOutboxHandler.OUTBOX_TYPE,
                new CheatKickOutboxHandler.Payload(userId, challengeId, detectionId),
                CheatKickOutboxHandler.OUTBOX_TYPE + ":" + detectionId);
        outbox.enqueue(CheatScoreOutboxHandler.OUTBOX_TYPE,
                new CheatScoreOutboxHandler.Payload(userId, challengeId, detectionId),
                CheatScoreOutboxHandler.OUTBOX_TYPE + ":" + detectionId);
        // 커밋 직후 한 번 흘린다 — 제재는 즉시성이 중요하다. 실패해도 스윕이 다시 집으므로
        // 이 호출은 지연을 줄일 뿐 유실을 막는 장치가 아니다.
        outboxDispatcher.requestFlush();
    }
}
