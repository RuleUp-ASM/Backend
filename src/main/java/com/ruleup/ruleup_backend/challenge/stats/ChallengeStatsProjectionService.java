package com.ruleup.ruleup_backend.challenge.stats;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 한 챌린지의 완주율·유지율 Projection 재계산 (탐색 백엔드 테크스펙 §6-4·§7-1).
 *
 * <p><b>왜 증분(+1/-1)이 아니라 전체 재계산인가</b>: 이의 승인으로 과거 판정이 뒤집히고, 탈퇴·강퇴 시
 * 그 멤버의 과거 기여를 통계에서 빼야 하며, 10회 표본·성공률 80%·확정 실패처럼 경계 조건이 많다.
 * 증분 카운터만 쓰면 누락 버그가 조용히 누적된다. Phase 1 에서는 영향받은 방 하나를 원천에서 다시 센다.
 *
 * <p><b>왜 별도 트랜잭션인가</b>: 통계 저장이 실패했다고 이미 성공한 가입을 되돌릴 이유가 없다.
 * 원본 COMMIT 이후 {@code REQUIRES_NEW} 로 실행하고, 잠그는 것은 해당 방의 {@code challenge_stats}
 * 한 행뿐이다 — 통계 계산 때문에 멤버·인증 이력에 락을 걸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeStatsProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeStatsProjectionService.class);

    /** 완주 기준 성공률 — 이 값 이상이면 완주로 센다(정책 §1). */
    private static final double COMPLETION_THRESHOLD = 0.8d;
    /** 완주율을 내려면 필요한 자격 멤버 수(정책 §4.4). */
    private static final int MIN_QUALIFIED_MEMBERS = 5;
    /** 자격 멤버가 되기 위한 확정 판정 횟수. */
    private static final int MIN_MEMBER_PROGRESS = 10;
    /** 유지율을 내려면 필요한 방 누적 확정 판정 수(정책 §4.4). */
    private static final int MIN_TOTAL_PROGRESS = 30;

    private final JdbcTemplate jdbc;

    /**
     * 챌린지 생성 시 기본 행을 함께 만든다(지표는 전부 NULL).
     *
     * <p>탐색 목록이 {@code challenges JOIN challenge_stats} 로 읽으므로, 행이 없으면 그 방은
     * 목록에서 통째로 사라진다. 그래서 생성 트랜잭션 안에서 같이 만들고 함께 롤백되게 둔다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createRow(UUID challengeId) {
        jdbc.update("INSERT INTO challenge_stats (challenge_id) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) toBytes(challengeId));
    }

    /**
     * 원천(현재 ACTIVE 멤버들의 확정 판정 누적)에서 통계를 다시 계산해 저장한다.
     * 호출부의 트랜잭션과 분리되며, 실패해도 원본 요청에 전파하지 않는 것은 호출부 책임이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh(UUID challengeId) {
        byte[] id = toBytes(challengeId);

        // 행이 없으면 만들고 잠근다 — 삭제된 방이면 FK 때문에 실패하므로 존재 확인이 겸해진다.
        jdbc.update("INSERT INTO challenge_stats (challenge_id) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) id);
        jdbc.queryForList("SELECT challenge_id FROM challenge_stats WHERE challenge_id = ? FOR UPDATE", (Object) id);

        String status = jdbc.queryForObject("SELECT status FROM challenges WHERE id = ?", String.class, id);
        int participantCount = jdbc.queryForObject(
                "SELECT participant_count FROM challenges WHERE id = ?", Integer.class, id);

        Counts c = countFromMembers(id);
        // 시작 전 방은 진행 지표 자체가 없다 — 표본이 충분해 보여도 값을 내지 않는다.
        boolean upcoming = "UPCOMING".equals(status);

        Double completionRate = (upcoming || c.qualified < MIN_QUALIFIED_MEMBERS)
                ? null : ratio(c.qualifiedSuccess, c.qualified);
        Double retentionRate = (upcoming || c.totalProgress < MIN_TOTAL_PROGRESS || participantCount == 0)
                ? null : ratio(c.nonFailed, participantCount);

        jdbc.update("UPDATE challenge_stats SET " +
                        "qualified_member_count = ?, qualified_success_member_count = ?, completion_rate = ?, " +
                        "total_progress_count = ?, non_failed_member_count = ?, retention_rate = ?, " +
                        "updated_at = NOW(6) WHERE challenge_id = ?",
                c.qualified, c.qualifiedSuccess, completionRate,
                c.totalProgress, c.nonFailed, retentionRate, id);
    }

    /**
     * 현재 ACTIVE 멤버들의 비정규화 카운터로 통계를 센다.
     *
     * <p>확정 실패는 "남은 날을 전부 성공해도 80%에 못 미치는 상태"다(정책 §1). 대상일이 아직 잡히지
     * 않은 멤버(target_days = 0)는 실패로 확정할 근거가 없으므로 실패로 세지 않는다.
     */
    private Counts countFromMembers(byte[] challengeId) {
        return jdbc.queryForObject(
                "SELECT " +
                        "  COALESCE(SUM(CASE WHEN done >= ? THEN 1 ELSE 0 END), 0) AS qualified, " +
                        "  COALESCE(SUM(CASE WHEN done >= ? AND success_days >= ? * done THEN 1 ELSE 0 END), 0) " +
                        "    AS qualified_success, " +
                        "  COALESCE(SUM(done), 0) AS total_progress, " +
                        "  COALESCE(SUM(CASE WHEN target_days > 0 " +
                        "                    AND (target_days - fail_days) < ? * target_days THEN 0 ELSE 1 END), 0) " +
                        "    AS non_failed " +
                        "FROM (SELECT success_days, fail_days, target_days, (success_days + fail_days) AS done " +
                        "      FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE') m",
                (rs, i) -> new Counts(rs.getInt("qualified"), rs.getInt("qualified_success"),
                        rs.getInt("total_progress"), rs.getInt("non_failed")),
                MIN_MEMBER_PROGRESS, MIN_MEMBER_PROGRESS, COMPLETION_THRESHOLD, COMPLETION_THRESHOLD, challengeId);
    }

    private record Counts(int qualified, int qualifiedSuccess, int totalProgress, int nonFailed) {}

    private static Double ratio(int numerator, int denominator) {
        if (denominator <= 0) return null;
        return Math.round((double) numerator / denominator * 10_000d) / 10_000d;
    }

    static byte[] toBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
