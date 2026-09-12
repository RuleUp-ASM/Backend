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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    /**
     * 묶음 전체를 <b>{@code sendEach} 한 번</b>으로 보낸다.
     *
     * <p>구 구조는 알림마다 {@code sendEach} 를 따로 불렀다. 단일 활성 기기 정책이라 보통 토큰이
     * 1개이므로 사실상 <b>알림 수만큼 Firebase 왕복이 직렬로</b> 일어났고, 한 묶음이 최대 1,000건
     * (SQS 100건 × 수신 10건)이라 08:30 소진 목표를 맞출 수 없었다. 묶음 전송의 목적이 이것이다.
     *
     * <p>{@code sendEachForMulticast} 는 여전히 쓰지 않는다 — 알림마다 {@code notificationId}·
     * {@code deeplink} 가 달라 payload 를 공유할 수 없다.
     */
    @Override
    public List<PushOutcome> send(List<PushRequest> requests) {
        // (요청, 토큰) 쌍을 한 줄로 편다. owner·tokenOf 가 응답 i 번째를 어느 알림·어느 토큰이
        // 냈는지 되짚는 색인이다 — BatchResponse 는 보낸 순서대로만 돌아온다.
        List<Message> messages = new ArrayList<>();
        List<PushRequest> owner = new ArrayList<>();
        List<String> tokenOf = new ArrayList<>();
        for (PushRequest request : requests) {
            for (String token : request.tokens()) {
                messages.add(build(token, request.message()));
                owner.add(request);
                tokenOf.add(token);
            }
        }
        if (messages.isEmpty()) return requests.stream().map(FcmBulkPushSender::noToken).toList();

        try {
            return fold(requests, owner, tokenOf, toTokenResults(firebaseMessaging.sendEach(messages)));
        } catch (Exception e) {
            // 호출 자체가 실패했다 — 네트워크·타임아웃이라 묶음 전체가 재시도 대상이다.
            log.warn("FCM 묶음 전송 실패 count={}: {}", messages.size(), e.toString());
            return requests.stream().map(r -> PushOutcome.failed(r.notificationId(),
                    e.getClass().getSimpleName(), true, List.of())).toList();
        }
    }

    /**
     * 토큰 1건의 결과 — <b>Firebase 타입을 경계에서 끊는다</b>.
     *
     * <p>{@code SendResponse} 는 final 이고 생성자가 패키지 전용이라 테스트가 만들 수 없다.
     * 접기 규칙(성공 우선·죽은 토큰 귀속·재시도 판정)이야말로 틀리면 조용히 아픈 부분이므로,
     * 그 규칙만 순수 함수로 떼어 목 없이 검증한다({@link DispatchDecision} 과 같은 이유다).
     */
    record TokenResult(boolean successful, MessagingErrorCode errorCode) {

        static TokenResult ok() {
            return new TokenResult(true, null);
        }

        static TokenResult error(MessagingErrorCode code) {
            return new TokenResult(false, code);
        }
    }

    private static List<TokenResult> toTokenResults(BatchResponse response) {
        return response.getResponses().stream()
                .map(row -> row.isSuccessful() ? TokenResult.ok()
                        : TokenResult.error(row.getException() == null
                                ? null : row.getException().getMessagingErrorCode()))
                .toList();
    }

    /**
     * 토큰 단위 결과를 알림 단위로 접는다. <b>토큰 하나라도 성공하면 그 알림은 성공</b>이다 —
     * 사용자에게 도달했기 때문이다.
     *
     * @param owner   {@code results[i]} 를 낸 요청. 보낸 순서와 응답 순서가 같다는 계약에 기댄다
     * @param tokenOf {@code results[i]} 가 쓴 토큰. 죽은 토큰을 낸 알림에 귀속시키는 데 쓴다
     */
    static List<PushOutcome> fold(List<PushRequest> requests, List<PushRequest> owner,
                                  List<String> tokenOf, List<TokenResult> results) {
        Set<UUID> succeeded = new HashSet<>();
        Map<UUID, String> errorCode = new HashMap<>();
        Set<UUID> retryable = new HashSet<>();
        Map<UUID, List<String>> dead = new HashMap<>();

        for (int i = 0; i < results.size() && i < owner.size(); i++) {
            UUID id = owner.get(i).notificationId();
            TokenResult result = results.get(i);
            if (result.successful()) {
                succeeded.add(id);
                continue;
            }
            MessagingErrorCode code = result.errorCode();
            if (code == null) continue;
            errorCode.put(id, code.name());
            // 죽은 토큰은 그 토큰을 실제로 낸 알림에 귀속시킨다 — 엉뚱한 기기를 내리면 안 된다.
            if (DEAD_TOKEN.contains(code)) {
                dead.computeIfAbsent(id, k -> new ArrayList<>()).add(tokenOf.get(i));
            }
            if (RETRYABLE.contains(code)) retryable.add(id);
        }

        return requests.stream().map(request -> {
            UUID id = request.notificationId();
            if (succeeded.contains(id)) return PushOutcome.success(id);
            if (request.tokens().isEmpty()) return noToken(request);
            return PushOutcome.failed(id, errorCode.get(id), retryable.contains(id),
                    dead.getOrDefault(id, List.of()));
        }).toList();
    }

    /**
     * 보낼 토큰이 없다 — 판정 ⑧단계가 걸렀어야 하지만 조회와 전송 사이에 기기가 빠지면 생긴다.
     * <b>재시도 대상이 아니다</b>: 다시 받아도 토큰은 여전히 없고 메시지만 큐에 남는다.
     */
    private static PushOutcome noToken(PushRequest request) {
        return PushOutcome.failed(request.notificationId(), "NO_TOKEN", false, List.of());
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
