package com.ruleup.ruleup_backend.report;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

/** 일시적인 LLM 장애로 PENDING 에 남은 신고를 다시 검토한다. review 자체가 멱등 가드한다. */
@Service
@RequiredArgsConstructor
public class ReportReviewRetryService {
    private final JdbcTemplate jdbc;
    private final ReportReviewService reviewService;

    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    public void retryPending() {
        pendingIds(200).forEach(reviewService::review);
    }

    List<UUID> pendingIds(int limit) {
        return jdbc.query("SELECT id FROM reports WHERE review_status='PENDING' " +
                        "AND created_at<=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 15 MINUTE) " +
                        "ORDER BY created_at LIMIT ?",
                (rs, row) -> uuid(rs.getBytes(1)), limit);
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
