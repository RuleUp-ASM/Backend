package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.config.AppProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/** Shared one-use marker, including across processes and application restarts. */
@Component
public class SignupTokenStore {
    private final JdbcTemplate jdbc;
    private final long retentionSeconds;

    public SignupTokenStore(AppProperties props, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.retentionSeconds = props.jwt().signupTokenTtl() + 60;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String jti) {
        return jdbc.update("INSERT IGNORE INTO signup_token_consumptions(jti,expires_at) VALUES(?,?)",
                jti, Timestamp.from(Instant.now().plusSeconds(retentionSeconds))) == 1;
    }

    public boolean isUsed(String jti) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM signup_token_consumptions WHERE jti=?", Integer.class, jti) > 0;
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        jdbc.update("DELETE FROM signup_token_consumptions WHERE expires_at<?", Timestamp.from(Instant.now()));
    }
}
