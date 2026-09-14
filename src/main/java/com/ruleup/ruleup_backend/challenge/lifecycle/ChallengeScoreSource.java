package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Immutable scoring inputs remain readable under the same UUID after operational deletion. */
@Component
@RequiredArgsConstructor
public class ChallengeScoreSource {
    private final ChallengeRepository challenges;
    private final JdbcTemplate jdbc;

    public record Input(UUID getId, LocalDate getStartDate, Integer getWeeklyCount, Long getTemplateId, boolean automatic) {}

    public Optional<Input> findById(UUID id) {
        return challenges.findById(id).map(c -> new Input(c.getId(),c.getStartDate(),c.getWeeklyCount(),
                c.getTemplateId(),c.getPenalties().score())).or(() -> jdbc.query(
                "SELECT start_date,weekly_count,template_id,JSON_UNQUOTE(JSON_EXTRACT(verification_config,'$.selectedMethod')) AS method " +
                        "FROM challenge_history WHERE challenge_id=?",(rs,i)->new Input(id,rs.getDate("start_date").toLocalDate(),
                        (Integer)rs.getObject("weekly_count"),(Long)rs.getObject("template_id"),"AUTO".equals(rs.getString("method"))),
                ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()).stream().findFirst());
    }
}
