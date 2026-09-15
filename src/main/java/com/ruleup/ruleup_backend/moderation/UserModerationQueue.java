package com.ruleup.ruleup_backend.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Only the daily sweep publishes user moderation retries; request paths remain synchronous. */
@Component
public class UserModerationQueue {
    private static final Logger log = LoggerFactory.getLogger(UserModerationQueue.class);
    private final JdbcTemplate jdbc;
    private final UserModerationService moderation;
    private final ObjectProvider<SqsClient> clients;
    private final String url;
    private final boolean local;
    private final ObjectMapper json = new ObjectMapper();

    public UserModerationQueue(JdbcTemplate jdbc, UserModerationService moderation,
                              @Qualifier("challengeModerationSqsClient") ObjectProvider<SqsClient> clients,
                              @Value("${app.moderation.user-queue-url:}") String url,
                              @Value("${app.moderation.local:false}") boolean local) {
        this.jdbc = jdbc; this.moderation = moderation; this.clients = clients; this.url = url; this.local = local;
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void retryPending() {
        // Keyset pages prevent one permanently pending user from starving later users.
        byte[] after = new byte[16];
        while (true) {
            var rows = jdbc.queryForList("SELECT id,nickname_status,profile_image_status FROM users WHERE id>? " +
                    "AND status<>'WITHDRAWN' AND (nickname_status='PENDING' OR profile_image_status='PENDING') ORDER BY id LIMIT 500",
                    (Object) after);
            if (rows.isEmpty()) return;
            for (var row : rows) {
                var bytes = ByteBuffer.wrap((byte[]) row.get("id"));
                UUID id = new UUID(bytes.getLong(), bytes.getLong());
                List<String> targets = new ArrayList<>();
                if ("PENDING".equals(row.get("nickname_status"))) targets.add("NICKNAME");
                if ("PENDING".equals(row.get("profile_image_status"))) targets.add("PROFILE_IMAGE");
                try {
                    if (local) moderation.moderate(id);
                    else if (!url.isBlank()) {
                        String payload = json.writeValueAsString(new MessageBody(id, targets));
                        clients.getObject().sendMessage(b -> b.queueUrl(url).messageBody(payload));
                    }
                    else log.error("user_moderation_queue_missing userId={}", id);
                } catch (Exception failure) { log.warn("user_moderation_publish_failed userId={}", id); }
            }
            after = (byte[]) rows.getLast().get("id");
        }
    }

    @Scheduled(fixedDelayString = "${app.moderation.poll-ms:5000}")
    public void poll() {
        if (local || url.isBlank()) return;
        try {
            SqsClient sqs = clients.getObject();
            for (var message : sqs.receiveMessage(b -> b.queueUrl(url).maxNumberOfMessages(1)
                    .visibilityTimeout(120).waitTimeSeconds(1)).messages()) {
                MessageBody body = json.readValue(message.body(), MessageBody.class);
                moderation.moderate(body.userId());
                // Pending on provider failure remains eligible for tomorrow's sweep.
                sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(message.receiptHandle()));
            }
        } catch (Exception failure) { log.warn("user_moderation_consume_failed error={}", failure.getClass().getSimpleName()); }
    }

    public record MessageBody(UUID userId, List<String> targets) {}
}
