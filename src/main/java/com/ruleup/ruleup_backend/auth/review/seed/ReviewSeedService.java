package com.ruleup.ruleup_backend.auth.review.seed;

import com.ruleup.ruleup_backend.auth.review.ReviewAccountPolicy;
import com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedDemo.Account;
import com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedDemo.Rooms;
import com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedException.Reason;
import com.ruleup.ruleup_backend.admin.service.SanctionLeaveListener;
import com.ruleup.ruleup_backend.challenge.lifecycle.ScoreStateOutboxHandler;
import com.ruleup.ruleup_backend.challenge.service.ChallengeHardDeleter;
import com.ruleup.ruleup_backend.challenge.service.CheatKickOutboxHandler;
import com.ruleup.ruleup_backend.room.service.PermissionKickHandler;
import com.ruleup.ruleup_backend.room.service.PermissionKickScoreHandler;
import com.ruleup.ruleup_backend.room.service.RoomCycleResultHandler;
import com.ruleup.ruleup_backend.score.service.AppealCorrectionOutboxHandler;
import com.ruleup.ruleup_backend.score.service.CheatScoreOutboxHandler;
import com.ruleup.ruleup_backend.score.service.LeaveScoreOutboxHandler;
import com.ruleup.ruleup_backend.score.service.ScoreInputOutboxHandler;
import com.ruleup.ruleup_backend.score.service.ScoreTierNoticeHandler;
import com.ruleup.ruleup_backend.verification.service.AppealAbuseOutboxHandler;
import com.ruleup.ruleup_backend.watcher.service.WatcherFailureOutboxHandler;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.sanction.ReviewAccounts;
import com.ruleup.ruleup_backend.sanction.SanctionService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.TempNicknameAllocator;
import com.ruleup.ruleup_backend.user.domain.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedDemo.KST;
import static com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedDemo.bytes;
import static com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedDemo.uuid;

