package com.ruleup.ruleup_backend.challenge.guide;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 안내가 비어 있는 방을 채운다 — 커밋 뒤 비동기 이벤트는 재배포·종료 중에 사라질 수 있다.
 * 막 만들어진 방은 이벤트가 처리 중일 수 있어 2분 지난 것만 본다. 끝난 방은 안내를 볼 일이 없다.
 */
@Service
@RequiredArgsConstructor
public class VerificationGuideSweep {

    private final JdbcTemplate jdbc;
    private final VerificationGuideService service;

    @SchedulerLock(name = "VerificationGuideSweep.fillMissing", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void fillMissing() {
        jdbc.query("SELECT id FROM challenges WHERE verification_guide IS NULL AND deleted_at IS NULL "
                        + "AND status <> 'COMPLETED' "
                        + "AND updated_at <= NOW(6) - INTERVAL 2 MINUTE "
                        + "ORDER BY created_at LIMIT 30",
                (rs, i) -> uuid(rs.getBytes(1)))
                .forEach(service::generate);
    }

    private static UUID uuid(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
