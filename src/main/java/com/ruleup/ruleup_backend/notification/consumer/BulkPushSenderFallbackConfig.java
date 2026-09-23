package com.ruleup.ruleup_backend.notification.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * FCM 자격증명이 없을 때의 스텁 — 로컬·CI 기동을 막지 않는다.
 *
 * <p><b>성공으로 위장하지 않는다.</b> 예전에는 성공을 돌려줬고, 그 결과 아무것도 나가지 않은
 * 상태에서 {@code push.result "SUCCESS"} 로그가 찍히고 {@code pushed_at} 까지 채워졌다.
 * 푸시가 안 온다는 제보를 받은 사람이 그 로그와 컬럼을 보면 「서버는 보냈다」로 읽는다 —
 * 실제로 QA 가 그 지점에서 막혔다(NOTI-13 · NOTI-14 · WAT-11).
 *
 * <p>대신 <b>재시도 불가</b> 실패로 돌린다. 자격증명은 재시도로 생기지 않으므로 메시지는
 * 그대로 큐에서 지워지고, 자격증명 없는 환경에서 큐가 영원히 비지 않는 문제도 없다.
 * {@code pushed_at} 이 비는 덕에, 나중에 FCM 을 켰을 때 24시간 억제에 걸려 첫 푸시가
 * 조용히 삼켜지는 일도 없다.
 */
@Slf4j
@Configuration
public class BulkPushSenderFallbackConfig {

    /** 전송기가 없었다는 사실을 로그·지표에서 한 눈에 가르는 코드. */
    static final String PUSH_DISABLED = "PUSH_DISABLED";

    @Bean
    @ConditionalOnMissingBean(BulkPushSender.class)
    BulkPushSender loggingBulkPushSender() {
        return requests -> {
            log.warn("FCM 전송기가 없어 푸시가 나가지 않는다 — 적재는 정상이다."
                    + " app.fcm.enabled 와 자격증명을 확인한다. count={}", requests.size());
            return requests.stream()
                    .map(r -> PushOutcome.failed(r.notificationId(), PUSH_DISABLED, false, List.of()))
                    .toList();
        };
    }
}
