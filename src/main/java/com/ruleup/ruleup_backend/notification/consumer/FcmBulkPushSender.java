package com.ruleup.ruleup_backend.notification.consumer;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;
import com.ruleup.ruleup_backend.push.AndroidNotificationChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FCM 묶음 전송 — {@code sendEach(List&lt;Message&gt;)}.
 *
 * <p>{@code sendEachForMulticast} 는 쓰지 않는다. 알림마다 {@code notificationId}·{@code deeplink}
 * 가 달라 payload 를 공유할 수 없다.
 *
 * <p>payload 는 {@code notification} 과 {@code data} 를 <b>함께</b> 싣는다(공통 7절).
 * {@code data} 만 쓰면 일부 Android 제조사에서 앱 종료 상태에 전달되지 않고, {@code notification}
 * 만 쓰면 {@code notificationId} 와 {@code deeplink} 를 실을 수 없다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.fcm", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class FcmBulkPushSender implements BulkPushSender {

    /** 잠금화면에 뜨는 길이. 알림함은 전문을 그대로 보여 준다. */
    private static final int BODY_LIMIT = 200;

    /** 재시도해도 소용이 있는 오류 — 이때만 SQS 메시지를 남긴다. */
    private static final Set<MessagingErrorCode> RETRYABLE = Set.of(
            MessagingErrorCode.UNAVAILABLE, MessagingErrorCode.INTERNAL,
            MessagingErrorCode.QUOTA_EXCEEDED);

    /** 재시도해도 소용없는 토큰 오류 — 기기를 비활성화한다. */
    private static final Set<MessagingErrorCode> DEAD_TOKEN = Set.of(
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT);

    private final FirebaseMessaging firebaseMessaging;

    @Override
    public List<PushOutcome> send(List<PushRequest> requests) {
        List<PushOutcome> outcomes = new ArrayList<>(requests.size());
        for (PushRequest request : requests) outcomes.add(sendOne(request));
        return outcomes;
    }

    /**
     * 한 알림의 모든 기기로. 단일 활성 기기 정책이라 보통 토큰 1개지만, 전환 중에는 여럿일 수 있다.
     *
     * <p>토큰 하나라도 성공하면 성공으로 본다 — 사용자에게 도달했기 때문이다.
     */
    private PushOutcome sendOne(PushRequest request) {
        List<Message> messages = request.tokens().stream()
                .map(token -> build(token, request.message())).toList();
        try {
            BatchResponse response = firebaseMessaging.sendEach(messages);
            if (response.getSuccessCount() > 0) return PushOutcome.success(request.notificationId());

            List<String> dead = new ArrayList<>();
            boolean retryable = false;
            String errorCode = null;
            for (int i = 0; i < response.getResponses().size(); i++) {
                SendResponse r = response.getResponses().get(i);
                if (r.isSuccessful() || r.getException() == null) continue;
                MessagingErrorCode code = r.getException().getMessagingErrorCode();
                errorCode = String.valueOf(code);
                if (DEAD_TOKEN.contains(code)) dead.add(request.tokens().get(i));
                if (RETRYABLE.contains(code)) retryable = true;
            }
            return PushOutcome.failed(request.notificationId(), errorCode, retryable, dead);
        } catch (Exception e) {
            // 호출 자체가 실패했다 — 네트워크·타임아웃이라 재시도 가치가 있다.
            log.warn("FCM 묶음 전송 실패 notificationId={}: {}", request.notificationId(), e.toString());
            return PushOutcome.failed(request.notificationId(),
                    e.getClass().getSimpleName(), true, List.of());
        }
    }

    private Message build(String token, NotificationMessage n) {
        return Message.builder()
                .setToken(token)
                .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setTitle(n.title())
                        .setBody(truncate(n.body()))
                        .build())
                .putAllData(data(n))
                .setAndroidConfig(AndroidConfig.builder()
                        // Doze 유예를 피해 08:00 발송이 지연되지 않게 한다.
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                // 유형별로 채널을 나누지 않는다. 채널 ID 는 배포 후 사실상 바꿀 수
                                // 없어 그룹 체계가 굳기 전에는 단일 채널로 간다(공통 7-1).
                                .setChannelId(AndroidNotificationChannels.DEFAULT)
                                .build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .build();
    }

    /** payload 합계 4KB 상한이 있어 본문을 자른다. 전문은 알림 센터가 들고 있다. */
    private static String truncate(String body) {
        if (body == null) return "";
        return body.length() <= BODY_LIMIT ? body : body.substring(0, BODY_LIMIT);
    }

    private static Map<String, String> data(NotificationMessage n) {
        Map<String, String> data = new LinkedHashMap<>();
        // 클라이언트가 알림 tag 로 쓴다 — at-least-once 라 같은 알림이 두 번 와도 트레이에서 덮인다.
        data.put("notificationId", n.id().toString());
        data.put("type", n.type());
        data.put("toggle_group", n.toggleGroup().name());
        if (n.challengeId() != null) data.put("challenge_id", n.challengeId().toString());
        if (n.deeplink() != null) data.put("deeplink", n.deeplink());
        return data;
    }
}
