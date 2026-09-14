package com.ruleup.ruleup_backend.challenge.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

import static com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationSnapshot.Target;

@Repository
@RequiredArgsConstructor
public class ChallengeModerationStore {
    private final JdbcTemplate jdbc;

    public ChallengeModerationSnapshot read(UUID id) {
        return jdbc.query("SELECT owner_id, title, description, image_url, moderation_title, " +
                        "moderation_description, moderation_image FROM challenges WHERE id=?", (rs, i) -> {
            var targets = new java.util.ArrayList<Target>();
            for (Target target : Target.values()) {
                if ("IN_REVIEW".equals(rs.getString(target.statusColumn))) targets.add(target);
            }
            byte[] owner = rs.getBytes("owner_id");
            return new ChallengeModerationSnapshot(id, owner == null ? null : uuid(owner),
                    rs.getString("title"), rs.getString("description"), rs.getString("image_url"), targets);
        }, bytes(id)).stream().findFirst().orElse(null);
    }

    /** Field-local CAS: another field's edit cannot invalidate this decision. Binary is case-sensitive. */
    public boolean apply(ChallengeModerationSnapshot snapshot, Target target, String status) {
        return jdbc.update("UPDATE challenges SET " + target.statusColumn + "=? WHERE id=? AND BINARY " +
                        target.column + " <=> BINARY ? AND " + target.statusColumn + "='IN_REVIEW'",
                status, bytes(snapshot.id()), snapshot.content(target)) == 1;
    }

    public void finish(UUID id) {
        jdbc.update("UPDATE challenges SET moderation_pending_since=NULL, moderation_enqueued_at=NULL " +
                "WHERE id=? AND moderation_title <> 'IN_REVIEW' AND moderation_description <> 'IN_REVIEW' " +
                "AND moderation_image <> 'IN_REVIEW'", bytes(id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEnqueued(ChallengeModerationSnapshot sent) {
        // Do not mark a later edit as delivered. No revision/version dependency; the sent targets and
        // the complete content snapshot must still match. Already-decided targets need no delivery.
        String omitted = Arrays.stream(Target.values()).filter(t -> !sent.targets().contains(t))
                .map(t -> " AND " + t.statusColumn + " <> 'IN_REVIEW'").collect(java.util.stream.Collectors.joining());
        jdbc.update("UPDATE challenges SET moderation_enqueued_at=UTC_TIMESTAMP(6) WHERE id=? " +
                        "AND moderation_pending_since IS NOT NULL AND BINARY title <=> BINARY ? " +
                        "AND BINARY description <=> BINARY ? AND BINARY image_url <=> BINARY ?" + omitted,
                bytes(sent.id()), sent.title(), sent.description(), sent.image());
    }

    public void recordRejection(UUID id) {
        jdbc.update("UPDATE challenges SET moderation_reject_count=moderation_reject_count+1, " +
                "moderation_locked_until=NULL WHERE id=?", bytes(id));
    }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    static UUID uuid(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong());
    }
}
