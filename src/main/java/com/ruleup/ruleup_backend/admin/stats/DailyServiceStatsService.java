package com.ruleup.ruleup_backend.admin.stats;

import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 일별 서비스 지표 — 기존 DB 를 하루 한 번 모아 {@code daily_service_stats} 한 행으로 남긴다.
 *
 * <h4>날짜 경계</h4>
 * 한 행은 KST 달력 하루다. 이벤트 지표(가입·참여·이의)는 그 날 <b>[00:00, 24:00) KST</b> 에 일어난
 * 사건을 센다. DB 의 시각 칸은 전부 <b>UTC 벽시계</b>라({@link DbTime}) 경계도 UTC 로 옮겨 바인딩한다 —
 * KST 하루 D 는 UTC {@code [D-1 15:00, D 15:00)} 이다. {@code java.sql.Timestamp} 로 넘기면 운영 접속
 * (serverTimezone=Asia/Seoul)이 경계를 9시간 밀어 전날 저녁의 가입이 오늘로 들어온다.
 *
 * <p>인증 지표는 사건 시각이 아니라 <b>귀속일</b>({@code VerificationDaily.targetDate}, 이미 KST 날짜)로
 * 묶는다. 판정은 귀속일에 대한 것이고, 확정 시각(D+2 00:00 KST)으로 묶으면 같은 날의 성공과 실패가
 * 서로 다른 행에 흩어진다.
 *
 * <h4>지표 정의</h4>
 * 모든 유저 기준 지표는 {@code users.role = 'MEMBER'} 만 센다 — 운영자 콘솔 계정은 회원이 아니다
 * (대시보드와 같은 기준). 탈퇴 여부는 보지 않는다. 가입은 사건이라, 나중에 탈퇴했다고 그 날의
 * 가입 수가 줄면 같은 날짜를 다시 계산할 때마다 과거가 바뀐다.
 * <ul>
 *   <li><b>signups</b> — {@code users.created_at} 이 그 날인 MEMBER 계정. 탈퇴 후 재가입은 새 계정 행이라
 *       한 번 더 센다.</li>
 *   <li><b>challenges_created</b> — {@code challenges.created_at} 이 그 날인 챌린지. 개설 경로는 초안에서
 *       만드는 유저 개설 하나뿐이다. 개설자는 방장으로 곧바로 참여하고 가입 사건을 남기지 않으므로
 *       (솔로 방은 전부 이 경로다) 가입 수와 따로 센다.</li>
 *   <li><b>challenge_joins</b> — {@code challenge_join_events.joined_at} 이 그 날인 가입 사건. 재입장하면
 *       새 줄이 쌓이므로 재입장도 포함되고, 방장 개설은 포함되지 않는다.</li>
 *   <li><b>rejoins</b> — 그 가입 사건 중 <b>같은 방에 이전 참여 기록이 있던</b> 것(나갔거나 강퇴된 뒤
 *       재입장). 이전 참여는 같은 (방, 유저)의 더 이른 가입 사건이 있거나, 멤버 행의 {@code joined_at}
 *       (처음 들어온 시각 — 재입장해도 바뀌지 않는다)이 이 사건보다 1분 넘게 이르면 성립한다. 뒤 조건이
 *       필요한 이유는 방장이 가입 사건 없이 들어오기 때문이다 — 나갔던 방장의 재입장도 재입장이다.
 *       1분은 멤버 행(앱 시계)과 가입 사건(DB 시계)이 같은 트랜잭션에서 찍히는 오차를 흡수한다(V21 과 같은 폭).</li>
 *   <li><b>participants</b> — 그 날 참여를 시작한 유저 수(중복 제거). 가입 사건이 있거나, 멤버 행의
 *       {@code joined_at} 이 그 날인(= 처음 들어왔거나 방을 개설한) 유저다.</li>
 *   <li><b>returning_participants</b> — 그중 <b>그 날 00:00 KST 이전에</b> 어떤 챌린지에든 참여한 적이 있는
 *       유저(재참여). 이전 참여는 현재 멤버 행({@code challenge_members}, 나간 행 포함)과 삭제된 방의
 *       보관 이력({@code challenge_member_history})에서 찾는다. 그 날 처음 참여한 유저는
 *       {@code participants - returning_participants} 다.</li>
 *   <li><b>verification_targets</b> — 귀속일이 그 날이고 인증이 필요했던 판정({@code PENDING·SUCCESS·FAILED}).
 *       대상 아님·인증 불필요({@code NOT_TARGET·NOT_REQUIRED})는 분모가 아니다.</li>
 *   <li><b>verification_attempts</b> — 실제 인증 시도. <b>유효한 증거가 접수된 판정</b>만 센다:
 *       자동 판정·수동 체크로 성공한 판정({@code SUCCESS}, 이의 인용 제외)과, 측정은 됐지만 목표에
 *       못 미친 실패({@code FAILED} 이고 {@code gapReason IS NULL}). 권한이 없거나 쓸 신호가 없어 판정할
 *       수 없었던 실패({@code gapReason} = PERMISSION_MISSING / NO_SIGNAL — 수동 방의 미체크도 여기다)는
 *       증거가 없었으므로 시도가 아니다. 이의 인용은 증거 제출이 아니라 {@code appeals} 로 따로 센다.
 *       <br>세는 단위가 <b>판정(멤버 × 귀속일) 한 건</b>이라 같은 신호를 몇 번 재전송해도, 하루에 sync 를
 *       몇 번 쳐도 1 이다. 신호 원본 행을 세지 않는 이유가 이것이다 — 원본은 배경 수집이라 판정과 1:1 이
 *       아니고, 재전송 때 {@code receivedAt} 이 앞으로 밀려 날짜가 옮겨 다니며, 좌표는 보관 기간이
 *       지나면 파기돼 나중에 다시 셀 수 없다.</li>
 *   <li><b>judged_success / judged_fail</b> — 판정 결과 그대로의 성공·실패 건수. <b>인증 성공률은
 *       {@code judged_success / (judged_success + judged_fail)}</b> 로, 실제 판정 결과로만 계산한다
 *       (이의 인용 성공 포함 — 서비스가 최종적으로 인정한 결과다). 인용분은
 *       {@code judged_success_appeal}, 판정 불가 실패는 {@code judged_fail_no_evidence} 로 따로 두어
 *       다른 기준의 성공률도 다시 계산할 수 있게 한다.</li>
 *   <li><b>judgement_pending / judgement_final</b> — 판정은 귀속일 D+2 00:00 KST 에 확정된다. 그 전에
 *       계산한 행은 판정 칸이 잠정값이며({@code judgement_final = 0}) 성공률을 내지 않는다. 배치가 같은
 *       날짜를 이틀 더 다시 계산해 확정값으로 갈아 끼운다.</li>
 *   <li><b>appeals</b> — {@code verification_appeals.acceptedAt} 이 그 날인 이의(접수 = 인용).</li>
 * </ul>
 *
 * <h4>다시 계산하면 덮어쓴다</h4>
 * UPSERT 라 같은 날짜를 몇 번 돌려도 마지막 계산만 남는다(멱등). 다만 방이 삭제되면 그 방의 가입
 * 사건도 함께 지워지므로({@code ON DELETE CASCADE}), 오래된 날짜를 수동으로 다시 계산하면 가입 수가
 * 처음 계산했을 때보다 작게 나올 수 있다. 자동 배치는 최근 사흘만 다시 계산한다.
 */
