package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.Watcher;
import com.ruleup.ruleup_backend.watcher.domain.WatcherChannel;
import com.ruleup.ruleup_backend.watcher.domain.WatcherNotification;
import com.ruleup.ruleup_backend.watcher.infra.ContactCipher;
import com.ruleup.ruleup_backend.watcher.infra.SmsSender;
import com.ruleup.ruleup_backend.watcher.repository.WatcherNotificationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 감시자 실패 통지 발송 스윕(§9/§8.2/§11.4).
 *  - 예정 시각(scheduledAt)이 지난 PENDING 을 FOR UPDATE SKIP LOCKED 로 선점(다중 인스턴스 중복 발송 방지).
 *  - 감시자별로 묶어 1건 요약 발송(§8.2 — 동일 감시자 요약). 채널 분기: 유저=인앱, 비유저=SMS.
 *  - 발송 시점에 감시자가 ACTIVE 가 아니면(수신거부/해제) SKIPPED(§5.9 발송 중단).
 *  - 한 감시자 발송 실패는 그 그룹만 PENDING 유지(다음 스윕 재시도) — 다른 그룹에 영향 없음.
 */
@Service
@RequiredArgsConstructor
public class WatcherNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WatcherNotificationDispatcher.class);
    private static final int CLAIM_LIMIT = 200;

    private final WatcherNotificationRepository notificationRepository;
    private final WatcherRepository watcherRepository;
    private final NotificationPublisher notificationPublisher;
    private final SmsSender smsSender;
    private final ContactCipher contactCipher;
    private final UserRepository userRepository;

    @Value("${app.watcher.invite-base-url:https://ruleup.app}")
    private String inviteBaseUrl;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void dispatchDue() {
        Instant now = Instant.now();
        List<WatcherNotification> due = notificationRepository.claimDue(now, CLAIM_LIMIT);
        if (due.isEmpty()) return;

        // 감시자별로 묶어 1건 요약 발송.
        Map<UUID, List<WatcherNotification>> byWatcher = new LinkedHashMap<>();
        for (WatcherNotification n : due) {
            byWatcher.computeIfAbsent(n.getWatcherId(), k -> new java.util.ArrayList<>()).add(n);
        }

        for (var entry : byWatcher.entrySet()) {
            List<WatcherNotification> group = entry.getValue();
            Watcher watcher = watcherRepository.findById(entry.getKey()).orElse(null);

            // 발송 중단 조건(§5.9): 감시자 없음/비활성(수신거부·해제).
            if (watcher == null || watcher.getStatus() == null || !watcher.getStatus().isActive()) {
                group.forEach(WatcherNotification::markSkipped);
                continue;
            }

            try {
                send(watcher, group.size());
                group.forEach(n -> n.markSent(now));
            } catch (Exception e) {
                // 그룹만 PENDING 유지 → 다음 스윕에서 재시도.
                log.warn("감시자 통지 발송 실패 watcherId={}: {}", watcher.getId(), e.getMessage());
            }
        }
    }

    private void send(Watcher watcher, int count) {
        String nickname = userRepository.findById(watcher.getInviterUserId())
                .map(u -> u.visibleNicknameTo(null))
                .orElse("회원");
        String countPhrase = (count > 1) ? count + "건의 " : "";

        if (watcher.getChannel() == WatcherChannel.IN_APP) {
            // 인앱은 가벼운 톤(§8.2).
            notificationPublisher.publish(NotificationEvent.of(watcher.getWatcherUserId(),
                    NotificationType.PENALTY_FAILURE_SHARED,
                    "감시 알림",
                    nickname + "님이 " + countPhrase + "루틴 약속을 지키지 못했어요."));
        } else if (watcher.getChannel() == WatcherChannel.SMS) {
            if (watcher.getContactEnc() == null) return;   // 연락처 파기된 경우 발송 생략
            String phone = contactCipher.decrypt(watcher.getContactEnc());
            // 외부 SMS는 운영성·무겁게 + 수신거부 링크 필수(§9).
            String message = nickname + "님이 " + countPhrase + "루틴 약속을 지키지 못했어요.";
            smsSender.sendFailureNotice(phone, message, unsubscribeUrl(watcher));
        }
    }

    private String unsubscribeUrl(Watcher watcher) {
        return inviteBaseUrl + "/unsubscribe?token=" + watcher.getUnsubscribeToken();
    }
}
