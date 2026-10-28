package com.ruleup.ruleup_backend.moderation;

import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Review after submission commits. Profile edits wait for it; signup hands it to a small background pool
 * so the response does not hold a ~0.9s LLM round-trip. Only the daily sweep retries pending fields.
 */
@Service
public class UserModerationService {
    private static final Logger log = LoggerFactory.getLogger(UserModerationService.class);
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ContentModerationClient client;
    private final NotificationPublisher notifications;
    private final ImageStorageService images;
    private final EntityManager em;
    private final TransactionTemplate tx;
    /** 외부 호출 ~1초짜리라 스레드는 적게, 가입이 몰리면 줄을 세운다. */
    private final ThreadPoolExecutor background = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(500), Thread.ofPlatform().name("user-moderation-", 0).daemon().factory());

    public UserModerationService(JdbcTemplate jdbc, UserRepository users, ContentModerationClient client,
                                 NotificationPublisher notifications, ImageStorageService images,
                                 EntityManager em, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.users = users; this.client = client;
        this.notifications = notifications; this.images = images; this.em = em;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public User moderate(UUID userId) {
        return moderate(userId, null);
    }

    /**
     * 가입 응답을 붙잡지 않고 뒤에서 심사한다. 심사 중에는 본인에게 입력 닉네임·타인에게 임시 닉네임이 보이고,
     * 거절되면 임시 닉네임과 거절 알림이 남는다. 대기열이 가득 차거나 태스크가 내려가 놓친 건 05:00 스캔이 다시 집는다.
     */
    public void moderateLater(UUID userId) {
        try {
            background.execute(() -> {
                try { moderate(userId); }
                catch (RuntimeException failure) {
                    log.warn("user_moderation_background_failed userId={} error={}", userId, failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException full) {
            log.warn("user_moderation_deferred userId={} reason=queue_full", userId);
        }
    }

    /** 종료 때 진행 중인 심사는 마저 끝낸다 — 못 끝낸 건 PENDING 으로 남아 05:00 스캔이 집는다. */
    @PreDestroy
    void stopBackground() throws InterruptedException {
        background.shutdown();
        if (!background.awaitTermination(15, TimeUnit.SECONDS)) background.shutdownNow();
    }

    /** 방금 올린 사진은 손에 든 바이트로 심사한다 — 공개 주소로 되받는 왕복(~0.6초)을 건너뛴다. */
    public User moderate(UUID userId, UploadedImage uploaded) {
        byte[] id = bytes(userId);
        var rows = jdbc.queryForList("SELECT nickname,nickname_status,profile_image_key,profile_image_status " +
                "FROM users WHERE id=? AND status<>'WITHDRAWN'", (Object) id);
        if (rows.isEmpty()) return null;
        var submitted = rows.getFirst();
        for (boolean nickname : new boolean[]{true, false}) {
            String column = nickname ? "nickname" : "profile_image_key";
            String statusColumn = nickname ? "nickname_status" : "profile_image_status";
            if (!"PENDING".equals(submitted.get(statusColumn))) continue;
            String content = (String) submitted.get(column);
            ModerationResult verdict;
            try {
                verdict = nickname ? client.moderateNickname(content)
                        : uploaded != null && uploaded.url().equals(content)
                        ? client.moderateImageBytes(uploaded.bytes(), uploaded.mimeType())
                        : client.moderateImage(content);
            } catch (RuntimeException unavailable) {
                log.warn("user_moderation_pending userId={} target={} error={}", userId, column,
                        unavailable.getClass().getSimpleName());
                continue;
            }
            if (verdict == null || verdict == ModerationResult.UNAVAILABLE) continue;
            String result = verdict.name();
            Boolean applied = tx.execute(ignored -> {
                // Claim the user row only after the external request completes.
                User user = users.findByIdForUpdate(userId).orElse(null);
                if (user == null || user.isWithdrawn()) return false;
                String decision = nickname && "APPROVED".equals(result) && users.isNicknameTaken(content, userId)
                        ? "CONFLICT" : result;
                String assignment = nickname && "APPROVED".equals(decision) ? ",approved_nickname=nickname" : "";
                int changed = jdbc.update("UPDATE users SET " + statusColumn + "=?" + assignment +
                        " WHERE id=? AND " + statusColumn + "='PENDING' AND BINARY " + column + " <=> BINARY ?",
                        decision, id, content);
                if (changed == 0) return false;
                // Keep the request's persistence context consistent with the guarded SQL update.
                em.refresh(user);
                if ("REJECTED".equals(decision)) {
                    notifications.publish(NotificationEvent.of(userId, NotificationType.MODERATION_REJECTED,
                            Map.of(NotificationParams.VARIANT, nickname ? "NICKNAME" : "PROFILE_IMAGE",
                                    NotificationParams.TARGET_KEY, nickname ? "nickname" : "profile_image",
                                    NotificationParams.EVENT_KEY, UUID.randomUUID().toString())));
                }
                log.info("user_moderation_decided userId={} target={} result={}", userId, column, decision);
                return true;
            });
            if (Boolean.TRUE.equals(applied) && !nickname && verdict == ModerationResult.REJECTED) {
                try { images.deleteByUrl(content); }
                catch (RuntimeException failure) { log.warn("rejected_image_cleanup_failed userId={}", userId); }
            }
        }
        return tx.execute(ignored -> {
            User user = users.findById(userId).orElse(null);
            if (user != null) { em.refresh(user); user.getInterestCategories().size(); }
            return user;
        });
    }

    /** 저장한 사진의 서빙 URL 과 원본 바이트. URL 이 지금 제출값과 같을 때만 바이트를 쓴다. */
    public record UploadedImage(String url, byte[] bytes, String mimeType) {}

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