@Service
@RequiredArgsConstructor
public class DailyServiceStatsService {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    /** 하루치 지표. 저장·조회·CSV 가 같은 모양을 쓴다. */
    public record DailyStat(
            LocalDate statDate,
            int signups,
            int challengesCreated,
            int challengeJoins,
            int rejoins,
            int participants,
            int returningParticipants,
            int verificationTargets,
            int verificationAttempts,
            int judgedSuccess,
            int judgedSuccessAppeal,
            int judgedFail,
            int judgedFailNoEvidence,
            int judgementPending,
            boolean judgementFinal,
            int appeals,
            Instant computedAt) {

        /**
         * 인증 성공률 — 확정 판정으로만 낸다. 아직 확정 전이거나 판정이 하나도 없으면 null.
         * 소수 넷째 자리(0.0001)까지.
         */
        public Double successRate() {
            int judged = judgedSuccess + judgedFail;
            if (!judgementFinal || judged == 0) return null;
            return Math.round(judgedSuccess * 10000.0 / judged) / 10000.0;
        }
    }

    /**
     * 하루를 계산해 저장한다. 같은 날짜가 있으면 덮어쓴다.
     *
     * @param day KST 달력 날짜
     * @param now 계산 시각 — 판정 확정 여부를 이 시각으로 가른다
     */
    @Transactional
    public DailyStat recompute(LocalDate day, Instant now) {
        DailyStat stat = compute(day, now);
        upsert(stat);
        return stat;
    }

