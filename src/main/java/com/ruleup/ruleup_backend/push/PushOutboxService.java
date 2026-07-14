package com.ruleup.ruleup_backend.push;

import com.ruleup.ruleup_backend.push.domain.PushOutbox;
import com.ruleup.ruleup_backend.push.repository.PushOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 고스트 푸시 큐 적재. 실시간 권한공백 이벤트 리스너가 호출한다.
 * 발송은 별도 스윕({@link PushOutboxDispatcher})이 트랜잭션 밖에서 수행한다.
 */
@Service
@RequiredArgsConstructor
public class PushOutboxService {

    private final PushOutboxRepository pushOutboxRepository;

    /**
     * 권한공백 1건 → 큐 적재. 멱등: 같은 유저×챌린지×날짜×타입이 이미 있으면 건너뛴다(sync 마다 스팸 방지).
     * 호출자(이벤트 리스너)의 트랜잭션 안에서 DB만 건드린다(외부 호출 없음).
     */
    public void enqueuePermissionGap(UUID userId, UUID challengeId, LocalDate targetDate,
                                     String signalType, Instant detectedAt) {
        if (pushOutboxRepository.existsByUserIdAndChallengeIdAndTargetDateAndType(
                userId, challengeId, targetDate, SilentPush.TYPE_PERMISSION_REQUIRED)) {
            return;
        }
        pushOutboxRepository.save(PushOutbox.enqueue(
                userId, challengeId, targetDate, SilentPush.TYPE_PERMISSION_REQUIRED, signalType, detectedAt));
    }
}
