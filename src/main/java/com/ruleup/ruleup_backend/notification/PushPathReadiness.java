package com.ruleup.ruleup_backend.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 기동 직후 <b>푸시가 실제로 나갈 수 있는 상태인가</b>를 한 줄로 적는다.
 *
 * <p>푸시 경로는 설정이 빠져도 조용히 꺼진다 — 큐 URL 이 비면 프로듀서·컨슈머가 아예 배선되지
 * 않고, FCM 이 꺼져 있으면 전송기가 스텁으로 떨어진다. 둘 다 적재는 정상이라 알림함에는
 * 계속 쌓이고, 그래서 밖에서 보면 「서버는 보냈는데 폰에 안 온다」로만 보인다. QA 가 이
 * 구간에서 여러 번 멈췄다(NOTI-13 · NOTI-14 · WAT-11).
 *
 * <p>값을 검사하지도, 고치지도 않는다. 꺼진 이유를 <b>트래픽이 흐르기 전에</b> 적어 둘 뿐이다.
 */
@Slf4j
@Component
public class PushPathReadiness {

    private final String queueUrl;
    private final boolean consumerEnabled;
    private final boolean fcmEnabled;

    public PushPathReadiness(@Value("${app.notification.queue.url:}") String queueUrl,
                             @Value("${app.notification.queue.consumer-enabled:true}") boolean consumerEnabled,
                             @Value("${app.fcm.enabled:false}") boolean fcmEnabled) {
        this.queueUrl = queueUrl == null ? "" : queueUrl.trim();
        this.consumerEnabled = consumerEnabled;
        this.fcmEnabled = fcmEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        List<String> missing = new ArrayList<>();
        if (queueUrl.isEmpty()) missing.add("app.notification.queue.url(NOTIFICATION_QUEUE_URL) 이 비어 있다");
        if (!consumerEnabled) missing.add("app.notification.queue.consumer-enabled 가 꺼져 있다");
        if (!fcmEnabled) missing.add("app.fcm.enabled(FCM_ENABLED) 가 꺼져 있다");

        if (missing.isEmpty()) {
            log.info("푸시 경로 준비됨 — 큐·컨슈머·FCM 이 모두 켜져 있다.");
            return;
        }
        log.warn("푸시가 나가지 않는 상태다 — 알림함 적재는 정상이다. 원인: {}", String.join(" / ", missing));
    }
}
