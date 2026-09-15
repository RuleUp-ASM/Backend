package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.ruleup.ruleup_backend.me.service.MeJudgementQuery.bytes;

/** Live and archived membership use the same categories without double-counting deferred archive rows. */
@Service
@RequiredArgsConstructor
public class MeChallengeCounts {
    private final JdbcTemplate jdbc;
    private static final String MEMBERS="SELECT m.status AS member_status,c.status AS challenge_status,m.progress_rate AS rate " +
            "FROM challenge_members m JOIN challenges c ON c.id=m.challenge_id WHERE m.user_id=? UNION ALL " +
            "SELECT IF(h.left_type='ACTIVE_AT_DELETE','ACTIVE',h.left_type),'COMPLETED',COALESCE(100*JSON_EXTRACT(h.verification_snapshot,'$.successDays')/NULLIF(JSON_EXTRACT(h.verification_snapshot,'$.targetDays'),0),h.final_success_rate) " +
            "FROM challenge_member_history h WHERE h.user_id=? AND NOT EXISTS(SELECT 1 FROM challenges c WHERE c.id=h.challenge_id)";

    public MeHomeResponse.Counts counts(UUID userId) {
        return jdbc.query("SELECT COALESCE(SUM(member_status='ACTIVE' AND challenge_status<>'COMPLETED'),0)," +
                "COALESCE(SUM(member_status='ACTIVE' AND challenge_status='COMPLETED'),0)," +
                "COALESCE(SUM(member_status<>'ACTIVE'),0) FROM ("+MEMBERS+") m",
                rs->{rs.next();return new MeHomeResponse.Counts(rs.getInt(1),rs.getInt(2),rs.getInt(3));},bytes(userId),bytes(userId));
    }

    public long completedSuccessfully(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ("+MEMBERS+") m WHERE challenge_status='COMPLETED' AND rate>=80",
                Long.class,bytes(userId),bytes(userId));
    }
}