/**
 * 심사 계정 시드 — {@code create}(새 회차) · {@code reset}(같은 회차 원상 복구) (심사 계정 테크 스펙 5-3 · 5-7 · 5-8).
 *
 * <ul>
 *   <li><b>create</b>: round 를 실행일(KST) + 순번으로 <b>발급</b>하고 새 UUIDv7 계정과 데모 데이터를 만든다. 같은 ID 나 그
 *       round 의 subject 가 하나라도 있으면 아무 행도 남기지 않고 실패한다</li>
 *   <li><b>reset</b>: round 를 서버와 같은 설정({@link ReviewAccountPolicy})에서 읽는다. 대상은 설정 codes 의 계정(폐기 포함)이고
 *       전부 그 round 의 계정이어야 한다. 하나라도 어긋나면 아무것도 바꾸지 않는다</li>
 *   <li>공통: 한 트랜잭션 — 중간에 실패하면 전부 되돌린다. 기존 행의 {@code oauth_subject} 를 고치지 않는다.
 *       출력은 round · UUID · 건수뿐이고 코드는 만들지도 받지도 않는다(5-7)</li>
 * </ul>
 *
 * <h4>리셋이 지우는 것과 남기는 것</h4>
 * <ul>
 *   <li>심사 계정·더미만 있던 방은 지운다(실제 삭제). 실제 유저가 있거나 있었던 방은 일반 방으로 남기고, 그 방의
 *       심사 계정·더미는 외부 사유 나가기로 뺀다 — 방장이 그쪽이면 봇방장이 된다</li>
 *   <li>심사 계정의 판정 이력·이의·점수 원장·알림함을 지우고 새 데모 방으로 다시 채운다. 같은 날 리셋이면 점수·티어가
 *       생성 직후와 같다</li>
 *   <li>탈퇴했으면 일반 복원과 같게 되살린다. 변경 잠금·개별 동의 2종 현재 상태·푸시 토큰·기기 정보를 비운다.
 *       동의 이력(입증 근거)과 RT(평가 중 로그인 유지)는 남긴다</li>
 *   <li>더미의 계정 행·전역 점수 원장은 건드리지 않는다 — 회차 간 공유라 지운 방의 이력만큼 랭킹이 바뀔 뿐이다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReviewSeedService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSeedService.class);

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern REVIEW_SUBJECT = Pattern.compile("ruleup-review-(\\d{8})(\\d{2})-(\\d{2})");
    /** 리셋으로 빠지는 방의 나가기 사유 — 탈퇴의 외부 나가기와 같다(감점·재입장 대기 없음). */
    private static final String RESET_LEAVE_REASON = "VOLUNTARY";
    /** 리셋이 닫은 아웃박스 행의 표식(last_error) — 실패가 아니라 「리셋 전 사건이라 적용하지 않았다」. */
    static final String RETIRED_BY_RESET = "REVIEW_SEED_RESET: 리셋 전 사건 — 적용하지 않고 닫았다";

    /**
     * 리셋이 닫는 아웃박스 사건 — <b>리셋이 지우는 원본(판정·이의·원장·점수 상태·알림함)에서 나온 사건</b>.
     * 새 회차 원장 위에 옛 판정·감점·티어가 끼면 안 되고, 원본이 사라진 사건은 처리기도 할 일이 없다.
     *
     * <p>감시자 실패 통지도 여기 둔다. 리셋은 그 실패 자체를 지운다 — 처리기는 판정 행이 없으면 보내지 않고 끝나며, 지운
     * 실패를 알리면 감시자가 여는 화면에 원본이 없다. 그래서 「심사 계정의 리셋 전 실패 중 아직 안 나간 통지는 보내지 않는다」로
     * 정하고, 조용히 사라지는 대신 표식을 남겨 닫는다. 이미 나간 통지는 감시자 쪽 기록이라 건드리지 않는다.
     */
    static final Set<String> SUPERSEDED_EVENT_TYPES = Set.of(
            ScoreInputOutboxHandler.TYPE,                 // 판정 → 점수 입력
            LeaveScoreOutboxHandler.TYPE,                 // 나가기 감점
            PermissionKickScoreHandler.TYPE,              // 권한 미허용 강퇴 감점
            CheatScoreOutboxHandler.OUTBOX_TYPE,          // 부정행위 감점
            AppealCorrectionOutboxHandler.OUTBOX_TYPE,    // 이의 인용 점수 정정(이의·판정을 지운다)
            ScoreTierNoticeHandler.TYPE,                  // 옛 점수의 티어 고지
            ScoreStateOutboxHandler.TYPE,                 // 옛 점수 상태의 티어 게이트 나가기
            RoomCycleResultHandler.TYPE,                  // 옛 주기 결과의 연속 실패 경고·강퇴
            WatcherFailureOutboxHandler.OUTBOX_TYPE,      // 지운 실패의 감시자 통지(판정 행이 원본)
            AppealAbuseOutboxHandler.OUTBOX_TYPE);        // 지운 이의의 남용 표본(이의 행이 원본)

    /**
     * 리셋이 그대로 두는 사건 — 리셋이 지우지 않는 원본에서 나온 운영 집행이다. 관리자 제재의 방 나가기는 제재가 유지되는 한
     * 리셋 뒤에도 집행돼야 한다(닫으면 제재 중인 계정이 새 데모 방에 남는다). 부정행위·권한 강퇴는 원본(검출·권한 대기)이
     * 남고, 자동 강퇴는 심사 계정을 빼므로({@code AutomaticKickService.enforce}) 리셋 뒤 데모 방에 영향이 없다.
     * 새 사건 종류는 두 목록 중 하나에 넣어야 한다(테스트가 막는다) — 원본을 리셋이 지우는지가 기준이다.
     */
    static final Set<String> KEPT_EVENT_TYPES = Set.of(
            SanctionLeaveListener.OUTBOX_TYPE,
            CheatKickOutboxHandler.OUTBOX_TYPE,
            PermissionKickHandler.TYPE);

    private final ReviewSeedDemo demo;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final PlatformTransactionManager txManager;
    private final UserRepository userRepository;
    private final ChallengeHardDeleter hardDeleter;
    private final ChallengeMemberService memberService;
    private final SanctionService sanctionService;
    private final TempNicknameAllocator tempNicknameAllocator;
    private final NotificationPublisher notificationPublisher;

    /** 생성 계획 — 아무것도 쓰지 않고 round 와 계정 UUID 만 정한다. */
    public record CreatePlan(String round, List<UUID> accountIds, LocalDate evaluationEnd) {
        public CreatePlan {
            accountIds = List.copyOf(accountIds);
        }
    }

    /**
     * @param followUpsComplete 커밋 뒤 마무리(통계 재계산·탐색 인덱스)까지 끝났는가. false 여도 데이터는 커밋됐다 —
     *                          두 값은 5분 통계 보정·탐색 대조가 원천에서 다시 맞춘다
     */
    public record CreateResult(String round, List<UUID> accountIds, List<UUID> dummyIds,
                               UUID manualRoomId, UUID autoRoomId, boolean followUpsComplete) {
        public CreateResult {
            accountIds = List.copyOf(accountIds);
            dummyIds = List.copyOf(dummyIds);
        }
    }

    /** @param followUpsComplete {@link CreateResult#followUpsComplete()} 와 같다. */
    public record ResetResult(String round, List<UUID> accountIds, UUID manualRoomId, UUID autoRoomId,
                              boolean followUpsComplete) {
        public ResetResult {
            accountIds = List.copyOf(accountIds);
        }
    }

    // =====================================================================
    // create
    // =====================================================================

    public CreateResult create(int accounts, LocalDate evaluationEnd) {
        return apply(plan(accounts, evaluationEnd));
    }

    /** round 발급 + 계정 UUID 발급. 읽기만 한다. */
    public CreatePlan plan(int accounts, LocalDate evaluationEnd) {
        if (accounts < 1 || accounts > ReviewSeedCommand.MAX_ACCOUNTS)
            throw new IllegalArgumentException("계정 수는 1~" + ReviewSeedCommand.MAX_ACCOUNTS + " 이어야 한다: " + accounts);
        LocalDate today = demo.today();
        checkEvaluationEnd(evaluationEnd, today);

        String date = today.format(YMD);
        int last = jdbc.queryForList("SELECT oauth_subject FROM users WHERE oauth_subject LIKE ?", String.class,
                        "ruleup-review-" + date + "%")
                .stream().map(REVIEW_SUBJECT::matcher).filter(Matcher::matches)
                .filter(m -> m.group(1).equals(date))
                .mapToInt(m -> Integer.parseInt(m.group(2))).max().orElse(0);
        if (last >= 99)
            throw new ReviewSeedException(Reason.ROUND_EXHAUSTED, "date=" + date + " 의 순번 99 를 다 썼다");
        String round = date + "%02d".formatted(last + 1);

        List<UUID> ids = new ArrayList<>(accounts);
        for (int i = 0; i < accounts; i++) ids.add(UuidGenerator.generate());
        return new CreatePlan(round, ids, evaluationEnd);
    }

    /** 계획대로 만든다 — 같은 ID·같은 round 의 행이 하나라도 있으면 아무것도 남기지 않고 실패한다. */
    public CreateResult apply(CreatePlan plan) {
        checkEvaluationEnd(plan.evaluationEnd(), demo.today());
        String round = plan.round();

        CreateResult committed = tx.execute(status -> {
            Object[] ids = plan.accountIds().stream().map(ReviewSeedDemo::bytes).toArray();
            if (demo.count("SELECT COUNT(*) FROM users WHERE id IN (" + placeholders(ids.length) + ")", ids) > 0)
                throw new ReviewSeedException(Reason.ACCOUNT_EXISTS, "round=" + round + " 의 계정 UUID 행이 이미 있다");
            if (demo.count("SELECT COUNT(*) FROM users WHERE oauth_subject LIKE ?", "ruleup-review-" + round + "-%") > 0)
                throw new ReviewSeedException(Reason.ROUND_TAKEN, "round=" + round + " 의 계정이 이미 있다");

            List<Account> accounts = new ArrayList<>();
            for (int i = 0; i < plan.accountIds().size(); i++) {
                UUID id = plan.accountIds().get(i);
                demo.createAccount(id, round, i + 1);
                accounts.add(new Account(id, i + 1));
            }
            List<UUID> dummies = demo.ensureDummies(ReviewSeedDemo.DUMMIES_PER_ROOM);
            Rooms rooms = demo.build(accounts, dummies, plan.evaluationEnd());
            return new CreateResult(round, plan.accountIds(), dummies, rooms.manualRoomId(), rooms.autoRoomId(), false);
        });

        // 커밋된 결과를 마무리보다 먼저 남긴다 — 마무리가 실패해도 운영자가 설정에 넣을 round·UUID 는 이 줄에 있다
        log.info("review_seed_created round={} evaluationEnd={} accounts={} dummies={} manualRoom={} autoRoom={}",
                round, plan.evaluationEnd(), committed.accountIds(), committed.dummyIds(),
                committed.manualRoomId(), committed.autoRoomId());
        int failed = demo.afterCommit(List.of(committed.manualRoomId(), committed.autoRoomId()));
        if (failed > 0)
            log.warn("review_seed_followup_incomplete mode=create round={} failedSteps={} — 데이터는 커밋됐다. "
                    + "통계·탐색은 5분 보정·탐색 대조가 다시 맞춘다", round, failed);
        return new CreateResult(round, committed.accountIds(), committed.dummyIds(),
                committed.manualRoomId(), committed.autoRoomId(), failed == 0);
    }

    private static void checkEvaluationEnd(LocalDate evaluationEnd, LocalDate today) {
        if (evaluationEnd == null || !evaluationEnd.isAfter(today))
            throw new IllegalArgumentException("평가 종료일은 오늘 뒤여야 한다: " + evaluationEnd);
        // 서버 설정 검사(round 날짜 ~ expiresAt ≤ 60일)를 통과할 수 있어야 한다 — expiresAt 은 종료일 다음 날 00:00 KST
        LocalDate latest = today.plusDays(ReviewAccountPolicy.MAX_REVIEW_LENGTH.toDays() - 1);
        if (evaluationEnd.isAfter(latest))
            throw new IllegalArgumentException("평가 종료일은 " + latest + " 까지다(회차 최대 "
                    + ReviewAccountPolicy.MAX_REVIEW_LENGTH.toDays() + "일): " + evaluationEnd);
    }

    // =====================================================================
    // reset
    // =====================================================================

    /**
     * 이번 회차 계정을 시드 상태로 되돌린다.
     *
     * @param scope        삭제 범위의 기준({@code REVIEW_ACCOUNT_IDS}) — 심사 계정·더미. 이 밖의 계정 행은 바꾸지 않는다
     * @param config       서버와 같은 심사 설정 — round · codes · 스위치를 여기서 읽는다
     * @param confirmRound 운영자의 확인 값(선택). 있으면 설정의 round 와 같아야 한다
     */
    public ResetResult reset(ReviewAccounts scope, ReviewAccountPolicy config, String confirmRound) {
        if (!config.isSwitchOn())
            throw new ReviewSeedException(Reason.SWITCHED_OFF, "심사 설정의 스위치가 꺼져 있다(enabled=false 또는 만료)");
        String round = config.round();
        if (confirmRound != null && !confirmRound.equals(round))
            throw new ReviewSeedException(Reason.ROUND_MISMATCH, "확인 round=" + confirmRound + " 가 설정 round=" + round + " 와 다르다");
        LocalDate evaluationEnd = LocalDate.ofInstant(config.expiresAt(), KST).minusDays(1);
        List<UUID> codeIds = new ArrayList<>(config.codeAccountIds());

        // 대상 방은 트랜잭션 밖에서 먼저 찾는다 — 트랜잭션 안의 첫 일반 읽기는 방 잠금 뒤여야 한다(resetInTransaction)
        Set<UUID> rooms = roomsOf(codeIds);
        Reset reset = tx.execute(status -> resetInTransaction(scope, config, round, codeIds, rooms, evaluationEnd));

        List<UUID> accountIds = reset.accounts().stream().map(Account::id).toList();
        log.info("review_seed_reset round={} accounts={} restored={} deletedRooms={} keptRooms={} retiredEvents={} "
                        + "manualRoom={} autoRoom={}",
                round, accountIds, reset.restored(), reset.deletedRooms(), reset.keptRooms(), reset.retiredEvents(),
                reset.rooms().manualRoomId(), reset.rooms().autoRoomId());
        List<UUID> touched = new ArrayList<>(reset.keptRooms());
        touched.addAll(reset.rooms().ids());
        int failed = demo.afterCommit(touched);
        if (failed > 0)
            log.warn("review_seed_followup_incomplete mode=reset round={} failedSteps={} — 데이터는 커밋됐다. "
                    + "통계·탐색은 5분 보정·탐색 대조가 다시 맞춘다", round, failed);
        return new ResetResult(round, accountIds, reset.rooms().manualRoomId(), reset.rooms().autoRoomId(), failed == 0);
    }

    private record Reset(List<Account> accounts, List<UUID> restored, List<UUID> deletedRooms,
                         List<UUID> keptRooms, int retiredEvents, Rooms rooms) {}

    /** 그 계정들이 멤버(상태 무관)이거나 방장인 방 — id 순. */
    private Set<UUID> roomsOf(List<UUID> userIds) {
        Object[] ub = userIds.stream().map(ReviewSeedDemo::bytes).toArray();
        String in = "(" + placeholders(ub.length) + ")";
        Set<UUID> rooms = new TreeSet<>();
        rooms.addAll(jdbc.query("SELECT DISTINCT challenge_id FROM challenge_members WHERE user_id IN " + in,
                (rs, n) -> uuid(rs.getBytes(1)), ub));
        rooms.addAll(jdbc.query("SELECT id FROM challenges WHERE owner_id IN " + in, (rs, n) -> uuid(rs.getBytes(1)), ub));
        return rooms;
    }

    private Reset resetInTransaction(ReviewAccounts scope, ReviewAccountPolicy config, String round,
                                     List<UUID> codeIds, Set<UUID> rooms, LocalDate evaluationEnd) {
        // --- 대상 확정 — 행을 잠그고, 하나라도 어긋나면 쓰기 전에 멈춘다 ---
        List<Account> accounts = new ArrayList<>();
        int missing = 0;
        for (UUID id : codeIds) {
            List<String> subject = jdbc.queryForList("SELECT oauth_subject FROM users WHERE id = ? FOR UPDATE",
                    String.class, (Object) bytes(id));
            if (subject.isEmpty()) {
                missing++;
                continue;
            }
            String s = subject.getFirst();
            Matcher m = (s == null) ? null : REVIEW_SUBJECT.matcher(s);
            if (m == null || !m.matches() || !config.isCurrentRoundAccount(s))
                throw new ReviewSeedException(Reason.MIXED_ROUND, "codes 의 계정 " + id + " 는 round=" + round + " 의 계정이 아니다");
            if (!scope.isExempt(id))
                throw new IllegalStateException("심사 계정 " + id + " 가 REVIEW_ACCOUNT_IDS 에 없다");
            accounts.add(new Account(id, Integer.parseInt(m.group(3))));
        }
        if (accounts.isEmpty())
            throw new ReviewSeedException(Reason.NO_TARGET, "round=" + round + " codes 의 계정 행이 하나도 없다");
        if (missing > 0)
            throw new ReviewSeedException(Reason.MIXED_ROUND, "codes 중 " + missing + "건의 계정 행이 없다 — 이번 회차 계정이 아니다");
        accounts.sort(Comparator.comparingInt(Account::seq));   // 순번이 이력 무늬를 정한다
        List<UUID> targets = accounts.stream().map(Account::id).toList();
        Object[] tb = targets.stream().map(ReviewSeedDemo::bytes).toArray();
        String in = "(" + placeholders(tb.length) + ")";

        // --- 방 정리 — 실제 삭제가 영속성 컨텍스트를 비우므로 엔티티를 읽기 전에 한다 ---
        // 지울지 남길지는 가입 경로와 같은 방 행 잠금(FOR UPDATE)을 잡은 뒤의 최신 멤버로 정한다. 데모 방은 공개라 리셋
        // 중에도 실제 유저가 들어올 수 있다 — 가입은 방 행을 잠그고 멤버를 넣으므로, 잠금을 잡은 뒤의 가입은 리셋 커밋을
        // 기다렸다가 지워진 방이면 실패하고, 그 전에 커밋된 가입은 아래 읽기에 보인다.
        // 이 트랜잭션의 첫 일반(스냅숏) 읽기는 잠금 뒤다 — InnoDB 는 첫 일반 읽기에서 스냅숏을 고정하므로, 잠금 전에 읽으면
        // 그 뒤의 읽기(마지막 멤버 판정의 인원 수 등)가 잠금을 기다리는 동안 커밋된 가입을 못 본다. 대상 방 찾기를 잠금
        // 읽기(FOR SHARE)로 하지 않는 이유는 보조 인덱스 갭 잠금이 가입의 INSERT 와 교착을 만들 수 있어서다.
        for (UUID roomId : rooms) {   // id 순 — 같은 방들을 잡는 다른 실행과 순서가 같다
            jdbc.queryForList("SELECT id FROM challenges WHERE id = ? FOR UPDATE", (Object) bytes(roomId));
        }
        Set<UUID> current = roomsOf(targets);
        if (!rooms.containsAll(current))
            throw new IllegalStateException("리셋을 준비하는 사이 심사 계정의 참여 방이 바뀌었다 — 아무것도 바꾸지 않았다. 다시 실행하면 된다");
        jdbc.update("DELETE FROM challenge_kicks WHERE user_id IN " + in, tb);
        jdbc.update("DELETE FROM challenge_rejoin_backoffs WHERE user_id IN " + in, tb);

        List<UUID> deleted = new ArrayList<>();
        Map<UUID, List<UUID>> leaving = new LinkedHashMap<>();
        for (UUID roomId : current) {
            List<Map<String, Object>> members = jdbc.queryForList(
                    "SELECT user_id, status FROM challenge_members WHERE challenge_id = ?", (Object) bytes(roomId));
            List<Map<String, Object>> owner = jdbc.queryForList(
                    "SELECT owner_id, owner_type FROM challenges WHERE id = ?", (Object) bytes(roomId));
            if (owner.isEmpty()) continue;   // 찾은 뒤 지워진 방
            boolean onlyReview = members.stream().allMatch(r -> scope.isExempt(uuid((byte[]) r.get("user_id"))))
                    && owner.stream().allMatch(r -> !"USER".equals(r.get("owner_type"))
                    || scope.isExempt(uuid((byte[]) r.get("owner_id"))));
            if (onlyReview) {
                deleted.add(roomId);
            } else {
                // 한 번이라도 실제 유저가 있었던 방 — 그 유저의 참여 기록이 남도록 방을 지우지 않는다
                leaving.put(roomId, members.stream()
                        .filter(r -> "ACTIVE".equals(r.get("status")))
                        .map(r -> uuid((byte[]) r.get("user_id")))
                        .filter(scope::isExempt).toList());
            }
        }
        for (UUID roomId : deleted) hardDeleter.hardDelete(roomId);
        for (var e : leaving.entrySet()) {
            for (UUID userId : e.getValue()) memberService.leaveExternally(userId, e.getKey(), RESET_LEAVE_REASON);
        }
        em.flush();
        em.clear();

        // --- 심사 계정의 이력·점수·알림함 ---
        jdbc.update("DELETE mr FROM VerificationMethodResult mr JOIN VerificationDaily d ON d.id = mr.verificationDailyId "
                + "WHERE d.userId IN " + in, tb);
        jdbc.update("DELETE FROM verification_appeals WHERE userId IN " + in, tb);
        jdbc.update("DELETE FROM VerificationDaily WHERE userId IN " + in, tb);   // 실패 상세는 함께 지워진다
        jdbc.update("DELETE FROM cycle_score_states WHERE user_id IN " + in, tb);
        jdbc.update("DELETE FROM score_transactions WHERE user_id IN " + in, tb);
        // 요약 행은 남긴다 — 버전이 이어져야 점수 상태 아웃박스의 중복 키가 지난 사건과 겹치지 않는다
        jdbc.update("UPDATE user_score_summaries SET total_score = 10, actual_tier = 'BRONZE', display_tier = 'BRONZE' "
                + "WHERE user_id IN " + in, tb);
        jdbc.update("DELETE FROM notifications WHERE user_id IN " + in, tb);

        // --- 계정 상태 ---
        List<UUID> restored = new ArrayList<>();
        for (Account a : accounts) {
            if (restoreAccount(a, round)) restored.add(a.id());
        }
        em.flush();
        for (Account a : accounts) {
            byte[] id = bytes(a.id());
            jdbc.update("UPDATE users SET profile_changed_at = NULL, nickname_changed_at = NULL, installation_id = ?, "
                            + "device_id = NULL, platform = NULL, app_version_code = NULL, app_version_name = NULL, "
                            + "os_version = NULL, sdk_int = NULL, device_model = NULL, manufacturer = NULL, low_ram = NULL, "
                            + "ram_mb = NULL, device_info_updated_at = NULL WHERE id = ?",
                    ReviewSeedDemo.installationOf(round, a.seq()), id);
            // 개별 동의 2종은 현재 상태만 지운다 — 이력(입증 근거)은 남는다
            jdbc.update("DELETE FROM user_agreement_states WHERE user_id = ? AND agreement_type IN ('LOCATION_INFO','HEALTH_INFO')",
                    (Object) id);
            jdbc.update("DELETE FROM DeviceToken WHERE userId = ?", (Object) id);
            demo.agreeTerms(a.id());
            demo.initializeScore(a.id());   // 원장을 비웠다 — 가입 사건(users.created_at)부터 다시 쌓는다
        }

        // --- 새 데모 방 ---
        List<UUID> dummies = demo.ensureDummies(ReviewSeedDemo.DUMMIES_PER_ROOM);
        Rooms built = demo.build(accounts, dummies, evaluationEnd);
        List<UUID> kept = new ArrayList<>(leaving.keySet());

        // --- 리셋 전의 미처리 사건 — 맨 끝에서 닫는다(그 사이 커밋된 후속 사건까지 보이게) ---
        int retired = retireEarlierEvents(targets);
        return new Reset(accounts, restored, deleted, kept, retired, built);
    }

    /**
     * 리셋 전에 쌓였지만 아직 적용되지 않은 심사 계정의 아웃박스 사건 중 {@link #SUPERSEDED_EVENT_TYPES} 만 「처리됨」으로
     * 닫는다({@link #KEPT_EVENT_TYPES} 는 그대로 집행된다). 돌려준 값은 닫은 건수.
     *
     * <p>리셋은 원장·판정·알림함을 지우고 다시 만든다. 그런데 그 전에 쌓인 사건(나가기 감점·점수 입력·티어 고지·티어
     * 게이트 나가기·이의 정정 등)이 대기·백오프 중이거나 포기돼 있으면, 디스패처가 리셋 뒤에 그것을 새 원장 위에
     * 적용한다 — 심사위원이 방을 나간 직후 리셋하면 리셋한 점수가 곧 다시 깎인다. 포기분도 매일 04:40 자동 재적재가
     * 되살리므로 함께 닫는다. 지우지 않고 닫는 이유는 흔적(표식 {@link #RETIRED_BY_RESET})을 남기기 위해서다 — 처리분과
     * 같이 14일 보관 뒤 정리된다.
     *
     * <p><b>새 트랜잭션의 최신 스냅숏으로 찾는다.</b> 이 트랜잭션의 스냅숏은 방 잠금 직후에 고정돼, 그 뒤 다른 처리가
     * 커밋하며 남긴 후속 사건(예: 감점 처리 → 티어 고지)을 보지 못한다. 반대로 이 트랜잭션이 방금 정산하며 쌓은 새 회차의
     * 사건은 커밋 전이라 새 트랜잭션에 보이지 않는다 — 리셋 전 사건만 골라진다. 찾는 쪽은 잠그지 않는 일반 읽기라
     * 아웃박스 전체를 막지 않고, 닫기는 PK 로 한 행씩 잠근다(현재 읽기라 그사이 처리된 행은 건드리지 않는다).
     *
     * <p>디스패처가 그 행을 처리하는 중이면 행 잠금에서 기다린다. 그 처리가 이 트랜잭션이 잡은 행(원장 등)을 기다리면
     * 교착이고, MySQL 이 한쪽을 되돌린다 — 처리 쪽이 지면 재시도 전에 여기서 닫히고, 리셋이 지면 아무것도 바뀌지 않은
     * 채 실패(종료 1)하니 다시 실행하면 된다.
     */
    private int retireEarlierEvents(List<UUID> targets) {
        List<Object> args = new ArrayList<>(SUPERSEDED_EVENT_TYPES);
        targets.forEach(t -> args.add(t.toString()));
        TransactionTemplate fresh = new TransactionTemplate(txManager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setReadOnly(true);
        List<byte[]> found = fresh.execute(status -> jdbc.query(
                "SELECT id FROM outbox_messages WHERE (processed_at IS NULL OR dead_lettered_at IS NOT NULL) "
                        + "AND type IN (" + placeholders(SUPERSEDED_EVENT_TYPES.size()) + ") "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.userId')) IN (" + placeholders(targets.size()) + ")",
                (rs, n) -> rs.getBytes(1), args.toArray()));
        if (found == null || found.isEmpty()) return 0;
        Object now = DbTime.utc(Instant.now());
        int retired = 0;
        for (byte[] id : found) {
            retired += jdbc.update("UPDATE outbox_messages SET processed_at = ?, dead_lettered_at = NULL, last_error = ? "
                    + "WHERE id = ? AND (processed_at IS NULL OR dead_lettered_at IS NOT NULL)", now, RETIRED_BY_RESET, id);
        }
        return retired;
    }

    /**
     * 탈퇴했으면 일반 복원과 같게 되살리고, 닉네임·프로필 사진을 시드 값으로 돌린다. 시드 닉네임을 다른 계정이 쓰고 있으면
     * 지금 닉네임을 두되, 그것도 겹치면 소셜 복원처럼 임시 닉네임 + 충돌 알림으로 둔다.
     *
     * @return 탈퇴에서 되살렸으면 true
     */
    private boolean restoreAccount(Account a, String round) {
        UUID id = a.id();
        User user = userRepository.findById(id).orElseThrow();
        String seedNickname = ReviewSeedDemo.accountNickname(round, a.seq());
        boolean seedNicknameFree = !userRepository.isNicknameTaken(seedNickname, id);
        boolean withdrawn = user.isWithdrawn();
        if (withdrawn) {
            boolean conflict = !seedNicknameFree && (userRepository.isNicknameTaken(user.getApprovedNickname(), id)
                    || (user.isNicknamePending() && userRepository.isNicknameTaken(user.getNickname(), id)));
            if (conflict) {
                tempNicknameAllocator.assign(user, value -> userRepository.isNicknameTaken(value, id));
                user.markNicknameConflict();
                notificationPublisher.publish(NotificationEvent.of(id, NotificationType.MODERATION_REJECTED,
                        Map.of(NotificationParams.VARIANT, "NICKNAME_TAKEN", NotificationParams.TARGET_KEY, "nickname",
                                NotificationParams.EVENT_KEY, "review-reset:" + Instant.now())));
            }
            user.restore(user.getInstallationId(), null);
            sanctionService.thawAll(id, Instant.now());
        }
        userRepository.saveAndFlush(user);
        if (seedNicknameFree) {
            jdbc.update("UPDATE users SET nickname = ?, approved_nickname = ?, nickname_status = 'APPROVED', "
                    + "profile_image_key = NULL, profile_image_status = 'NONE' WHERE id = ?", seedNickname, seedNickname, bytes(id));
        }
        return withdrawn;
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
