package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.counter.UserJoinCounterService;
import com.ruleup.ruleup_backend.challenge.explore.CategoryCountService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeHardDeleter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 챌린지 자동 삭제 배치 — 매일 04:10 KST (기능 스펙 6-1 #9 / 백엔드 4-5).
 *
 *  대상: ① 기간 만료(COMPLETED) 방 ② 유령방(ACTIVE 멤버 0명).
 *  방 단위 트랜잭션으로:
 *   1) 이력 스냅샷 적재 — challenge_history·challenge_member_history·challenge_final_ranking
 *      (하드 삭제 뒤에도 완료 목록·최종 랭킹 열람이 가능해야 하는 모순 해소 — P0)
 *   2) 통계 로그 적재([STATS]) — 어떤 루틴이 얼마나 생성·수행됐는지 보존
 *   3) 방 데이터(잔디·공지·감시자·멤버) 하드 삭제 — 인증 기록 원본·통계는 보존(소프트 참조, V6)
 *  랭킹 제거는 일 1회 랭킹 갱신 배치가 수행한다(정책 그대로 — 여기서 하지 않는다).
 *  실패한 방만 다음 실행에서 재처리된다(방 단위 격리).
 */
@Service
@RequiredArgsConstructor
public class ChallengeAutoDeleteService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeAutoDeleteService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ChallengeHardDeleter hardDeleter;
    private final CategoryCountService categoryCountService;
    private final UserJoinCounterService joinCounterService;

    /** 매일 04:10 KST — 만료·유령방 자동 삭제. */
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        runOnce();
    }

    /** 만료(COMPLETED)·유령방(ACTIVE 멤버 0명)을 이력 스냅샷 적재 후 하드 삭제한다. */
    public void runOnce() {
        List<byte[]> targets = jdbc.query(
                "SELECT c.id FROM challenges c " +
                        "WHERE c.status = 'COMPLETED' " +
                        "   OR NOT EXISTS (SELECT 1 FROM challenge_members m " +
                        "                  WHERE m.challenge_id = c.id AND m.status = 'ACTIVE')",
                (rs, i) -> rs.getBytes(1));
        int deleted = 0;
        Set<UUID> affectedUsers = new LinkedHashSet<>();
        for (byte[] id : targets) {
            try {
                Set<UUID> members = transactionTemplate.execute(tx -> deleteOne(id));
                if (members != null) affectedUsers.addAll(members);
                deleted++;
            } catch (Exception e) {
                // 방 단위 격리 — 실패한 방은 다음 실행에서 재처리, 나머지는 계속 진행
                log.error("챌린지 자동 삭제 실패 challengeId={}: {}", toUuid(id), e.getMessage(), e);
            }
        }
        // 슬롯 회수는 방 트랜잭션이 <b>끝난 뒤</b>에 한다. 트랜잭션 안에서 하면 challenges 행 락을 쥔 채
        // 사용자 행을 잠그게 되어 가입 경로(사용자 → 챌린지)와 락 순서가 역전된다.
        int released = joinCounterService.recompute(affectedUsers, "CHALLENGE_DELETED");
        if (released > 0) {
            log.info("자동 삭제 슬롯 회수: 대상 {}명 중 {}명 카운터 조정", affectedUsers.size(), released);
        }
        if (deleted > 0) {
            // 유령방(ACTIVE·멤버 0명)도 지우므로 그리드 집계가 줄어든다. 트랜잭션 밖이라 바로 버린다.
            categoryCountService.evict();
        }
        if (!targets.isEmpty()) {
            log.info("챌린지 자동 삭제 배치: 대상 {}건 중 {}건 삭제", targets.size(), deleted);
        }
    }

    /** 방 하나를 삭제하고, 슬롯을 돌려줘야 할 잔여 멤버의 userId 를 돌려준다(재계산은 커밋 뒤에). */
    private Set<UUID> deleteOne(byte[] id) {
        Map<String, Object> c;
        try {
            c = jdbc.queryForMap("SELECT title, image_url, category, start_date, end_date, template_id, " +
                    " participant_count, status FROM challenges WHERE id = ? FOR UPDATE", (Object) id);
        } catch (org.springframework.dao.EmptyResultDataAccessException gone) {
            return Set.of();   // 다른 인스턴스가 이미 지웠다 — 멱등
        }

        // 잔여 ACTIVE 멤버는 멤버 행이 지워지기 전에 여기서 확보해야 한다(지운 뒤엔 누구였는지 알 수 없다).
        Set<UUID> activeMembers = new LinkedHashSet<>(jdbc.query(
                "SELECT user_id FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE'",
                (rs, i) -> toUuid(rs.getBytes(1)), (Object) id));

        // 1) 이력 스냅샷 — 완료 기록·최종 랭킹 열람의 근거(삭제 "직전"에 적재)
        jdbc.update("INSERT INTO challenge_history " +
                        "(challenge_id, title_snapshot, image_snapshot, category, start_date, end_date, deleted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW(6)) " +
                        "ON DUPLICATE KEY UPDATE deleted_at = VALUES(deleted_at)",
                id, c.get("title"), c.get("image_url"), c.get("category"), c.get("start_date"), c.get("end_date"));

        jdbc.update("INSERT INTO challenge_member_history " +
                        "(challenge_id, user_id, final_role, left_type, left_at, final_success_rate) " +
                        "SELECT m.challenge_id, m.user_id, m.role, " +
                        "       CASE m.status WHEN 'ACTIVE' THEN 'ACTIVE_AT_DELETE' ELSE m.status END, " +
                        "       CASE WHEN m.status = 'ACTIVE' THEN NULL ELSE m.joined_at END, " +
                        "       m.progress_rate " +
                        "FROM challenge_members m WHERE m.challenge_id = ? " +
                        "ON DUPLICATE KEY UPDATE final_role = VALUES(final_role)", (Object) id);

        jdbc.update("INSERT INTO challenge_final_ranking (challenge_id, user_id, rank_no, score_snapshot) " +
                        "SELECT challenge_id, user_id, " +
                        "       RANK() OVER (ORDER BY progress_rate DESC, joined_at ASC), progress_rate " +
                        "FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE' " +
                        "ON DUPLICATE KEY UPDATE rank_no = VALUES(rank_no)", (Object) id);

        // 2) 통계 로그 — 루틴별 생성·수행 통계의 원천(로그 파이프라인 적재)
        log.info("[STATS] challenge_deleted challengeId={} templateId={} category={} status={} " +
                        "participants={} start={} end={} imageUrl={}",
                toUuid(id), c.get("template_id"), c.get("category"), c.get("status"),
                c.get("participant_count"), c.get("start_date"), c.get("end_date"), c.get("image_url"));

        // 3) 방 데이터 하드 삭제(인증 원본·이의·통계 보존은 HardDeleter 가 보장)
        hardDeleter.hardDelete(toUuid(id));

        // 슬롯 회수는 호출부가 커밋 뒤에 한다 — 여기서 하면 challenges 행 락을 쥔 채 사용자 행을 잠근다.
        return activeMembers;
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
