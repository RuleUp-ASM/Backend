package com.ruleup.ruleup_backend.notification.announcement;

import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 공지 팬아웃 잡 — 공지 1건을 약 2만 행으로 편다.
 *
 * <p><b>관리자 요청 트랜잭션 안에 두지 않는다.</b> 2만 행 INSERT 를 요청-응답 안에서 하면
 * 커넥션을 오래 잡고 실패 시 전부 롤백된다. 요청은 공지 원본만 저장하고 즉시 응답한 뒤
 * 이 잡이 청크 단위로 편다.
 *
 * <p><b>청크마다 자기 트랜잭션</b>이다. 중단돼도 이미 커밋된 청크는 남고,
 * {@code dedup_key = ANNOUNCEMENT:{user_id}:{announcement_id}} 의 UNIQUE 가 재개 시
 * 중복 적재를 막는다 — 그래서 진행 상황을 따로 기록할 필요가 없다.
 *
 * <p>공지는 {@code pushable = false} 라 큐에 들어가지 않는다. 알림 센터의 공지 탭에만 쌓인다.
 */
@Slf4j
@Component
public class AnnouncementFanoutJob {

    /** 한 트랜잭션에 담는 수신자 수. 커넥션 점유와 재개 단위의 절충이다. */
    private static final int CHUNK = 500;

    /** 한 번에 처리할 공지 수. 공지는 하루 몇 건이라 대기가 쌓일 일이 없다. */
    private static final int MAX_ANNOUNCEMENTS = 5;

    private final AnnouncementRepository announcementRepository;
    private final NotificationPublisher publisher;
    private final JdbcTemplate jdbc;
    private final AnnouncementFanoutJob self;

    public AnnouncementFanoutJob(AnnouncementRepository announcementRepository,
                                 NotificationPublisher publisher, JdbcTemplate jdbc,
                                 @org.springframework.context.annotation.Lazy AnnouncementFanoutJob self) {
        this.announcementRepository = announcementRepository;
        this.publisher = publisher;
        this.jdbc = jdbc;
        this.self = self;
    }

    /**
     * 대기 중인 공지를 편다. 요청 직후 바로 흐르도록 주기를 짧게 두되, 잡이 하는 일은
     * 대기 행이 없으면 인덱스 조회 한 번이라 비용이 없다.
     */
    @Scheduled(fixedDelay = 10_000L)
    public int fanOutPending() {
        List<Announcement> pending = announcementRepository
                .findPending(Instant.now(), Limit.of(MAX_ANNOUNCEMENTS));

        int total = 0;
        for (Announcement announcement : pending) total += fanOut(announcement);
        return total;
    }

    private int fanOut(Announcement announcement) {
        UUID cursor = null;
        int stored = 0;
        while (true) {
            List<UUID> chunk = nextRecipients(cursor);
            if (chunk.isEmpty()) break;
            stored += self.storeChunk(announcement, chunk);
            cursor = chunk.getLast();
        }
        self.complete(announcement.getId(), stored);
        log.info("공지 팬아웃 완료 — announcementId={} 수신자={}건", announcement.getId(), stored);
        return stored;
    }

    /**
     * 수신자 한 청크. {@code id} 오름차순 커서라 중간에 가입한 유저가 끼어들어도 이미 지나간
     * 구간을 다시 읽지 않는다. 탈퇴자는 제외한다 — 나간 사람에게 보낼 공지가 없다.
     */
    private List<UUID> nextRecipients(UUID cursor) {
        String sql = "SELECT id FROM users WHERE status <> 'WITHDRAWN' AND deleted_at IS NULL"
                + (cursor == null ? "" : " AND id > ?")
                + " ORDER BY id LIMIT " + CHUNK;
        return cursor == null
                ? jdbc.query(sql, (rs, row) -> uuid(rs.getBytes(1)))
                : jdbc.query(sql, (rs, row) -> uuid(rs.getBytes(1)), (Object) bytes(cursor));
    }

    /**
     * 청크 하나를 <b>자기 트랜잭션에서</b> 적재한다. 잡이 중단돼도 여기까지는 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int storeChunk(Announcement announcement, List<UUID> recipients) {
        // 공지 타입에는 기본 딥링크가 없다 — 갈 곳이 사안마다 다르다. 발행 시 지정한 값이
        // 있으면 그것을 쓰고, 없으면 링크 없이 알림함에 머문다.
        String deeplink = announcement.getDeepLink();

        return publisher.publishAll(recipients.stream()
                .map(userId -> {
                    NotificationEvent event = NotificationEvent.of(userId,
                            NotificationType.ANNOUNCEMENT,
                            announcement.getTitle(), announcement.getBody(),
                            Map.of(NotificationParams.ANNOUNCEMENT_ID,
                                    announcement.getId().toString()));
                    return (deeplink == null || deeplink.isBlank())
                            ? event : event.withDeeplink(deeplink);
                })
                .toList()).size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID announcementId, int recipients) {
        announcementRepository.findById(announcementId)
                .ifPresent(a -> a.markFannedOut(recipients, Instant.now()));
    }

    private static UUID uuid(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }

    private static byte[] bytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
