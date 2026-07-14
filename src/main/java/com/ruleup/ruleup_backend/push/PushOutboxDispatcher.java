package com.ruleup.ruleup_backend.push;

import com.ruleup.ruleup_backend.push.domain.PushOutbox;
import com.ruleup.ruleup_backend.push.repository.PushOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 고스트 푸시 큐 발송 스윕(§8.5). 예정 시각이 지난 PENDING 을 FOR UPDATE SKIP LOCKED 로 선점해
 * {@link PushSender} 로 무음 푸시를 보낸다(FCM 활성 시 실제 전송, 아니면 로깅 스텁).
 *  - 전송 성공 → SENT. 전송 예외 → PENDING 유지(다음 스윕 재시도). 등록 토큰이 없어도 sendSilent 는 no-op 이므로 SENT.
 *  - 다중 인스턴스에서도 잠긴 행은 건너뛰어 중복 발송 없음.
 */
@Service
@RequiredArgsConstructor
public class PushOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushOutboxDispatcher.class);
    private static final int CLAIM_LIMIT = 200;

    private final PushOutboxRepository pushOutboxRepository;
    private final PushSender pushSender;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void dispatchDue() {
        Instant now = Instant.now();
        List<PushOutbox> due = pushOutboxRepository.claimDue(now, CLAIM_LIMIT);
        if (due.isEmpty()) return;

        int sent = 0;
        for (PushOutbox o : due) {
            try {
                pushSender.sendSilent(o.getUserId(), toSilentPush(o));
                o.markSent(now);
                sent++;
            } catch (Exception e) {
                // 이 건만 PENDING 유지 → 다음 스윕 재시도.
                log.warn("고스트 푸시 발송 실패 id={}: {}", o.getId(), e.getMessage());
            }
        }
        if (sent > 0) log.info("고스트 푸시(권한공백) {}건 발송(선점 {}건)", sent, due.size());
    }

    private SilentPush toSilentPush(PushOutbox o) {
        if (SilentPush.TYPE_PERMISSION_REQUIRED.equals(o.getType())) {
            return SilentPush.permissionRequired(o.getChallengeId().toString(), o.getSignalType());
        }
        // 알 수 없는 타입은 challengeId 만 실어 보낸다(방어적).
        return new SilentPush(o.getType(), Map.of("challengeId", o.getChallengeId().toString()));
    }
}
