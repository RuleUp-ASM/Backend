package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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
public class NotificationBatch {

    /**
     * 한 번에 지울 행 수. 일 12~14만 행이면 30청크 이내로 끝난다.
     *
     * <p>프로퍼티로 빼 둔 이유는 하나뿐이다 — <b>반복 소진을 테스트가 5,000행 없이 검증</b>할 수
     * 있어야 한다. 운영에서 이 값을 만질 일은 없다.
     */
    private final int chunk;

    /**
     * 한 실행에서 돌 청크 수 상한 — 5,000 × 200 = 100만 행.
     *
     * <p>무한 루프 방어이자 <b>02:00~03:00 점검 창을 침범하지 않기 위한 상한</b>이다. 여기 걸리면
     * 그날 다 지우지 못한 것이므로 경고를 남긴다 — 상한에 매일 닿는다면 적재량이 전제를 넘었다는
     * 뜻이고, 청크를 키우는 게 아니라 보관 기간이나 실행 주기를 다시 봐야 한다.
     */
    private static final int MAX_CHUNKS = 200;

    private final NotificationRepository notificationRepository;
    private final NotificationBatch self;

    public NotificationBatch(NotificationRepository notificationRepository,
                             @Lazy NotificationBatch self,
                             @Value("${app.notification.purge.chunk:5000}") int chunk) {
        this.notificationRepository = notificationRepository;
        this.self = self;
        this.chunk = chunk;
    }

    /**
     * 보관 기간 경과분 정리. <b>하드 삭제</b>다 — 고지 성립은 보관 기간 안에서만 다투므로
     * 6개월이 지난 행을 남길 이유가 없고, 남기면 알림 센터 인덱스만 무겁게 만든다.
     *
     * <p><b>청크를 다 돌 때까지 반복한다.</b> 한 청크만 지우고 끝내면 일 12~14만 건이 쌓이는
     * 규모에서 하루 5,000건씩만 빠져 적체가 영구히 늘어난다.
     *
     * <p>청크마다 <b>자기 트랜잭션</b>이다(공지 팬아웃과 같은 패턴). 100만 행을 한 트랜잭션에
     * 묶으면 언두 로그가 커지고 중단 시 전부 롤백된다 — 이미 지운 청크는 남는 편이 낫다.
     *
     * <p>발송 기록이 FK 로 매달려 있던 {@code notification_deliveries} 가 사라져 선행 삭제가
     * 필요 없어졌다.
     */
    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    public int purgeExpired() {
        // 기준 시각은 시작 시점에 한 번 고정한다 — 청크마다 다시 계산하면 실행 중 경계를 넘는
        // 행이 섞여 들어와 "이번 실행에서 지운 범위"가 불분명해진다.
        Instant threshold = Instant.now().minus(NotificationService.RETENTION);

        int total = 0;
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            int deleted = self.purgeChunk(threshold);
            total += deleted;
            if (deleted < chunk) {   // 마지막 청크 — 남은 행이 없다
                if (total > 0) log.info("알림 보관 기간 경과분 정리 — {}건", total);
                return total;
            }
        }
        log.warn("알림 파기 상한 도달 — {}건 정리하고 중단한다. 남은 행은 다음 실행이 이어받는다.", total);
        return total;
    }

    /** 청크 하나를 자기 트랜잭션에서 지운다. 중단돼도 여기까지는 커밋돼 있다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeChunk(Instant threshold) {
        List<Notification> expired = notificationRepository
                .findByCreatedAtBefore(threshold, Limit.of(chunk));
        if (expired.isEmpty()) return 0;

        notificationRepository.deleteAllInBatch(expired);
        return expired.size();
    }
}
