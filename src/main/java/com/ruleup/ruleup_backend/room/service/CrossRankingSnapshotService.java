package com.ruleup.ruleup_backend.room.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 03시 이후 한 번 계산한 챌린지 간 랭킹을 다음 배치까지 고정한다. */
@Service
@RequiredArgsConstructor
public class CrossRankingSnapshotService {
    private final JdbcTemplate jdbc;

    private record Aggregate(String mode, UUID challengeId, String title, int members,
                             int success, int total, BigDecimal rate) {}

    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void refresh() {
        lockJob();
        List<Aggregate> aggregates = jdbc.query(
                "SELECT c.mode,c.id,c.title,COUNT(m.id),COALESCE(SUM(m.success_days),0)," +
                        "COALESCE(SUM(m.success_days+m.fail_days),0) FROM challenges c " +
                        "LEFT JOIN challenge_members m ON m.challenge_id=c.id AND m.status='ACTIVE' " +
                        "WHERE c.deleted_at IS NULL AND c.status='ACTIVE' " +
                        "AND (c.mode='GROUP' OR (c.mode='SOLO' AND c.ranking_visible=1)) " +
                        "GROUP BY c.mode,c.id,c.title",
                (rs, row) -> {
                    int success = rs.getInt(5);
                    int total = rs.getInt(6);
                    BigDecimal rate = total == 0 ? BigDecimal.ZERO.setScale(4)
                            : BigDecimal.valueOf(success).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
                    return new Aggregate(rs.getString(1), uuid(rs.getBytes(2)), rs.getString(3),
                            rs.getInt(4), success, total, rate);
                });
        Instant snapshotAt = Instant.now();
        List<Object[]> batch = new ArrayList<>();
        for (String mode : List.of("GROUP", "SOLO")) {
            int minimum = "GROUP".equals(mode) ? 50 : 10;
            List<Aggregate> rows = aggregates.stream().filter(a -> a.mode().equals(mode))
                    .sorted(Comparator.comparing(Aggregate::rate).reversed()
                            .thenComparing(Aggregate::success, Comparator.reverseOrder())
                            .thenComparing(Aggregate::challengeId)).toList();
            int rank = 0;
            for (Aggregate row : rows) {
                Integer assignedRank = row.total() >= minimum ? ++rank : null;
                batch.add(new Object[]{row.mode(), bytes(row.challengeId()), assignedRank, row.title(), row.members(),
                        row.success(), row.total(), row.rate(), Timestamp.from(snapshotAt)});
            }
        }
        jdbc.update("DELETE FROM challenge_cross_ranking_snapshot");
        jdbc.batchUpdate("INSERT INTO challenge_cross_ranking_snapshot " +
                        "(mode,challenge_id,rank_no,title,member_count,success_count,total_count,success_rate,snapshot_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)", batch);
    }

    /** 신규 배포 직후 첫 03시 배치 전에도 빈 스냅샷을 노출하지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeIfEmpty() {
        lockJob();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM challenge_cross_ranking_snapshot", Integer.class);
        if (count == null || count == 0) refresh();
    }

    private void lockJob() {
        jdbc.queryForObject("SELECT job_name FROM room_job_locks WHERE job_name='CROSS_RANKING' FOR UPDATE",
                String.class);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
