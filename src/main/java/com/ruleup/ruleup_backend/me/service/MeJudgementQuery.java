package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.Polarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationPolarity;
import com.ruleup.ruleup_backend.verification.service.VerificationConfigFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Shared read source. Live judgements take precedence over legacy collected outcomes, including reversals. */
@Service
@RequiredArgsConstructor
public class MeJudgementQuery {
    private final JdbcTemplate jdbc;
    private final ChallengeRepository challenges;
    private final VerificationConfigFactory configs;

    public record Row(UUID id,UUID challengeId,LocalDate date,VerificationStatus status,String failureReason,
                      String verifiedVia,Instant verifiedAt,Instant appealClosesAt,boolean appealed,
                      String title,String category,Polarity polarity) {}

    public List<Row> between(UUID userId,LocalDate from,LocalDate to) {
        String range=from==null ? "" : " AND v.targetDate BETWEEN ? AND ?";
        String sql="SELECT v.id,v.challengeId,v.targetDate,v.status,v.failureReason,v.verifiedVia,v.verifiedAt,v.appealClosesAt," +
                "EXISTS(SELECT 1 FROM verification_appeals a WHERE a.verificationDailyId=v.id) AS appealed," +
                "COALESCE(CASE WHEN c.owner_id=v.userId OR c.moderation_title IN ('EXEMPT','APPROVED') THEN c.title ELSE c.ai_title END,h.title_snapshot) AS title," +
                "COALESCE(c.category,h.category) AS category,(SELECT r.polarity FROM VerificationMethodResult r WHERE r.verificationDailyId=v.id LIMIT 1) AS polarity " +
                "FROM VerificationDaily v LEFT JOIN challenges c ON c.id=v.challengeId LEFT JOIN challenge_history h ON h.challenge_id=v.challengeId " +
                "WHERE v.userId=?" + range + " UNION ALL " +
                "SELECT NULL,v.challengeId,v.targetDate,v.status,v.failureReason,v.verifiedVia,v.confirmedAt,NULL,0," +
                "COALESCE(CASE WHEN c.owner_id=v.userId OR c.moderation_title IN ('EXEMPT','APPROVED') THEN c.title ELSE c.ai_title END,h.title_snapshot)," +
                "COALESCE(c.category,h.category,v.category),NULL FROM RoutineOutcome v " +
                "LEFT JOIN challenges c ON c.id=v.challengeId LEFT JOIN challenge_history h ON h.challenge_id=v.challengeId " +
                "WHERE v.userId=?" + range + " AND NOT EXISTS(SELECT 1 FROM VerificationDaily d WHERE d.userId=v.userId AND d.challengeId=v.challengeId AND d.targetDate=v.targetDate) " +
                "ORDER BY targetDate,challengeId";
        Object[] args=from==null ? new Object[]{bytes(userId),bytes(userId)} : new Object[]{bytes(userId),from,to,bytes(userId),from,to};
        List<Row> rows=jdbc.query(sql,(rs,n)->new Row(uuid(rs.getBytes(1)),uuid(rs.getBytes(2)),rs.getDate(3).toLocalDate(),
                VerificationStatus.valueOf(rs.getString(4)),rs.getString(5),rs.getString(6),instant(rs,7),instant(rs,8),
                rs.getBoolean(9),rs.getString(10),rs.getString(11),rs.getString(12)==null ? null : Polarity.valueOf(rs.getString(12))),args);
        Map<UUID,Polarity> missing=new HashMap<>();
        challenges.findAllById(rows.stream().filter(r->r.status()==VerificationStatus.PENDING && r.polarity()==null).map(Row::challengeId).distinct().toList())
                .forEach(c->missing.put(c.getId(),VerificationPolarity.of(configs.build(c))));
        return rows.stream().map(r->r.polarity()!=null ? r : new Row(r.id(),r.challengeId(),r.date(),r.status(),r.failureReason(),r.verifiedVia(),r.verifiedAt(),
                r.appealClosesAt(),r.appealed(),r.title(),r.category(),missing.getOrDefault(r.challengeId(),Polarity.ACHIEVEMENT))).toList();
    }

    private static Instant instant(ResultSet rs,int index) throws SQLException { var at=rs.getTimestamp(index);return at==null ? null : at.toInstant(); }
    static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) { if(bytes==null)return null;var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong()); }
}
