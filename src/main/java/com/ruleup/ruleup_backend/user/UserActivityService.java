package com.ruleup.ruleup_backend.user;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserActivityService {
    private final JdbcTemplate jdbc;

    @Transactional
    public void touch(UUID userId) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        byte[] id = ByteBuffer.allocate(16).putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits()).array();
        jdbc.update("INSERT INTO user_activity(user_id,last_active_on) SELECT id,? FROM users " +
                "WHERE id=? AND status<>'WITHDRAWN' ON DUPLICATE KEY UPDATE " +
                "notified_stage=IF(last_active_on<VALUES(last_active_on),'NONE',notified_stage), " +
                "last_active_on=GREATEST(last_active_on,VALUES(last_active_on))", today, id);
    }
}