    /** 계산만 한다(저장하지 않음). */
    @Transactional(readOnly = true)
    public DailyStat compute(LocalDate day, Instant now) {
        LocalDateTime from = DbTime.utc(day.atStartOfDay(KST).toInstant());
        LocalDateTime to = DbTime.utc(day.plusDays(1).atStartOfDay(KST).toInstant());

        int signups = count("""
                SELECT COUNT(*) FROM users
                 WHERE role = 'MEMBER' AND created_at >= ? AND created_at < ?
                """, from, to);

        int challengesCreated = count("""
                SELECT COUNT(*) FROM challenges
                 WHERE created_at >= ? AND created_at < ?
                """, from, to);

        int[] joins = jdbc.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(CASE WHEN EXISTS (SELECT 1 FROM challenge_join_events p
                                                       WHERE p.challenge_id = e.challenge_id
                                                         AND p.user_id = e.user_id
                                                         AND p.joined_at < e.joined_at)
                                           OR EXISTS (SELECT 1 FROM challenge_members m
                                                       WHERE m.challenge_id = e.challenge_id
                                                         AND m.user_id = e.user_id
                                                         AND m.joined_at < e.joined_at - INTERVAL 1 MINUTE)
                                         THEN 1 ELSE 0 END), 0)
                  FROM challenge_join_events e
                  JOIN users u ON u.id = e.user_id AND u.role = 'MEMBER'
                 WHERE e.joined_at >= ? AND e.joined_at < ?
                """, (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, from, to);

        int[] participants = jdbc.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(CASE WHEN EXISTS (SELECT 1 FROM challenge_members m
                                                       WHERE m.user_id = p.user_id AND m.joined_at < ?)
                                           OR EXISTS (SELECT 1 FROM challenge_member_history h
                                                       WHERE h.user_id = p.user_id AND h.joined_at < ?)
                                         THEN 1 ELSE 0 END), 0)
                  FROM (SELECT e.user_id FROM challenge_join_events e
                         WHERE e.joined_at >= ? AND e.joined_at < ?
                        UNION
                        SELECT m.user_id FROM challenge_members m
                         WHERE m.joined_at >= ? AND m.joined_at < ?) p
                  JOIN users u ON u.id = p.user_id AND u.role = 'MEMBER'
                """, (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)},
                from, from, from, to, from, to);

        int[] verification = jdbc.queryForObject("""
                SELECT COALESCE(SUM(v.status IN ('PENDING', 'SUCCESS', 'FAILED')), 0),
                       COALESCE(SUM((v.status = 'SUCCESS' AND (v.verifiedVia IS NULL OR v.verifiedVia <> 'APPEAL'))
                                    OR (v.status = 'FAILED' AND v.gapReason IS NULL)), 0),
                       COALESCE(SUM(v.status = 'SUCCESS'), 0),
                       COALESCE(SUM(v.status = 'SUCCESS' AND v.verifiedVia = 'APPEAL'), 0),
                       COALESCE(SUM(v.status = 'FAILED'), 0),
                       COALESCE(SUM(v.status = 'FAILED' AND v.gapReason IS NOT NULL), 0),
                       COALESCE(SUM(v.status = 'PENDING'), 0)
                  FROM VerificationDaily v
                  JOIN users u ON u.id = v.userId AND u.role = 'MEMBER'
                 WHERE v.targetDate = ?
                """, (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7)}, day);

        int appeals = count("""
                SELECT COUNT(*) FROM verification_appeals a
                  JOIN users u ON u.id = a.userId AND u.role = 'MEMBER'
                 WHERE a.acceptedAt >= ? AND a.acceptedAt < ?
                """, from, to);

        // 확정은 귀속일에서 파생한 시각으로 가른다 — 확정 배치가 밀려 PENDING 이 남아 있으면
        // judgement_pending 이 그대로 드러낸다.
        boolean judgementFinal = !now.isBefore(VerificationDeadlines.finalizeAfter(day));

        return new DailyStat(day, signups, challengesCreated, joins[0], joins[1],
                participants[0], participants[1],
                verification[0], verification[1], verification[2], verification[3],
                verification[4], verification[5], verification[6],
                judgementFinal, appeals, now);
    }

    /** 기간 조회 — 날짜 오름차순. 계산된 적 없는 날짜는 빠진다. */
    @Transactional(readOnly = true)
    public List<DailyStat> find(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT stat_date, signups, challenges_created, challenge_joins, rejoins, participants,
                       returning_participants, verification_targets, verification_attempts,
                       judged_success, judged_success_appeal, judged_fail, judged_fail_no_evidence,
                       judgement_pending, judgement_final, appeals, computed_at
                  FROM daily_service_stats
                 WHERE stat_date BETWEEN ? AND ?
                 ORDER BY stat_date
                """, (rs, i) -> new DailyStat(
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getInt("signups"),
                        rs.getInt("challenges_created"),
                        rs.getInt("challenge_joins"),
                        rs.getInt("rejoins"),
                        rs.getInt("participants"),
                        rs.getInt("returning_participants"),
                        rs.getInt("verification_targets"),
                        rs.getInt("verification_attempts"),
                        rs.getInt("judged_success"),
                        rs.getInt("judged_success_appeal"),
                        rs.getInt("judged_fail"),
                        rs.getInt("judged_fail_no_evidence"),
                        rs.getInt("judgement_pending"),
                        rs.getBoolean("judgement_final"),
                        rs.getInt("appeals"),
                        DbTime.read(rs, "computed_at")),
                from, to);
    }

    private void upsert(DailyStat s) {
        jdbc.update("""
                INSERT INTO daily_service_stats
                  (stat_date, signups, challenges_created, challenge_joins, rejoins, participants,
                   returning_participants, verification_targets, verification_attempts,
                   judged_success, judged_success_appeal, judged_fail, judged_fail_no_evidence,
                   judgement_pending, judgement_final, appeals, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  signups = VALUES(signups),
                  challenges_created = VALUES(challenges_created),
                  challenge_joins = VALUES(challenge_joins),
                  rejoins = VALUES(rejoins),
                  participants = VALUES(participants),
                  returning_participants = VALUES(returning_participants),
                  verification_targets = VALUES(verification_targets),
                  verification_attempts = VALUES(verification_attempts),
                  judged_success = VALUES(judged_success),
                  judged_success_appeal = VALUES(judged_success_appeal),
                  judged_fail = VALUES(judged_fail),
                  judged_fail_no_evidence = VALUES(judged_fail_no_evidence),
                  judgement_pending = VALUES(judgement_pending),
                  judgement_final = VALUES(judgement_final),
                  appeals = VALUES(appeals),
                  computed_at = VALUES(computed_at)
                """,
                s.statDate(), s.signups(), s.challengesCreated(), s.challengeJoins(), s.rejoins(),
                s.participants(), s.returningParticipants(), s.verificationTargets(),
                s.verificationAttempts(), s.judgedSuccess(), s.judgedSuccessAppeal(), s.judgedFail(),
                s.judgedFailNoEvidence(), s.judgementPending(), s.judgementFinal(), s.appeals(),
                DbTime.utc(s.computedAt()));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
