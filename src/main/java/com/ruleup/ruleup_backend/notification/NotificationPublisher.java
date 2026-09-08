package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import com.ruleup.ruleup_backend.notification.queue.NotificationQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 알림 발행 — <b>적재 → 커밋 → enqueue</b>.
 *
 * <h4>적재가 곧 고지 성립이고, 도메인 커밋과 원자적이다</h4>
 * 발행부의 트랜잭션에 합류해 {@code notifications} 에 직접 INSERT 한다. 판정이 커밋됐으면 고지도
 * 커밋된 것이므로 <b>적재 누락이라는 실패 모드가 없다</b>. 아웃박스를 걷어낸 이유가 이것이다 —
 * 그 구조는 「릴레이 5회 실패 = 적재 누락」이라는 실패 모드를 스스로 만들고 그것을 알람으로
 * 감시하는 형태였다.
 *
 * <h4>적재 경로에 조건 분기가 하나도 없다</h4>
 * 토글·음소거·야간·억제는 전부 <b>푸시만</b> 막고 컨슈머가 발송 직전에 평가한다. 차단도 여기서
 * 거르지 않는다 — 차단은 감시자 초대 요청을 막는 <b>관계 생성 게이트</b>라(공통 2절) 관계가
 * 없으면 알림도 발생하지 않는다.
 *
 * <h4>enqueue 는 커밋 후다 — dual write 를 알고 간다</h4>
 * 커밋과 enqueue 사이에서 죽으면 그 알림은 푸시가 안 나간다. 적재는 이미 끝났으므로 절대 규칙 1은
 * 지켜지고, 공통 3절이 푸시 유실을 허용한다. 반대로 커밋 <b>전에</b> 보내면 롤백된 알림의 푸시가
 * 잠금화면에 떠 있는 상태가 되고 그건 되돌릴 방법이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPublisher {

    /** SQS 메시지 하나에 담는 알림 수. 100건 ≈ 40KB 로 256KB 한도에 여유가 있다. */
    private static final int BATCH_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final NotificationQueue queue;

    /**
     * 알림 1건 발행. <b>발행부의 트랜잭션 안에서</b> 적재하고, 커밋 후 큐에 넣는다.
     *
     * @return 적재된 행. 같은 멱등키로 이미 적재돼 있으면 empty 다.
     */
    public Optional<Notification> publish(NotificationEvent event) {
        List<Notification> stored = publishAll(List.of(event));
        return stored.isEmpty() ? Optional.empty() : Optional.of(stored.getFirst());
    }

    /**
     * 여러 건 발행 — 챌린지 스코프 팬아웃처럼 수신자가 N명일 때 쓴다. 배치 INSERT 후 묶음으로
     * enqueue 하므로 SQS 호출이 100건에 한 번이다.
     */
    public List<Notification> publishAll(List<NotificationEvent> events) {
        Instant now = Instant.now();
        List<Notification> stored = new ArrayList<>(events.size());

        for (NotificationEvent event : events) {
            Notification row = store(event, now);
            if (row != null) stored.add(row);
        }
        if (stored.isEmpty()) return List.of();

        // 푸시 대상만 큐로. 공지는 pushable=false 라 적재만 되고 알림 센터에만 남는다.
        List<NotificationMessage> messages = stored.stream()
                .filter(n -> NotificationType.find(n.getType())
                        .map(NotificationType::isPushable).orElse(false))
                .map(NotificationMessage::from)
                .toList();
        if (!messages.isEmpty()) enqueueAfterCommit(messages);

        return stored;
    }

    /**
     * 적재 1건. 멱등키가 이미 있으면 <b>조용히 건너뛴다</b> — 발행 재시도이지 오류가 아니다.
     *
     * <p>겹침을 예외로 잡지 않고 <b>먼저 조회해서</b> 거른다. 적재가 도메인 트랜잭션 안이라,
     * 제약 위반이 flush 에서 터지면 영속성 컨텍스트가 죽어 <b>강퇴 판정까지 롤백된다</b>.
     * 진짜 경합(같은 키가 동시에 두 번)은 {@code uq_notifications_dedup} 이 끝까지 막고,
     * 이 조회는 흔한 재시도 경로가 예외로 가지 않게 하는 장치다.
     *
     * <p>키가 없는 발행은 경고만 남기고 적재한다. 절대 규칙 1이 멱등 보호보다 위다.
     */
    private Notification store(NotificationEvent event, Instant now) {
        String dedupKey = event.dedupKey();
        if (dedupKey == null) {
            log.warn("알림 멱등키 없음 — 발행 재시도가 중복 적재될 수 있다. type={} user={}",
                    event.type(), event.userId());
        } else if (notificationRepository.existsByDedupKey(dedupKey)) {
            log.debug("같은 멱등키로 이미 적재돼 있다. key={}", dedupKey);
            return null;
        }
        return notificationRepository.save(Notification.of(
                event.userId(), event.type(), event.title(), event.body(), event.challengeId(),
                event.resolvedDeeplink(), dedupKey, event.suppressKey(), now));
    }

    /**
     * 커밋 이후 큐로. 트랜잭션이 없으면(배치·잡) 즉시 보낸다.
     *
     * <p>여기서 터져도 도메인으로 올리지 않는다. 적재는 이미 끝났고 푸시 유실은 허용된다.
     */
    private void enqueueAfterCommit(List<NotificationMessage> messages) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeEnqueue(messages);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeEnqueue(messages);
            }
        });
    }

    private void safeEnqueue(List<NotificationMessage> messages) {
        for (int from = 0; from < messages.size(); from += BATCH_SIZE) {
            List<NotificationMessage> chunk =
                    messages.subList(from, Math.min(from + BATCH_SIZE, messages.size()));
            try {
                queue.enqueue(chunk);
            } catch (RuntimeException e) {
                log.warn("알림 큐 투입 실패 — 적재는 유효하므로 푸시만 잃는다. count={} err={}",
                        chunk.size(), e.toString());
            }
        }
    }
}
