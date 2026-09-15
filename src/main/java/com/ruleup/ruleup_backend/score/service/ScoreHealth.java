package com.ruleup.ruleup_backend.score.service;

import com.ruleup.ruleup_backend.score.ScoreKeys;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** Reconstruct from immutable inputs; report corruption without silently replacing the evidence. */
@Component @Slf4j
public class ScoreHealth {
    private final JdbcTemplate jdbc;
    private final ScoreProcessor scores;
    private final AtomicLong mismatchedUsers=new AtomicLong();
    private final AtomicLong failedChecks=new AtomicLong();
    public ScoreHealth(JdbcTemplate jdbc,ScoreProcessor scores,MeterRegistry metrics) {
        this.jdbc=jdbc;this.scores=scores;
        metrics.gauge("score.integrity.mismatched.users",mismatchedUsers);
        metrics.gauge("score.integrity.failed.checks",failedChecks);
    }
    @Scheduled(cron="0 0 5 * * *",zone="Asia/Seoul")
    public void audit() {
        byte[] cursor=new byte[16];long mismatched=0,failed=0;
        while(true) {
            var page=jdbc.queryForList("SELECT user_id FROM user_score_summaries WHERE user_id>? ORDER BY user_id LIMIT 500",byte[].class,cursor);
            for(var id:page) {
                try {
                    var errors=scores.verifyUser(ScoreKeys.uuid(id));
                    if(!errors.isEmpty()){mismatched++;log.error("score integrity mismatch userId={} errors={}",ScoreKeys.uuid(id),errors);}
                } catch(RuntimeException e) {failed++;log.error("score integrity check failed userId={}",ScoreKeys.uuid(id),e);}
            }
            if(page.size()<500)break;
            cursor=page.getLast();
        }
        mismatchedUsers.set(mismatched);failedChecks.set(failed);
    }
}
