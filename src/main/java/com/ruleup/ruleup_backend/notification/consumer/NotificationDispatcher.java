package com.ruleup.ruleup_backend.notification.consumer;

import com.ruleup.ruleup_backend.notification.NotificationMuteRepository;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.NotificationSettingRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.UserNotificationSetting;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import com.ruleup.ruleup_backend.push.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 발송 — 묶음 하나를 판정하고 FCM 으로 보낸 뒤 결과를 남긴다.
 *
 * <h4>묶음 조회는 메시지당 각 1회</h4>
 * 설정 · 음소거 · 기기 · 억제 이력을 IN 으로 한 번씩만 읽는다. <b>건당 조회 금지</b> —
 * 08:00 에 8만 건이 소진되는데 N+1 이 나면 RDS 가 밀린다.
 *
 * <h4>pushed_at 은 성공했고 억제 대상인 행만</h4>
 * 억제를 쓰지 않는 16종까지 갱신하면 08:00 피크의 쓰기가 5배가 된다. 그리고 <b>억제된 행에는
 * 절대 남기지 않는다</b> — 남기면 이벤트가 인터벌보다 잦을 때 직전 행이 항상 윈도우 안에 있어
 * 억제가 영원히 풀리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    /** 억제 조회의 시간 범위 — 최장 인터벌이 1주다. 범위를 줘야 인덱스가 커버링으로 동작한다. */
    private static final Duration LONGEST_INTERVAL = Duration.ofDays(7);

    /** 발송 로그 — CloudWatch 메트릭 필터가 이 이름으로 건다. DB 에 발송 로그 테이블을 두지 않는다. */
    private static final Logger PUSH_LOG = LoggerFactory.getLogger("notification.push");

    private final NotificationRepository notificationRepository;
    private final NotificationSettingRepository settingRepository;
    private final NotificationMuteRepository muteRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final BulkPushSender pushSender;

    /**
     * 묶음 하나를 처리한다.
     *
     * @return 메시지 순서 그대로의 처리 결과. 컨슈머가 이 값으로 SQS 삭제 여부를 정한다.
     */
    @Transactional
    public List<DispatchOutcome> dispatch(List<NotificationMessage> messages, Instant now) {
        if (messages.isEmpty()) return List.of();

        Map<UUID, DispatchInputs> inputs = loadInputs(messages, now);

        List<DispatchOutcome> outcomes = new ArrayList<>(messages.size());
        List<PushRequest> toSend = new ArrayList<>();
        Map<UUID, NotificationMessage> sending = new HashMap<>();

        for (NotificationMessage message : messages) {
            DispatchInputs in = inputs.get(message.userId());
            DispatchDecision decision = DispatchDecision.decide(message, in, now);

            if (decision.deferred()) {
                outcomes.add(DispatchOutcome.deferred(message.id()));
                continue;
            }
            if (!decision.shouldSend()) {
                logResult(message, "SUPPRESSED", decision.suppressedReason(), null);
                outcomes.add(DispatchOutcome.suppressed(message.id(), decision.suppressedReason()));
                continue;
            }
            logAttempt(message, now);
            toSend.add(new PushRequest(message, tokensOf(message.userId())));
            sending.put(message.id(), message);
            outcomes.add(null);   // 전송 결과를 받아 채운다
        }

        if (!toSend.isEmpty()) fillSendResults(outcomes, messages, toSend, sending, now);
        return outcomes;
    }

    // ===== 묶음 조회 =====

    private Map<UUID, DispatchInputs> loadInputs(List<NotificationMessage> messages, Instant now) {
        List<UUID> userIds = messages.stream().map(NotificationMessage::userId).distinct().toList();

        Map<UUID, UserNotificationSetting> settings = settingRepository.findByUserIdIn(userIds)
                .stream().collect(Collectors.toMap(UserNotificationSetting::getUserId, s -> s));

        Map<UUID, Set<UUID>> mutes = new HashMap<>();
        muteRepository.findByUserIdIn(userIds).forEach(m -> mutes
                .computeIfAbsent(m.getUserId(), k -> new HashSet<>()).add(m.getChallengeId()));

        Set<UUID> withDevice = new HashSet<>(deviceTokenRepository.findUserIdsWithActiveToken(userIds));

        Map<UUID, Map<String, Instant>> lastPushed = loadSuppressHistory(messages, userIds, now);

        Map<UUID, DispatchInputs> inputs = new HashMap<>();
        for (UUID userId : userIds) {
            inputs.put(userId, new DispatchInputs(
                    // 행이 없으면 전부 ON 으로 해석한다 — 가입 시 백필하지 않기 때문이다.
                    settings.getOrDefault(userId, UserNotificationSetting.defaults(userId, now)),
                    mutes.getOrDefault(userId, Set.of()),
                    lastPushed.getOrDefault(userId, Map.of()),
                    withDevice.contains(userId)));
        }
        return inputs;
    }

    /**
     * 억제 이력 — 억제 키가 있는 메시지가 하나도 없으면 조회 자체를 하지 않는다.
     * 22종 중 6종만 키를 쓰므로 보통 이 쿼리는 나가지 않는다.
     */
    private Map<UUID, Map<String, Instant>> loadSuppressHistory(List<NotificationMessage> messages,
                                                                List<UUID> userIds, Instant now) {
        List<String> keys = messages.stream()
                .map(NotificationMessage::suppressKey)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (keys.isEmpty()) return Map.of();

        Map<UUID, Map<String, Instant>> byUser = new HashMap<>();
        for (Object[] row : notificationRepository.findLastPushedBySuppressKey(
                userIds, keys, now.minus(LONGEST_INTERVAL))) {
            byUser.computeIfAbsent((UUID) row[0], k -> new HashMap<>())
                    .put((String) row[1], (Instant) row[2]);
        }
        return byUser;
    }

    private List<String> tokensOf(UUID userId) {
        return deviceTokenRepository.findActiveTokens(userId);
    }

    // ===== 전송 결과 반영 =====

    private void fillSendResults(List<DispatchOutcome> outcomes, List<NotificationMessage> messages,
                                 List<PushRequest> toSend, Map<UUID, NotificationMessage> sending,
                                 Instant now) {
        Map<UUID, PushOutcome> results = pushSender.send(toSend).stream()
                .collect(Collectors.toMap(PushOutcome::notificationId, o -> o));

        List<UUID> stamp = new ArrayList<>();
        List<String> deadTokens = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            if (outcomes.get(i) != null) continue;

            NotificationMessage message = messages.get(i);
            PushOutcome result = results.get(message.id());
            if (result == null) {   // 전송기가 결과를 빠뜨렸다 — 재시도 대상으로 둔다
                outcomes.set(i, DispatchOutcome.retryable(message.id(), "NO_RESULT"));
                continue;
            }
            deadTokens.addAll(result.deadTokens());

            if (result.success()) {
                logResult(message, "SUCCESS", null, null);
                // 억제 대상 타입만 찍는다.
                if (message.suppressKey() != null) stamp.add(message.id());
                outcomes.set(i, DispatchOutcome.sent(message.id()));
            } else {
                logResult(message, "FAILED", null, result.errorCode());
                outcomes.set(i, result.retryable()
                        ? DispatchOutcome.retryable(message.id(), result.errorCode())
                        : DispatchOutcome.failed(message.id(), result.errorCode()));
            }
        }

        if (!stamp.isEmpty()) notificationRepository.markPushed(stamp, now);
        // 재시도해도 소용없는 토큰은 지우지 않고 비활성화한다 — CS 대응 근거로 남긴다.
        if (!deadTokens.isEmpty()) deviceTokenRepository.deactivate(deadTokens, now);
        sending.clear();
    }

    // ===== 관측 =====
    // 로그에 FCM 토큰과 알림 본문을 넣지 않는다. userId·notificationId(UUID)까지만.

    private void logAttempt(NotificationMessage m, Instant now) {
        PUSH_LOG.info("{\"evt\":\"push.attempt\",\"notificationId\":\"{}\",\"userId\":\"{}\","
                + "\"type\":\"{}\",\"at\":\"{}\"}", m.id(), m.userId(), m.type(), now);
    }

    private void logResult(NotificationMessage m, String result, SuppressedReason reason,
                           String errorCode) {
        PUSH_LOG.info("{\"evt\":\"push.result\",\"notificationId\":\"{}\",\"userId\":\"{}\","
                        + "\"type\":\"{}\",\"result\":\"{}\",\"suppressedReason\":\"{}\","
                        + "\"errorCode\":\"{}\",\"at\":\"{}\"}",
                m.id(), m.userId(), m.type(), result,
                reason == null ? "" : reason.name(),
                errorCode == null ? "" : errorCode, Instant.now());
    }

    /** 컨슈머가 SQS 메시지를 지울지 정하는 데 필요한 것만 담는다. */
    public record DispatchOutcome(UUID notificationId, boolean sent, boolean deferred,
                                  boolean retryable, SuppressedReason suppressedReason,
                                  String errorCode) {

        static DispatchOutcome sent(UUID id) {
            return new DispatchOutcome(id, true, false, false, null, null);
        }

        static DispatchOutcome deferred(UUID id) {
            return new DispatchOutcome(id, false, true, false, null, null);
        }

        static DispatchOutcome suppressed(UUID id, SuppressedReason reason) {
            return new DispatchOutcome(id, false, false, false, reason, null);
        }

        static DispatchOutcome failed(UUID id, String errorCode) {
            return new DispatchOutcome(id, false, false, false, null, errorCode);
        }

        static DispatchOutcome retryable(UUID id, String errorCode) {
            return new DispatchOutcome(id, false, false, true, null, errorCode);
        }

        /**
         * SQS 메시지를 지워도 되는가. <b>발송·억제·읽음 스킵은 전부 지운다</b> —
         * 재시도 가능 에러와 야간 보류만 남겨 가시성 만료 후 다시 받는다.
         */
        public boolean deletable() {
            return !deferred && !retryable;
        }
    }
}
