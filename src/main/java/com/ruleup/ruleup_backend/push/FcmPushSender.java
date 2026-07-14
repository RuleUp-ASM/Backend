package com.ruleup.ruleup_backend.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 실제 FCM 전송 {@link PushSender} 구현(무음/데이터 전용). {@code app.fcm.enabled=true} 일 때만 뜨고
 * {@code @Primary} 로 {@link LoggingPushSender} 스텁 대신 주입된다.
 *
 * <p>대상 유저의 등록 토큰마다 data-only 메시지를 보낸다(notification 블록 없음 → 화면 알림 미표시,
 * 앱만 백그라운드에서 깨어남). Android 는 high priority, iOS 는 {@code content-available} 로 백그라운드 수신.
 * 전송 결과가 UNREGISTERED/INVALID 면 그 토큰을 정리한다. 전송 예외는 호출부(배치/스윕)로 전파하지 않는다.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "app.fcm", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenService deviceTokenService;

    @Override
    public void sendSilent(UUID userId, SilentPush push) {
        List<String> tokens = deviceTokenService.tokensOf(userId);
        if (tokens.isEmpty()) return;   // 계약: 등록 토큰 없으면 no-op

        for (String token : tokens) {
            try {
                firebaseMessaging.send(build(token, push));
            } catch (FirebaseMessagingException e) {
                MessagingErrorCode code = e.getMessagingErrorCode();
                if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                    deviceTokenService.remove(token);   // 죽은/잘못된 토큰 정리
                } else {
                    log.warn("FCM 전송 실패 userId={} code={}: {}", userId, code, e.getMessage());
                }
            } catch (Exception e) {
                log.warn("FCM 전송 예외 userId={}: {}", userId, e.getMessage());
            }
        }
    }

    private Message build(String token, SilentPush push) {
        Map<String, String> data = (push.data() != null) ? push.data() : Map.of();
        return Message.builder()
                .setToken(token)
                .putData("type", push.type())
                .putAllData(data)
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)   // Doze 중에도 앱을 깨울 수 있게
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "5")
                        .setAps(Aps.builder().setContentAvailable(true).build())   // iOS 무음 백그라운드
                        .build())
                .build();
    }
}
