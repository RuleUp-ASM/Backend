package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.config.runtime.RuntimeRole;
import org.springframework.beans.factory.annotation.Value;

import com.ruleup.ruleup_backend.score.service.ScoreProcessor;import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
@Component @RequiredArgsConstructor
public class ScoreInitialization implements ApplicationRunner {

    /** 관리자 서비스·마이그레이션 태스크는 기동 작업을 돌지 않는다 — 공개 API 가 서비스당 한 벌만 맡는다. */
    @Value("${app.runtime.role:api}")
    private String runtimeRole = "api";
    private final JdbcTemplate jdbc;
    private final ScoreProcessor scores;
    @Override public void run(ApplicationArguments args) {
        if (!RuntimeRole.valueOf(runtimeRole.toUpperCase()).runsBackgroundWork()) return;
        var users=jdbc.queryForList("SELECT s.user_id FROM user_score_summaries s WHERE NOT EXISTS(SELECT 1 FROM score_transactions t WHERE t.user_id=s.user_id AND t.entry_kind='RESULT')",byte[].class);
        for(var user:users)scores.process(ScoreKeys.uuid(user),"signup",scores.inputHash(List.of()),List.of(),false);
    }
}
