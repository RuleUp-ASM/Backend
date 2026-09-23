package com.ruleup.ruleup_backend.moderation;

import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
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

/** Synchronous review after submission commits. Only the daily sweep retries pending fields. */
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

    public UserModerationService(JdbcTemplate jdbc, UserRepository users, ContentModerationClient client,
                                 NotificationPublisher notifications, ImageStorageService images,
                                 EntityManager em, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.users = users; this.client = client;
        this.notifications = notifications; this.images = images; this.em = em;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public User moderate(UUID userId) {
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
                verdict = nickname ? client.moderateNickname(content) : client.moderateImage(content);
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

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
