package com.ruleup.ruleup_backend.challenge.stats;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.List;
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

        // 참여자 수는 <b>원천에서 다시 센다</b>. 가입·탈퇴 트랜잭션은 더 이상 표시값을 올리고
        // 내리지 않는다(탐색 백엔드 5-2·12) — 같은 방의 모든 가입이 그 한 행에서 직렬화되기
        // 때문이다. 세어 둔 값을 challenges 에 되써 주는 이유는 다른 모듈(자동 삭제·추천 집계·
        // 카드 응답)이 그 열을 계속 읽기 때문이고, 그러니 여기가 그 열의 유일한 기록 지점이다.
        Integer participantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE'",
                Integer.class, id);
        if (participantCount == null) participantCount = 0;

        // <b>challenges 를 먼저, 그리고 배타로 잡는다.</b> 순서를 뒤집으면 락을 승격하게 된다 —
        // challenge_stats 에 INSERT 하는 순간 외래키 검사가 부모 행에 공유 락을 걸고, 그 뒤의
        // 이 UPDATE 가 같은 행을 배타 락으로 올린다. 그사이 다른 트랜잭션(강퇴·설정 변경처럼
        // challenges 를 처음부터 배타로 잡는 경로)이 같은 행의 배타 락을 기다리고 있으면,
        // 공유 락을 쥔 채 올리려는 이쪽과 서로를 기다려 <b>데드락</b>이 난다.
        // 방 단위 작업은 전부 「challenges 먼저」로 줄을 세운다.
        //
        // updated_at 은 그대로 둔다. 이 열은 ON UPDATE CURRENT_TIMESTAMP 라 가만두면 파생값을
        // 채울 때마다 「방금 수정된 방」이 되어, 수정 시각으로 지체를 판별하는 배치들(심사 재시도 등)이
        // 영영 대상을 찾지 못한다. 참여자 수는 사용자가 방을 고친 것이 아니다.
        jdbc.update("UPDATE challenges SET participant_count = ?, updated_at = updated_at WHERE id = ?",
                participantCount, id);

        // 행이 없으면 만들고 잠근다 — 삭제된 방이면 FK 때문에 실패하므로 존재 확인이 겸해진다.
        jdbc.update("INSERT INTO challenge_stats (challenge_id) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) id);
        jdbc.queryForList("SELECT challenge_id FROM challenge_stats WHERE challenge_id = ? FOR UPDATE", (Object) id);

        String status = jdbc.queryForObject("SELECT status FROM challenges WHERE id = ?", String.class, id);

        Counts c = countFromMembers(id);
        // 시작 전 방은 진행 지표 자체가 없다 — 표본이 충분해 보여도 값을 내지 않는다.
        boolean upcoming = "UPCOMING".equals(status);

        Double completionRate = (upcoming || c.qualified < MIN_QUALIFIED_MEMBERS)
                ? null : ratio(c.qualifiedSuccess, c.qualified);
        Double retentionRate = (upcoming || c.totalProgress < MIN_TOTAL_PROGRESS || participantCount == 0)
                ? null : ratio(c.nonFailed, participantCount);

        // <b>값이 그대로면 쓰지 않는다.</b> challenge_stats.updated_at 은 「이 방의 통계가 언제
        // 달라졌는가」를 뜻해야 한다 — 탐색 투영이 그 시각을 원천 리비전에 넣어 순서를 정하고,
        // 5분 보정이 그 시각으로 「다시 볼 방」을 고르기 때문이다.
        //
        // 재계산할 때마다 무조건 NOW(6) 을 찍으면 값이 하나도 안 바뀐 전수 배치가 <b>모든 방의
        // 리비전을 한꺼번에 미래로 밀어버린다.</b> 그러면 멀쩡한 Redis 투영이 전부 「원천보다
        // 오래됐다」로 읽혀 준비 상태가 내려가고, 전수 재구성과 그동안의 503 이 매일 따라온다.
        // 위 participant_count 를 쓸 때 challenges.updated_at 을 그대로 두는 것과 같은 이유다.
        if (unchanged(id, c, completionRate, retentionRate)) return;

        jdbc.update("UPDATE challenge_stats SET " +
                        "qualified_member_count = ?, qualified_success_member_count = ?, completion_rate = ?, " +
                        "total_progress_count = ?, non_failed_member_count = ?, retention_rate = ?, " +
                        "updated_at = NOW(6) WHERE challenge_id = ?",
                c.qualified, c.qualifiedSuccess, completionRate,
                c.totalProgress, c.nonFailed, retentionRate, id);
    }

    /**
     * 지금 저장된 값이 이번에 센 값과 같은가.
     *
     * <p>비율은 {@code DECIMAL(5,4)} 이라 double 로 견주면 표현 차이로 「달라졌다」가 나올 수 있다.
     * 그래서 <b>DB 가 찍어 주는 문자열</b>끼리 비교한다 — 저장될 모양 그대로를 견주는 셈이라,
     * 소수점 자리수나 부동소수 반올림이 끼어들지 않는다.
     */
    private boolean unchanged(byte[] id, Counts c, Double completionRate, Double retentionRate) {
        List<String> current = jdbc.query(
                "SELECT qualified_member_count, qualified_success_member_count, completion_rate, " +
                        "       total_progress_count, non_failed_member_count, retention_rate " +
                        "FROM challenge_stats WHERE challenge_id = ?",
                (rs, i) -> rs.getInt(1) + "|" + rs.getInt(2) + "|" + rs.getString(3) + "|"
                        + rs.getInt(4) + "|" + rs.getInt(5) + "|" + rs.getString(6),
                id);
        if (current.isEmpty()) return false;

        // 같은 자리수로 찍어야 문자열이 맞아떨어진다 — DECIMAL(5,4) 는 언제나 소수 넷째 자리까지다.
        String next = c.qualified + "|" + c.qualifiedSuccess + "|" + decimal4(completionRate) + "|"
                + c.totalProgress + "|" + c.nonFailed + "|" + decimal4(retentionRate);
        return current.getFirst().equals(next);
    }

    private static String decimal4(Double value) {
        return (value == null) ? null : new java.math.BigDecimal(value.toString())
                .setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
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
