package com.ruleup.ruleup_backend.auth.review.seed;

import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengePenalties;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.TargetModerationStatus;
import com.ruleup.ruleup_backend.challenge.draft.DraftView;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreIndexer;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsProjectionService;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.domain.RoutineLabels;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.service.ScoreService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.Gender;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.domain.Appeal;
import com.ruleup.ruleup_backend.verification.domain.FailureEvidence;
import com.ruleup.ruleup_backend.verification.domain.FailureReasons;
import com.ruleup.ruleup_backend.verification.domain.Polarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationFailureDetail;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.service.VerificationConfigFactory;
import com.ruleup.ruleup_backend.verification.service.VerificationMemberSetup;
import com.ruleup.ruleup_backend.verification.service.VerificationProgressService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 심사 계정이 들어가 볼 데모 데이터 — 데모 그룹방 2개 · 더미 멤버 · 최근 4주 판정 이력 · 점수 · 알림함 샘플
 * (심사 계정 테크 스펙 5-3 「데모 데이터 구성」). 생성·리셋이 같은 것을 만든다.
 *
 * <h4>결정</h4>
 * <ul>
 *   <li>방장은 더미 멤버 — 심사 계정이 방장이면 한 심사위원의 나가기·탈퇴가 다른 심사위원의 방을 바꾼다</li>
 *   <li>날짜는 실행일 기준 상대값이고 이력 무늬는 계정 순번(subject 의 NN)으로 정해진다 — 같은 날 같은 순번이면
 *       이력·점수가 같다. 그래서 리셋하면 생성 직후와 점수·티어가 같다</li>
 *   <li>판정 행은 실제 판정 경로와 같은 엔티티 전이(열기 → 성공·실패 확정·이의 인용·수동 체크)로 만든다 — 확정 시각·이의
 *       기한·공유 시각을 손으로 맞추지 않는다. 점수는 그 행을 정산 로직({@link ScoreService})에 넣어 산출한다</li>
 *   <li>더미 멤버도 같은 정산을 거친다 — 빼 두면 점수 보정 배치가 원장 없는 확정 이력으로 보고 곧 정산한다. 같은 날짜대
 *       이력이 다시 들어가는 리셋은 그 더미 원장의 해당 구간을 다시 재생한다(운영에서는 회차당 몇 번이라 감당할 양이다)</li>
 *   <li>수동 방은 「체크하지 않은 날」이 행 없음이다(실제로도 수동 방 미체크는 판정 행을 열지 않는다)</li>
 *   <li>자동 방의 실패는 주 1회 이하 — 주간 결과가 부분 성공에 머물러 연속 실패 경고·강퇴로 이어지지 않는다</li>
 *   <li>감시자는 지정하지 않는다 — 실제 번호가 들어가면 SMS 가 나간다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ReviewSeedDemo {

    private static final Logger log = LoggerFactory.getLogger(ReviewSeedDemo.class);

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 이력 기간 — 오늘-28 ~ 오늘-2(어제·오늘은 확정 시각 전이라 확정 행이 없다). */
    static final int HISTORY_DAYS = 28;
    /** 가입·계정 생성은 이력 시작보다 충분히 앞 — 점수의 가입 사건이 판정보다 먼저다. */
    static final int ACCOUNT_AGE_DAYS = 35;
    static final int DUMMIES_PER_ROOM = 5;
    static final long STEPS_TEMPLATE_ID = 1201L;
    static final int STEP_GOAL = 10_000;
    static final List<AgreementType> TERMS = List.of(AgreementType.TOS, AgreementType.PRIVACY,
            AgreementType.LOCATION, AgreementType.MARKETING, AgreementType.EVENT);
    private static final String DUMMY_PREFIX = "ruleup-demo-";
    private static final String DUMMY_INSTALLATION_PREFIX = "demo-inst-";

    /**
     * 더미 subject·설치 값의 이름 공간 — 운영은 빈 값이라 스펙 5-3 대로 {@code ruleup-demo-NN} · {@code demo-inst-NN} 이다.
     *
     * <p>테스트 전용 이음새다. 더미는 회차와 무관하게 다시 쓰이므로, 같은 날 수십 번 생성·리셋하는 테스트에서는 같은 더미 5명에
     * 같은 날짜대의 이력이 계속 쌓여 정산이 매번 그 구간을 재생한다(원장·재생 비용이 실행 횟수의 제곱으로 는다 — 8회에 원장
     * 2만 행). 운영은 한 회차의 생성·리셋 몇 번뿐이라 문제가 없고, 테스트는 테스트마다 이름 공간을 바꿔 새 더미를 쓴다.
     */
    String dummyNamespace = "";
    private static final List<String> DUMMY_NICKNAMES = List.of(
            "아침산책러", "만보도전", "책읽는곰", "꾸준히가자", "오늘도한걸음",
            "하루한쪽", "새벽러너", "걷기왕", "루틴지킴이", "작심백일");
    private static final String APPEAL_REASON = "휴대폰을 두고 산책해서 걸음이 덜 잡혔어요. 실제로는 목표만큼 걸었어요.";

    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final UserRepository userRepository;
    private final AgreementService agreementService;
    private final AppProperties props;
    private final ScoreService scoreService;
    private final RoutineCatalog catalog;
    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final VerificationMemberSetup memberSetup;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final ChallengeStatsProjectionService statsProjection;
    private final ExploreIndexer exploreIndexer;
    private final NotificationPublisher notificationPublisher;

    /** 데모 방 두 개. */
    record Rooms(UUID manualRoomId, UUID autoRoomId) {
        List<UUID> ids() { return List.of(manualRoomId, autoRoomId); }
    }

    /** 심사 계정 한 명 — 이력 무늬는 순번으로 정한다. */
    record Account(UUID id, int seq) {}

    LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }

    Instant accountCreatedAt() {
        return today().minusDays(ACCOUNT_AGE_DAYS).atTime(LocalTime.of(10, 0)).atZone(KST).toInstant();
    }

    // =====================================================================
    // 계정 · 더미
    // =====================================================================

    /** 심사 계정 행 — 승인된 닉네임 · 만 14세 이상 · 고정 설치 값 · device_id NULL · 약관 5종 동의 · 가입 점수. */
    void createAccount(UUID id, String round, int seq) {
        String nn = "%02d".formatted(seq);
        String nickname = accountNickname(round, seq);
        if (userRepository.isNicknameTaken(nickname, null))
            throw new IllegalStateException("심사 계정 닉네임이 이미 쓰이고 있다 round=" + round + " seq=" + nn);
        User user = User.createWithId(id, OAuthProvider.GOOGLE, "ruleup-review-" + round + "-" + nn, nickname,
                List.of("EXERCISE", "READING"));
        user.approveNickname();                                                  // 심사 중 상태로 시작하지 않는다
        user.registerDemographics(LocalDate.of(1995, 3, 15), Gender.FEMALE);     // 만 14세 이상
        user.attachInstallation(installationOf(round, seq), null);               // device_id 는 NULL
        createUser(user);
    }

    static String accountNickname(String round, int seq) {
        // 심사(2) + yyMMddSS(8) + NN(2) = 12자 — 닉네임 규칙(한글·숫자 2~12자) 안이고 회차마다 다르다
        return "심사" + round.substring(2) + "%02d".formatted(seq);
    }

    static String installationOf(String round, int seq) {
        return "review-inst-" + round + "-" + "%02d".formatted(seq);
    }

    /**
     * 더미 멤버 — 회차와 무관하게 유지한다. 이력보다 먼저 가입한 ACTIVE 더미가 있으면 다시 쓰고, 모자라면 더 만든다.
     * 다시 쓰는 더미의 계정 행은 건드리지 않는다.
     */
    List<UUID> ensureDummies(int count) {
        Instant joinBound = joinedAt();
        String prefix = DUMMY_PREFIX + dummyNamespace;
        Pattern dummySubject = Pattern.compile(Pattern.quote(prefix) + "(\\d+)");
        String like = prefix.replace("_", "\\_").replace("%", "\\%") + "%";
        List<UUID> pool = new ArrayList<>();
        jdbc.query("SELECT id, oauth_subject FROM users WHERE oauth_subject LIKE ? AND status = 'ACTIVE' AND created_at < ? "
                        + "ORDER BY oauth_subject",
                rs -> {
                    if (pool.size() < count && dummySubject.matcher(rs.getString(2)).matches()) pool.add(uuid(rs.getBytes(1)));
                }, like, DbTime.utc(joinBound));
        if (pool.size() >= count) return pool;

        int next = jdbc.queryForList("SELECT oauth_subject FROM users WHERE oauth_subject LIKE ?", String.class, like)
                .stream().map(dummySubject::matcher).filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(0) + 1;
        while (pool.size() < count) {
            String nn = "%02d".formatted(next);
            String subject = prefix + nn;
            String installation = DUMMY_INSTALLATION_PREFIX + dummyNamespace + nn;
            next++;
            if (count("SELECT COUNT(*) FROM users WHERE oauth_subject = ? OR installation_id = ?", subject, installation) > 0)
                continue;
            UUID id = UuidGenerator.generate();
            User user = User.createWithId(id, OAuthProvider.GOOGLE, subject, dummyNickname(next - 1), List.of("EXERCISE"));
            user.approveNickname();
            user.registerDemographics(LocalDate.of(1993, 7, 1), (next % 2 == 0) ? Gender.MALE : Gender.FEMALE);
            user.attachInstallation(installation, null);
            createUser(user);
            pool.add(id);
        }
        return pool;
    }

    private String dummyNickname(int no) {
        String base = DUMMY_NICKNAMES.get((no - 1) % DUMMY_NICKNAMES.size());
        if (!userRepository.isNicknameTaken(base, null)) return base;
        for (int i = no; i < no + 1000; i++) {
            String candidate = (base.length() > 9 ? base.substring(0, 9) : base) + (i % 1000);
            if (!userRepository.isNicknameTaken(candidate, null)) return candidate;
        }
        throw new IllegalStateException("더미 멤버 닉네임을 정하지 못했다 no=" + no);
    }

    private void createUser(User user) {
        userRepository.saveAndFlush(user);
        // created_at 은 엔티티가 쓰지 않는 열이다 — 이력보다 앞선 가입으로 둔다(점수의 가입 사건 시각)
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", DbTime.utc(accountCreatedAt()), bytes(user.getId()));
        scoreService.initialize(user.getId());
        agreeTerms(user.getId());
    }

    void initializeScore(UUID userId) {
        scoreService.initialize(userId);
    }

    /** 약관 5종 현행 버전 동의. 이미 현행 버전으로 동의한 항목은 이력을 더 쌓지 않는다. */
    void agreeTerms(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        Instant now = Instant.now(clock);
        for (AgreementType type : TERMS) {
            String version = props.client().termsVersions().of(type);
            boolean current = count("SELECT COUNT(*) FROM user_agreement_states WHERE user_id = ? AND agreement_type = ? "
                    + "AND agreed = 1 AND version = ?", bytes(userId), type.name(), version) > 0;
            if (!current) agreementService.record(user, type, true, version, now);
        }
    }

    // =====================================================================
    // 데모 방 · 이력 · 점수 · 알림
    // =====================================================================

    /**
     * 데모 방 두 개를 만들고 심사 계정 전원과 더미를 넣은 뒤 이력·점수·알림을 채운다. 호출부 트랜잭션 안에서 돈다.
     *
     * @param accounts 순번 순서의 심사 계정
     */
    Rooms build(List<Account> accounts, List<UUID> dummies, LocalDate evaluationEnd) {
        LocalDate start = today().minusDays(HISTORY_DAYS);
        LocalDate end = evaluationEnd.plusDays(7);

        RoutineTemplate steps = catalog.findById(STEPS_TEMPLATE_ID)
                .orElseThrow(() -> new IllegalStateException("걸음 템플릿 " + STEPS_TEMPLATE_ID + " 이 카탈로그에 없다"));
        Map<String, Object> params = new LinkedHashMap<>();
        List<DraftView.DraftParam> specs = new ArrayList<>();
        for (ParamSpec spec : steps.paramSpecs()) {   // 생성 API 와 같은 모양 — 값은 문자열로 저장된다
            Object value = "steps".equals(spec.key()) ? Integer.valueOf(STEP_GOAL) : spec.defaultValue();
            if (value != null) params.put(spec.key(), String.valueOf(value));
            specs.add(new DraftView.DraftParam(spec.key(), value == null ? null : String.valueOf(value),
                    spec.defaultValue() == null ? null : String.valueOf(spec.defaultValue()),
                    spec.kind().name(), spec.unit(), spec.min(), spec.max()));
        }

        Challenge manual = createRoom(dummies.get(0), "매일 책 10쪽 읽기",
                "하루 10쪽씩 읽고 체크해요. 짧아도 매일 읽는 습관을 만들어요.", "READING",
                null, VerificationConfig.manual(null), Map.of(), List.of(), false, start, end);
        Challenge auto = createRoom(dummies.get(1 % dummies.size()), "하루 만 보 걷기",
                "건강 기록의 하루 걸음 수로 자동 인증돼요. 함께 매일 만 보를 걸어요.", "EXERCISE",
                STEPS_TEMPLATE_ID, VerificationConfig.auto(steps), params, specs, true, start, end);

        List<UUID> people = new ArrayList<>();
        accounts.forEach(a -> people.add(a.id()));
        people.addAll(dummies);
        for (Challenge room : List.of(manual, auto)) addMembers(room, people);

        // 가입 시각을 이력보다 앞으로 옮겼다 — 엔티티를 다시 읽어 설정·진행률 계산이 옮긴 시각을 보게 한다
        em.flush();
        em.clear();

        Map<UUID, Integer> pattern = new LinkedHashMap<>();
        accounts.forEach(a -> pattern.put(a.id(), a.seq() - 1));
        for (int i = 0; i < dummies.size(); i++) pattern.putIfAbsent(dummies.get(i), 10 + i);

        Map<UUID, UUID> latestSuccess = new LinkedHashMap<>();
        Map<UUID, UUID> latestFailure = new LinkedHashMap<>();
        Map<UUID, UUID> latestAppeal = new LinkedHashMap<>();
        for (UUID roomId : List.of(manual.getId(), auto.getId())) {
            Challenge room = challengeRepository.findById(roomId).orElseThrow();
            com.ruleup.ruleup_backend.verification.domain.VerificationConfig config = configFactory.build(room);
            for (ChallengeMember member : memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(roomId, MemberStatus.ACTIVE)) {
                memberSetup.apply(member, room, config);
                int k = pattern.get(member.getUserId());
                if (config.isManual()) {
                    manualHistory(member, start, k);
                } else {
                    History h = autoHistory(member, config, start, k);
                    latestSuccess.put(member.getUserId(), h.lastSuccess());
                    latestFailure.put(member.getUserId(), h.lastFailure());
                    latestAppeal.put(member.getUserId(), h.lastAppeal());
                }
                em.flush();
                progressService.recount(member);
            }
        }
        em.flush();

        // 점수 — 방 안 모든 멤버(더미 포함). 지난 주까지는 마감(마감 입력에 그 주 판정이 모두 들어간다), 이번 주는
        // 반영만(어제 몫이 아직 확정 전이다). 더미를 빼면 1분 점수 보정 배치(ScoreSyncService)가 원장 없는 확정 이력으로
        // 보고 곧 정산한다 — 시드가 끝난 뒤 점수가 바뀌지 않게 여기서 끝낸다.
        for (UUID userId : people) {
            for (int no = 1; no <= 3; no++) scoreService.closeCycle(userId, auto.getId(), no);
            scoreService.reconcileCycle(userId, auto.getId(), 4);
        }

        // 알림함 샘플 — 심사 계정에만. 푸시 대상 기기가 없어 알림함에만 남는다
        Challenge autoRoom = challengeRepository.findById(auto.getId()).orElseThrow();
        String autoTitle = RoutineLabels.of(autoRoom);
        List<NotificationEvent> events = new ArrayList<>();
        for (Account a : accounts) {
            for (UUID roomId : List.of(manual.getId(), auto.getId())) {
                events.add(NotificationEvent.forChallenge(a.id(), NotificationType.CHALLENGE_LIFECYCLE, roomId,
                        Map.of(NotificationParams.CHALLENGE_ID, roomId.toString(), NotificationParams.PHASE, "STARTED")));
            }
            events.add(NotificationEvent.forChallenge(a.id(), NotificationType.VERIFICATION_RESULT, auto.getId(),
                    Map.of(NotificationParams.VARIANT, "CONFIRMED_FAILURE",
                            NotificationParams.VERIFICATION_ID, latestFailure.get(a.id()).toString(),
                            NotificationParams.CHALLENGE_ID, auto.getId().toString(),
                            NotificationParams.CHALLENGE_TITLE, autoTitle)));
            events.add(NotificationEvent.forChallenge(a.id(), NotificationType.VERIFICATION_RESULT, auto.getId(),
                    Map.of(NotificationParams.VARIANT, "CONFIRMED_SUCCESS",
                            NotificationParams.VERIFICATION_ID, latestSuccess.get(a.id()).toString(),
                            NotificationParams.CHALLENGE_ID, auto.getId().toString(),
                            NotificationParams.CHALLENGE_TITLE, autoTitle)));
            events.add(NotificationEvent.of(a.id(), NotificationType.APPEAL_RESULT,
                    Map.of(NotificationParams.APPEAL_ID, latestAppeal.get(a.id()).toString())));
        }
        notificationPublisher.publishAll(events);
        return new Rooms(manual.getId(), auto.getId());
    }

    private Challenge createRoom(UUID ownerId, String title, String description, String category, Long templateId,
                                 VerificationConfig config, Map<String, Object> params,
                                 List<DraftView.DraftParam> specs, boolean auto, LocalDate start, LocalDate end) {
        Challenge c = Challenge.createFromDraft(ownerId, title, title, description, null, category,
                ParticipationType.GROUP, "PUBLIC", null, 30, Tier.BRONZE, start, end, 7, templateId,
                config, params, specs, ChallengePenalties.enforced(auto, true, false),
                TargetModerationStatus.EXEMPT, TargetModerationStatus.EXEMPT, TargetModerationStatus.NONE);
        c.activate();   // 시작일이 지났다 — 진행 중
        challengeRepository.saveAndFlush(c);
        statsProjection.createRow(c.getId());
        return c;
    }

    /** 방장(첫 사람이 아니라 지정한 더미) + 나머지를 ACTIVE 로 넣고, 가입 시각·가입 사건·참여 인원을 맞춘다. */
    private void addMembers(Challenge room, List<UUID> people) {
        List<ChallengeMember> members = new ArrayList<>();
        UUID ownerId = room.getCreatorId();
        members.add(ChallengeMember.owner(room.getId(), ownerId));
        for (UUID userId : people) {
            if (!userId.equals(ownerId)) members.add(ChallengeMember.join(room.getId(), userId, MemberStatus.ACTIVE));
        }
        for (ChallengeMember m : members) memberSetup.readyIfNothingToBind(m, room);
        memberRepository.saveAllAndFlush(members);

        Instant joined = joinedAt();
        jdbc.update("UPDATE challenge_members SET joined_at = ? WHERE challenge_id = ?", DbTime.utc(joined), bytes(room.getId()));
        for (ChallengeMember m : members) {
            jdbc.update("INSERT INTO challenge_join_events (id, challenge_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
                    bytes(UuidGenerator.generate()), bytes(room.getId()), bytes(m.getUserId()), DbTime.utc(joined));
        }
        jdbc.update("UPDATE challenges SET participant_count = ? WHERE id = ?", members.size(), bytes(room.getId()));
    }

    /** 방 시작 전날 오전 — 시작일부터 판정 대상이다. */
    Instant joinedAt() {
        return today().minusDays(HISTORY_DAYS + 1).atTime(LocalTime.of(9, 0)).atZone(KST).toInstant();
    }

    private record History(UUID lastSuccess, UUID lastFailure, UUID lastAppeal) {}

    /**
     * 걸음 자동 인증 이력 — 매일 행이 있고, 주 1회 실패(확정) · 2주 1회 이의 인용 · 나머지 자동 성공.
     * 실패와 이의가 같은 날에 오지 않는다((i+k)%7==3 과 (i+k)%14==6 은 겹치지 않는다).
     */
    private History autoHistory(ChallengeMember member, com.ruleup.ruleup_backend.verification.domain.VerificationConfig config,
                                LocalDate start, int k) {
        String method = VerificationMethod.HEALTH.name();
        String reason = FailureReasons.of(VerificationMethod.HEALTH, config);
        UUID lastSuccess = null, lastFailure = null, lastAppeal = null;
        for (int i = 0; i < HISTORY_DAYS - 1; i++) {
            LocalDate day = start.plusDays(i);
            VerificationDaily daily = VerificationDaily.open(member.getId(), member.getChallengeId(), member.getUserId(), day);
            VerificationMethodResult result = VerificationMethodResult.create(daily.getId(), method, Polarity.ACHIEVEMENT, true);
            Instant evening = day.atTime(LocalTime.of(21, 0).plusMinutes(k)).atZone(KST).toInstant();
            boolean fail = (i + k) % 7 == 3;
            boolean appeal = (i + k) % 14 == 6;

            if (fail || appeal) {
                Map<String, Object> evidence = stepsEvidence(4_000 + (i * 53 + k * 17) % 5_000);
                result.evaluate(VerificationStatus.FAILED, evidence, evening);
                daily.recordFailExpected(method, null);   // 목표 미달은 확정 전까지 사유가 비어 있다
                FailureEvidence explained = FailureEvidence.of(reason, evidence);
                if (fail) {
                    Instant confirmedAt = daily.getFinalizeAfter();
                    daily.confirmFailure(confirmedAt, method, reason);
                    daily.acknowledge(confirmedAt);
                    em.persist(daily);
                    em.persist(result);
                    em.persist(VerificationFailureDetail.of(daily.getId(), reason, explained, confirmedAt));
                    lastFailure = daily.getId();
                } else {
                    Instant acceptedAt = day.plusDays(1).atTime(LocalTime.of(10, 0)).atZone(KST).toInstant();
                    daily.correctByAppeal(acceptedAt);
                    daily.acknowledge(acceptedAt);
                    em.persist(daily);
                    em.persist(result);
                    Appeal accepted = Appeal.accept(daily.getId(), member.getChallengeId(), member.getId(), member.getUserId(),
                            day, APPEAL_REASON, null, new Appeal.FailureSnapshot(reason, explained.summary(),
                                    explained.expected(), explained.actual()), acceptedAt);
                    em.persist(accepted);
                    lastAppeal = accepted.getId();
                }
            } else {
                result.evaluate(VerificationStatus.SUCCESS, stepsEvidence(STEP_GOAL + (i * 37 + k * 101) % 4_000), evening);
                daily.recordResult(VerificationStatus.SUCCESS, method, null, evening);
                daily.acknowledge(evening);
                em.persist(daily);
                em.persist(result);
                lastSuccess = daily.getId();
            }
        }
        return new History(lastSuccess, lastFailure, lastAppeal);
    }

    /** 수동 인증 이력 — 첫 2주에만 체크를 빠뜨린 날이 있고(행 없음) 그 뒤로는 매일 체크했다. */
    private void manualHistory(ChallengeMember member, LocalDate start, int k) {
        String method = VerificationMethod.SELF_CHECK.name();
        for (int i = 0; i < HISTORY_DAYS - 1; i++) {
            if (i < 14 && (i + k) % 6 == 2) continue;   // 체크하지 않은 날
            LocalDate day = start.plusDays(i);
            Instant checkedAt = day.atTime(LocalTime.of(22, 0).plusMinutes(k)).atZone(KST).toInstant();
            VerificationDaily daily = VerificationDaily.open(member.getId(), member.getChallengeId(), member.getUserId(), day);
            VerificationMethodResult result = VerificationMethodResult.create(daily.getId(), method, null, true);
            result.evaluate(VerificationStatus.SUCCESS, new LinkedHashMap<>(Map.of("selfCheck", true)), checkedAt);
            daily.recordManual(method, checkedAt);
            daily.acknowledge(checkedAt);
            em.persist(daily);
            em.persist(result);
        }
    }

    private static Map<String, Object> stepsEvidence(int value) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("value", value);
        evidence.put("goal", STEP_GOAL);
        return evidence;
    }

    // =====================================================================
    // 커밋 뒤
    // =====================================================================

    /**
     * 커밋 뒤 마무리 — 참여 인원·통계를 원천에서 다시 세고 탐색 인덱스를 고친다. 데이터는 이미 커밋됐으므로 여기서의
     * 실패는 되돌리지 않고 보고만 한다. 둘 다 원천에서 다시 만드는 값이라 5분 통계 보정·탐색 대조가 같은 일을 다시 한다.
     *
     * <p>정산이 쌓은 아웃박스(티어 알림 등)는 이 프로세스가 흘리지 않는다 — 시드 태스크는 배경 작업을 돌지 않는 역할이고,
     * 상시 API 서비스의 디스패처가 평소처럼 처리한다. 그래서 티어 알림은 시드 직후 조금 늦게 알림함에 들어올 수 있다.
     *
     * @return 실패한 단계 수 — 0 이면 전부 끝났다
     */
    int afterCommit(List<UUID> rooms) {
        int failed = 0;
        for (UUID roomId : rooms) {
            try {
                if (count("SELECT COUNT(*) FROM challenges WHERE id = ?", bytes(roomId)) == 0) continue;
                statsProjection.refresh(roomId);
            } catch (RuntimeException e) {
                failed++;
                log.warn("review_seed_followup_failed step=STATS room={}", roomId, e);
            }
            try {
                // index() 는 Redis 실패를 삼킨다(가입 경로용) — 해냈는지를 돌려주는 쪽으로 불러야 실패가 종료 코드 2 로 드러난다
                if (!exploreIndexer.tryIndex(roomId)) {
                    failed++;
                    log.warn("review_seed_followup_failed step=EXPLORE_INDEX room={} — Redis 실패 또는 회로 열림", roomId);
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("review_seed_followup_failed step=EXPLORE_INDEX room={}", roomId, e);
            }
        }
        return failed;
    }

    // =====================================================================

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    static UUID uuid(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
