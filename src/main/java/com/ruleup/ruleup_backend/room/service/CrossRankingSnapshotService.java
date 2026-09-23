package com.ruleup.ruleup_backend.room.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Daily verified snapshot. Departing members are removed in their membership transaction. */
@Service
@RequiredArgsConstructor
public class CrossRankingSnapshotService {
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    private record Aggregate(String mode, UUID challengeId, String title, int members,
                             int success, int total, BigDecimal rate) {}

    @Transactional
    public void refresh() {
        lockJob();
        jdbc.update("DELETE FROM challenge_cross_ranking_members");
        jdbc.update("INSERT INTO challenge_cross_ranking_members SELECT m.challenge_id,m.user_id,m.success_days,m.success_days+m.fail_days " +
                "FROM challenge_members m JOIN challenges c ON c.id=m.challenge_id WHERE m.status='ACTIVE' AND m.left_at IS NULL " +
                "AND c.status='ACTIVE' AND c.deleted_at IS NULL AND (c.mode='GROUP' OR (c.mode='SOLO' AND c.ranking_visible=1))");
        rebuild(Instant.now());
    }

    private void rebuild(Instant snapshotAt) {
        List<Aggregate> aggregates = jdbc.query(
                "SELECT c.mode,c.id,CASE WHEN c.moderation_title IN ('APPROVED','EXEMPT') THEN c.title ELSE c.ai_title END," +
                        "COUNT(m.user_id),COALESCE(SUM(m.success_count),0),COALESCE(SUM(m.total_count),0) FROM challenges c " +
                        "LEFT JOIN challenge_cross_ranking_members m ON m.challenge_id=c.id " +
                        "WHERE c.deleted_at IS NULL AND c.status='ACTIVE' " +
                        "AND (c.mode='GROUP' OR (c.mode='SOLO' AND c.ranking_visible=1)) GROUP BY c.id",
                (rs, row) -> {
                    int success = rs.getInt(5), total = rs.getInt(6);
                    BigDecimal rate = total == 0 ? BigDecimal.ZERO.setScale(4)
                            : BigDecimal.valueOf(success).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
                    return new Aggregate(rs.getString(1), uuid(rs.getBytes(2)), rs.getString(3),rs.getInt(4),success,total,rate);
                });
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

    /** Multiple application instances observe the same completion marker and job lock. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void refreshAfterVerification() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        java.sql.Date last = jdbc.queryForObject("SELECT last_run_on FROM room_job_locks WHERE job_name='CROSS_RANKING' FOR UPDATE", java.sql.Date.class);
        if (last != null && !last.toLocalDate().isBefore(today)) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM verification_batch_completions WHERE batch_on=? AND finalized_at IS NOT NULL",
                Integer.class, today) == 0) return;
        refresh();
        jdbc.update("UPDATE room_job_locks SET last_run_on=? WHERE job_name='CROSS_RANKING'", today);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void removeDeparted(ChallengeStatsRefreshRequested event) {
        if (!java.util.Set.of("LEAVE", "WITHDRAW", "KICK", "SANCTION", "DORMANT", "TIER_GATE", "ADMIN").contains(event.reason())) return;
        em.flush();
        lockJob();
        int removed = jdbc.update("DELETE r FROM challenge_cross_ranking_members r LEFT JOIN challenge_members m " +
                "ON m.challenge_id=r.challenge_id AND m.user_id=r.user_id WHERE r.challenge_id=? " +
                "AND (m.id IS NULL OR m.status<>'ACTIVE' OR m.left_at IS NOT NULL)", bytes(event.challengeId()));
        if (removed > 0) {
            jdbc.update("UPDATE challenge_cross_ranking_snapshot s SET member_count=(SELECT COUNT(*) FROM challenge_cross_ranking_members r WHERE r.challenge_id=s.challenge_id)," +
                    "success_count=(SELECT COALESCE(SUM(success_count),0) FROM challenge_cross_ranking_members r WHERE r.challenge_id=s.challenge_id)," +
                    "total_count=(SELECT COALESCE(SUM(total_count),0) FROM challenge_cross_ranking_members r WHERE r.challenge_id=s.challenge_id) WHERE challenge_id=?", bytes(event.challengeId()));
            jdbc.update("UPDATE challenge_cross_ranking_snapshot SET success_rate=IF(total_count=0,0,ROUND(success_count/total_count,4)) WHERE challenge_id=?", bytes(event.challengeId()));
            rerank();
        }
    }

    public void removeChallenge(UUID challengeId) {
        lockJob();
        jdbc.update("DELETE FROM challenge_cross_ranking_members WHERE challenge_id=?", bytes(challengeId));
        jdbc.update("DELETE FROM challenge_cross_ranking_snapshot WHERE challenge_id=?", bytes(challengeId));
        rerank();
    }

    private void rerank() {
        for (String mode : List.of("GROUP", "SOLO")) {
            int minimum = "GROUP".equals(mode) ? 50 : 10;
            List<byte[]> eligible = jdbc.query("SELECT challenge_id FROM challenge_cross_ranking_snapshot WHERE mode=? AND total_count>=? " +
                    "ORDER BY success_rate DESC,success_count DESC,challenge_id FOR UPDATE", (rs,n) -> rs.getBytes(1), mode, minimum);
            jdbc.update("UPDATE challenge_cross_ranking_snapshot SET rank_no=NULL WHERE mode=?", mode);
            for (int n=0;n<eligible.size();n++) jdbc.update("UPDATE challenge_cross_ranking_snapshot SET rank_no=? WHERE challenge_id=?", n+1,eligible.get(n));
        }
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
