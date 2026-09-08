package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 알림 파기 배치 — 보관 6개월.
 *
 * <p>아침 요약·미발송 보정 배치는 사라졌다. 야간 보류는 <b>컨슈머 정지</b>로 구현되므로 보류분이
 * DB 가 아니라 SQS 에 남고, 08:00 에 컨슈머가 수신을 재개하면 그대로 흘러 나간다. 미발송을
 * DB 에서 찾아 재시도하던 경로는 큐의 가시성 타임아웃이 대신한다.
 *
 * <p>실행 시각은 <b>02:00~03:00 점검 창</b>과 00시 인증 판정 배치를 피해 03:10 이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationBatch {

    /** 한 번에 지울 행 수. 일 12~14만 행이면 30청크 이내로 끝난다. */
    private static final int CHUNK = 5_000;

    private final NotificationRepository notificationRepository;

    /**
     * 보관 기간 경과분 정리. <b>하드 삭제</b>다 — 고지 성립은 보관 기간 안에서만 다투므로
     * 6개월이 지난 행을 남길 이유가 없고, 남기면 알림 센터 인덱스만 무겁게 만든다.
     *
     * <p>발송 기록이 FK 로 매달려 있던 {@code notification_deliveries} 가 사라져 선행 삭제가
     * 필요 없어졌다.
     */
    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public int purgeExpired() {
        Instant threshold = Instant.now().minus(NotificationService.RETENTION);
        List<Notification> expired = notificationRepository
                .findByCreatedAtBefore(threshold, Limit.of(CHUNK));
        if (expired.isEmpty()) return 0;

        notificationRepository.deleteAllInBatch(expired);
        log.info("알림 보관 기간 경과분 정리 — {}건", expired.size());
        return expired.size();
    }
}
