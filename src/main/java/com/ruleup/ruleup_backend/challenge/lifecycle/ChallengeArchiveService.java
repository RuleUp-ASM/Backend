package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationRequested;
import com.ruleup.ruleup_backend.challenge.service.ChallengeHardDeleter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/** A room lock protects the eligibility check, final snapshots and operational deletion together. */
@Service
@RequiredArgsConstructor
public class ChallengeArchiveService {
    private static final Logger log = LoggerFactory.getLogger(ChallengeArchiveService.class);
    private final JdbcTemplate jdbc;
    private final ChallengeHardDeleter hardDeleter;
    private final ApplicationEventPublisher events;

    @Transactional
    public boolean deleteIfEligible(UUID id) { return archive(id, false); }

    @Transactional
    public boolean closeByAdmin(UUID id) { return archive(id, true); }

    private boolean archive(UUID id, boolean admin) {
        long started = System.nanoTime();
        byte[] key = bytes(id);
        var rows = jdbc.queryForList("SELECT * FROM challenges WHERE id=? FOR UPDATE", (Object) key);
        if (rows.isEmpty()) return false;
        Map<String, Object> c = rows.getFirst();
        int active = count("SELECT COUNT(*) FROM challenge_members WHERE challenge_id=? AND status='ACTIVE'", key);
        boolean adminRequested = admin || count("SELECT COUNT(*) FROM challenge_history WHERE challenge_id=? AND close_reason='ADMIN'", key) > 0;
        LocalDate end = c.get("end_date") == null ? null : ((java.sql.Date)c.get("end_date")).toLocalDate();
        boolean expired = "COMPLETED".equals(c.get("status")) && end != null
                && end.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(2));
        if (!adminRequested && !expired && active != 0) return false;
        String reason = adminRequested ? "ADMIN" : expired ? "EXPIRED" : "EMPTY";

        if (adminRequested) {
            jdbc.update("UPDATE challenges SET status='COMPLETED', version=version+1 WHERE id=? AND status<>'COMPLETED'", key);
            // Persist closure intent in its owning history, including when pending work defers deletion.
            snapshot(key, "ADMIN", active);
        }
        boolean pending = "IN_REVIEW".equals(c.get("moderation_title"))
                || "IN_REVIEW".equals(c.get("moderation_description")) || "IN_REVIEW".equals(c.get("moderation_image"));
        if (pending) {
            if (c.get("moderation_enqueued_at") == null) events.publishEvent(new ChallengeModerationRequested(id));
            return false;
        }
        if (count("SELECT COUNT(*) FROM VerificationDaily WHERE challengeId=? AND status='PENDING'", key) > 0
                || count("SELECT COUNT(*) FROM cycle_score_states WHERE challenge_id=? AND closed_at IS NULL", key) > 0) {
            log.info("challenge_delete_deferred challengeId={} reason=UNSETTLED_WORK", id);
            return false;
        }
        snapshot(key, reason, active);
        jdbc.update("INSERT INTO challenge_member_history " +
                "(challenge_id,user_id,final_role,left_type,left_at,final_success_rate,joined_at,leave_reason,archived_at,member_id,verification_snapshot) " +
                "SELECT m.challenge_id,m.user_id,CASE WHEN c.owner_id=m.user_id THEN 'OWNER' ELSE 'MEMBER' END, " +
                "CASE m.status WHEN 'ACTIVE' THEN 'ACTIVE_AT_DELETE' ELSE m.status END,m.left_at, " +
                "CASE WHEN m.success_days+m.fail_days=0 THEN NULL ELSE ROUND(100*m.success_days/(m.success_days+m.fail_days),2) END, " +
                "m.joined_at,COALESCE(m.leave_reason, CASE m.left_type WHEN 'LEAVE' THEN 'VOLUNTARY' WHEN 'KICK' THEN 'KICKED' END), " +
                "UTC_TIMESTAMP(6),m.id,JSON_OBJECT('scheduleType',m.schedule_type,'targetDays',m.target_days, " +
                "'successDays',m.success_days,'failDays',m.fail_days,'anchors',m.anchors,'screenApps',m.screen_apps, " +
                "'pendingScreenApps',m.pending_screen_apps,'pendingScreenAppsEffectiveDate',m.pending_screen_apps_effective_date) " +
                "FROM challenge_members m JOIN challenges c ON c.id=m.challenge_id WHERE m.challenge_id=? " +
                "ON DUPLICATE KEY UPDATE left_at=VALUES(left_at), final_success_rate=VALUES(final_success_rate), " +
                "joined_at=VALUES(joined_at), leave_reason=VALUES(leave_reason), archived_at=VALUES(archived_at), " +
                "member_id=VALUES(member_id),verification_snapshot=VALUES(verification_snapshot)", key);
        jdbc.update("INSERT INTO challenge_final_ranking " +
                "(challenge_id,user_id,rank_no,score_snapshot,success_count,participations,archived_at) " +
                "SELECT challenge_id,user_id,CASE WHEN success_days+fail_days>=10 THEN " +
                "RANK() OVER (ORDER BY (success_days+fail_days>=10) DESC, " +
                "ROUND(success_days/NULLIF(success_days+fail_days,0),4) DESC,success_days DESC) ELSE NULL END, " +
                "CASE WHEN success_days+fail_days>=10 THEN ROUND(success_days/(success_days+fail_days),4) ELSE NULL END, " +
                "success_days,success_days+fail_days,UTC_TIMESTAMP(6) FROM challenge_members WHERE challenge_id=? AND status='ACTIVE' " +
                "ON DUPLICATE KEY UPDATE rank_no=VALUES(rank_no),score_snapshot=VALUES(score_snapshot)", key);
        hardDeleter.hardDelete(id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                log.info("challenge_deleted challengeId={} closeReason={} members={} latencyMs={}",
                        id, reason, active, (System.nanoTime()-started)/1_000_000);
            }
        });
        return true;
    }

    private void snapshot(byte[] key, String reason, int active) {
        jdbc.update("INSERT INTO challenge_history " +
                "(challenge_id,title_snapshot,ai_title_snapshot,description_snapshot,image_snapshot,category,start_date,end_date,deleted_at, " +
                "owner_id_snapshot,owner_type_snapshot,mode,visibility,capacity,min_tier,weekly_count,verification_config,params,penalties, " +
                "final_member_count,close_reason,closed_at,template_id,origin,source_challenge_id) " +
                "SELECT id,CASE WHEN moderation_title IN ('IN_REVIEW','REJECTED') THEN ai_title ELSE title END,ai_title, " +
                "CASE WHEN moderation_description IN ('IN_REVIEW','REJECTED') THEN NULL ELSE description END, " +
                "CASE WHEN moderation_image IN ('IN_REVIEW','REJECTED') THEN NULL ELSE image_url END,category,start_date,end_date,UTC_TIMESTAMP(6), " +
                "owner_id,owner_type,mode,visibility,capacity,min_tier,weekly_count,verification_config,params,penalties,?,?,UTC_TIMESTAMP(6),template_id,origin,source_challenge_id " +
                "FROM challenges WHERE id=? ON DUPLICATE KEY UPDATE title_snapshot=VALUES(title_snapshot), " +
                "description_snapshot=VALUES(description_snapshot),image_snapshot=VALUES(image_snapshot), " +
                "final_member_count=VALUES(final_member_count),deleted_at=VALUES(deleted_at)", active, reason, key);
    }
    private int count(String sql, byte[] id) { return jdbc.queryForObject(sql, Integer.class, (Object) id); }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
