package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.score.service.ScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.UUID;
import static com.ruleup.ruleup_backend.score.ScoreKeys.uuid;

/** Challenge-owned close confirmation. ScoreService also requires every eligible date to be final. */
@Component @RequiredArgsConstructor @Slf4j
public class ChallengeScoreClosure {
    private final JdbcTemplate jdbc;
    private final ChallengeScoreSource sources;
    private final ScoreService scores;

    @SchedulerLock(name = "ChallengeScoreClosure.closeFinishedCycles", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void closeFinishedCycles() {
        var rows=jdbc.queryForList("SELECT user_id,challenge_id,cycle_start_on FROM cycle_score_states WHERE closed_at IS NULL AND cycle_end_on<? ORDER BY cycle_start_on",LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
        for(var row:rows) {
            UUID user=uuid((byte[])row.get("user_id")),challenge=uuid((byte[])row.get("challenge_id"));
            var source=sources.findById(challenge);
            if(source.isEmpty())continue;
            int no=(int)(java.time.temporal.ChronoUnit.DAYS.between(source.get().getStartDate(),((java.sql.Date)row.get("cycle_start_on")).toLocalDate())/7)+1;
            try { scores.closeCycle(user,challenge,no); }
            catch(RuntimeException e) { log.warn("score close deferred userId={} challengeId={} cycle={}",user,challenge,no,e); }
        }
    }
}
