package com.ruleup.ruleup_backend.score.service;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeScoreSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.ruleup.ruleup_backend.score.ScoreKeys.*;
@Service @RequiredArgsConstructor @Slf4j
public class ScoreSyncService {
    private final JdbcTemplate jdbc;
    private final ChallengeScoreSource sources;
    private final ScoreService scores;
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void syncConfirmedJudgements() {
        byte[] cursor=new byte[16];
        while(true) {
            var page=jdbc.queryForList("SELECT d.id,d.userId,d.challengeId,d.targetDate FROM VerificationDaily d WHERE d.status IN ('SUCCESS','FAILED') AND d.id>? AND NOT EXISTS (SELECT 1 FROM score_transactions t WHERE t.user_id=d.userId AND t.source_event_key=SHA2(CONCAT(UNHEX('00000024'),LOWER(BIN_TO_UUID(d.userId)),UNHEX('00000005'),'DAILY',UNHEX('00000024'),LOWER(BIN_TO_UUID(d.id))),256) AND t.source_version>=d.version AND t.entry_kind='RESULT' AND NOT EXISTS (SELECT 1 FROM score_transactions r WHERE r.reversal_of=t.id)) ORDER BY d.id LIMIT 500",cursor);
            for(var row:page) {
                UUID user=uuid((byte[])row.get("userId")),challenge=uuid((byte[])row.get("challengeId"));
                var source=sources.findById(challenge);
                if(source.isEmpty() || !source.get().automatic())continue;
                LocalDate target=((java.sql.Date)row.get("targetDate")).toLocalDate();
                int no=(int)(java.time.temporal.ChronoUnit.DAYS.between(source.get().getStartDate(),target)/7)+1;
                try { scores.reconcileCycle(user,challenge,no); }
                catch(RuntimeException e) { log.warn("score repair userId={} challengeId={} cycle={}",user,challenge,no,e); }
            }
            if(page.size()<500)return;cursor=(byte[])page.getLast().get("id");
        }
    }
}
