package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.common.ClockSkew;
import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import static com.ruleup.ruleup_backend.me.service.MeJudgementQuery.bytes;

/** Only observed, unresolved measurement gaps are warnings; absence does not assert an OS permission grant. */
@Service
@RequiredArgsConstructor
public class MePermissionWarnings {
    private final JdbcTemplate jdbc;
    public List<MeHomeResponse.PermissionWarning> of(UUID userId) {
        ZoneId kst=ZoneId.of("Asia/Seoul");
        LocalDate today=LocalDate.now(kst);
        return jdbc.query("SELECT w.challenge_id,w.signal_type,w.waiting_from_on FROM verification_permission_waits w " +
                "JOIN challenge_members m ON m.challenge_id=w.challenge_id AND m.user_id=w.user_id " +
                "WHERE w.user_id=? AND w.resolved_at IS NULL AND m.status='ACTIVE' AND m.left_at IS NULL " +
                // 방장은 가입 사건 행이 없어 서브쿼리가 NULL → 비교가 NULL → 경고가 안 보였다(QA SAN-09).
                "AND w.first_observed_at>=DATE_SUB(COALESCE("
                + "(SELECT MAX(joined_at) FROM challenge_join_events e WHERE e.challenge_id=w.challenge_id AND e.user_id=w.user_id),"
                + "(SELECT m2.joined_at FROM challenge_members m2 WHERE m2.challenge_id=w.challenge_id AND m2.user_id=w.user_id)"
                + "), INTERVAL " + ClockSkew.TOLERANCE_SECONDS + " SECOND)",
                (rs,n)->{
                    var b=ByteBuffer.wrap(rs.getBytes(1));
                    LocalDate until=rs.getDate(3).toLocalDate().plusDays(14);
                    int cycles=(int)Math.max(0,Math.min(2,(ChronoUnit.DAYS.between(today,until)+6)/7));
                    return new MeHomeResponse.PermissionWarning(new UUID(b.getLong(),b.getLong()).toString(),rs.getString(2),cycles,until.atStartOfDay(kst).toInstant().toString());
                },bytes(userId));
    }
}
