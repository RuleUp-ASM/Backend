package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.common.DbTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChallengeRejoinPolicy {
    private final JdbcTemplate jdbc;

    public boolean permanentlyBanned(UUID challenge, UUID user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=? AND user_id=? AND is_permanent=TRUE",
                Integer.class, bytes(challenge), bytes(user)) > 0;
    }

    public Instant availableAt(UUID challenge, UUID user) {
        return jdbc.query("SELECT available_at FROM challenge_rejoin_backoffs WHERE challenge_id=? AND user_id=?",
                rs -> rs.next() ? DbTime.read(rs, 1) : null, bytes(challenge), bytes(user));
    }

    public Instant availableAt(UUID challenge, UUID user, com.ruleup.ruleup_backend.challenge.domain.ChallengeMember member) {
        Instant recorded = availableAt(challenge, user);
        Instant legacy = member == null ? null : member.getRejoinAvailableAt();
        return recorded == null ? legacy : legacy == null || recorded.isAfter(legacy) ? recorded : legacy;
    }

    public void voluntaryLeave(UUID challenge, UUID user, Instant availableAt) {
        jdbc.update("INSERT INTO challenge_rejoin_backoffs VALUES(?,?,0,?) ON DUPLICATE KEY UPDATE available_at=VALUES(available_at)",
                bytes(challenge), bytes(user), DbTime.utc(availableAt));
    }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
