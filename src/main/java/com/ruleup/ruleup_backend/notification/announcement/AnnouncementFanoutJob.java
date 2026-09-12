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
 * <p><b>청크마다 자기 트랜잭션</b>이다. 중단돼도 이미 커밋된 청크는 남고, 멱등 키의 UNIQUE 가
 * 재개 시 중복 적재를 막는다 — 그래서 진행 상황을 따로 기록할 필요가 없다. 키는 종류에 따라
 * {@code ANNOUNCEMENT:{user_id}:{announcement_id}} 또는 {@code MARKETING:{user_id}:{event_key}} 다.
 *
 * <p>운영 공지는 {@code pushable = false} 라 큐에 들어가지 않는다 — 알림 센터의 공지 탭에만 쌓인다.
 *
 * <p><b>광고({@code MARKETING})만 성격이 다르다.</b> 수신자가 동의자로 한정되고, 알림 탭에 쌓이며,
 * 푸시도 나간다. 여기서 수신자를 거르지 않으면 <b>미동의자의 알림함에 광고가 적재</b>되는데,
 * 그것은 발송 단계에서 푸시를 막는 것으로 되돌려지지 않는다.
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
            List<UUID> chunk = nextRecipients(announcement, cursor);
            if (chunk.isEmpty()) break;
            stored += self.storeChunk(announcement, chunk);
            cursor = chunk.getLast();
        }
        self.complete(announcement.getId(), stored);
        log.info("공지 팬아웃 완료 — announcementId={} kind={} 수신자={}건",
                announcement.getId(), announcement.getKind(), stored);
        return stored;
    }

    /**
     * 수신자 한 청크. {@code id} 오름차순 커서라 중간에 가입한 유저가 끼어들어도 이미 지나간
     * 구간을 다시 읽지 않는다. 탈퇴자는 제외한다 — 나간 사람에게 보낼 공지가 없다.
     *
     * <p><b>광고는 수신 동의자에게만</b> 간다. 발송 판정에도 동의 게이트가 있지만 그것은 푸시만
     * 막는다 — 여기서 거르지 않으면 미동의자의 <b>알림함에 광고가 적재</b>된다.
     * 동의 여부의 원본은 {@code user_agreement_states} 다(설정 토글이 아니다).
     */
    private List<UUID> nextRecipients(Announcement announcement, UUID cursor) {
        // 운영자 콘솔 계정은 앱을 쓰지 않는다 — 공지를 받을 알림함이 없는 것과 같다.
        String sql = announcement.getKind().isMarketing()
                ? "SELECT u.id FROM users u"
                + " JOIN user_agreement_states s ON s.user_id = u.id"
                + " WHERE u.status <> 'WITHDRAWN' AND u.deleted_at IS NULL AND u.role = 'MEMBER'"
                + " AND s.agreement_type = 'MARKETING' AND s.agreed = 1"
                + (cursor == null ? "" : " AND u.id > ?")
                + " ORDER BY u.id LIMIT " + CHUNK
                : "SELECT id FROM users WHERE status <> 'WITHDRAWN' AND deleted_at IS NULL"
                + " AND role = 'MEMBER'"
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
        // 있으면 그것을 쓰고, 없으면 링크 없이 알림함에 머문다. 마케팅도 같다(캠페인이 정한다).
        String deeplink = announcement.getDeepLink();
        boolean marketing = announcement.getKind().isMarketing();
        String id = announcement.getId().toString();

        // 마케팅은 캠페인 단위로 억제된다 — 공지 id 가 곧 campaign_id 이자 멱등 키다.
        // 그래서 같은 캠페인이 두 번 팬아웃돼도 적재는 한 번이고, 24시간 억제도 함께 걸린다.
        Map<String, String> params = marketing
                ? Map.of(NotificationParams.EVENT_KEY, id, NotificationParams.CAMPAIGN_ID, id)
                : Map.of(NotificationParams.ANNOUNCEMENT_ID, id);
        NotificationType type = marketing
                ? NotificationType.MARKETING : NotificationType.ANNOUNCEMENT;

        return publisher.publishAll(recipients.stream()
                .map(userId -> {
                    NotificationEvent event = NotificationEvent.of(userId, type,
                            announcement.getTitle(), announcement.getBody(), params);
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
