package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.score.*;
import com.ruleup.ruleup_backend.score.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static com.ruleup.ruleup_backend.score.ScoreKeys.*;

/** Challenge-owned calendar, stable cycle identity and participation snapshot. */
@Component @RequiredArgsConstructor
public class ChallengeScoreInputs {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    public static UUID cycleId(UUID challenge,LocalDate start) {
        return UUID.nameUUIDFromBytes(("ruleup:cycle:"+challenge+":"+start).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public Optional<ScoreInput.CycleSpec> cycle(UUID user,UUID challenge,int no) {
        if (no < 1) throw new IllegalArgumentException("SCORE_CYCLE_NUMBER_INVALID");
        var rows=jdbc.queryForList("SELECT start_date,end_date,weekly_count,repeat_days,verification_config FROM challenges WHERE id=? UNION ALL SELECT start_date,end_date,weekly_count,repeat_days,verification_config FROM challenge_history WHERE challenge_id=? AND NOT EXISTS(SELECT 1 FROM challenges WHERE id=?)",bytes(challenge),bytes(challenge),bytes(challenge));
        if(rows.isEmpty())throw new IllegalStateException("SCORE_CHALLENGE_INPUT_MISSING");
        var c=rows.getFirst();
        var config=json.readTree(c.get("verification_config").toString());
        if(!"AUTO".equals(config.path("selectedMethod").asText()))return Optional.empty();
        LocalDate start=((java.sql.Date)c.get("start_date")).toLocalDate().plusDays(7L*(no-1));
        LocalDate end=start.plusDays(6);
        var joins=jdbc.query("SELECT joined_at FROM challenge_join_events WHERE user_id=? AND challenge_id=? AND joined_at < ? ORDER BY joined_at DESC LIMIT 1",
                (rs,n)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC),bytes(user),bytes(challenge),LocalDateTime.ofInstant(end.plusDays(1).atStartOfDay(KST).toInstant(),ZoneOffset.UTC));
        if(joins.isEmpty()) joins=jdbc.query("SELECT joined_at FROM challenge_members WHERE user_id=? AND challenge_id=? UNION ALL SELECT joined_at FROM challenge_member_history WHERE user_id=? AND challenge_id=?",(rs,n)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC),bytes(user),bytes(challenge),bytes(user),bytes(challenge));
        if(joins.isEmpty()) {
            // A known later join proves this historical cycle was not scoreable for this member.
            Integer later=jdbc.queryForObject("SELECT COUNT(*) FROM challenge_join_events WHERE user_id=? AND challenge_id=?",Integer.class,bytes(user),bytes(challenge));
            if(later!=null && later>0)return Optional.empty();
            throw new IllegalStateException("SCORE_MEMBERSHIP_INPUT_MISSING");
        }
        Instant joined=joins.getFirst();LocalDate first=ChallengeCycle.countFrom(((java.sql.Date)c.get("start_date")).toLocalDate(),joined.atZone(KST).toLocalDate());
        if(start.isBefore(first))return Optional.empty();
        if(c.get("repeat_days")==null)throw new IllegalStateException("SCORE_ELIGIBLE_DATES_MISSING");
        Set<String> repeat=new HashSet<>();json.readTree(c.get("repeat_days").toString()).forEach(v->repeat.add(v.asText()));
        // The last cycle is cut at the challenge end. Counting days past it as "remaining" kept misses from ever
        // confirming, and closing waited for verification rows that are never opened (QA TIER-15 B2).
        LocalDate lastDay=c.get("end_date")==null?end:((java.sql.Date)c.get("end_date")).toLocalDate();
        List<LocalDate> dates=new ArrayList<>();
        for(LocalDate d=start;!d.isAfter(end)&&!d.isAfter(lastDay);d=d.plusDays(1))if(repeat.contains(d.getDayOfWeek().name().substring(0,3)))dates.add(d);
        if(dates.isEmpty())return Optional.empty();
        // A weekly goal larger than the days left in a cut-short week is unreachable; cap it at those days.
        int target=c.get("weekly_count")==null?dates.size():Math.min(dates.size(),((Number)c.get("weekly_count")).intValue());
        return Optional.of(new ScoreInput.CycleSpec(challenge,cycleId(challenge,start),no,start,end,joined,first,target,List.copyOf(dates),ScoreInput.POLICY));
    }
}
