package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.score.service.ScoreService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.Gender;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 심사 계정 행을 <b>시드 스크립트처럼</b> 심는다 (심사 계정 테크 스펙 5-3 「심사 계정 행 구성」).
 *
 * <p>심사 계정은 가입 경로로 만들지 않는다 — 고정 UUID 가 {@code REVIEW_ACCOUNT_IDS} 와 묶여 있어야 하고,
 * 실제 소셜 계정이 없다. 그래서 테스트도 가입 API 대신 행을 직접 만든다. 값은 application-test.yaml 의
 * {@code app.review-accounts} · {@code app.review-login.codes} 와 짝이다.
 *
 * <ul>
 *   <li>{@code oauth_provider}=GOOGLE · {@code oauth_subject}=ruleup-review-{회차}-0N (더미는 ruleup-demo-fixture — 시드 더미 번호와 겹치지 않게). subject 는 바뀌지 않는
 *       값이라 계정의 <b>회차</b>를 여기서 읽는다 — 이번 회차는 {@link #ROUND}, JUDGE_8 만 지난 회차 {@link #EARLIER_ROUND}</li>
 *   <li>{@code installation_id} 시드 고정값 · <b>{@code device_id} NULL</b> · {@code device_info} NULL — sync 의 활성 기기
 *       대조를 건너뛰어 심사위원 기기의 신호가 판정에 쓰이고(코드 로그인은 기기를 받지 않는다), sync 주기는 폴백 행</li>
 *   <li>닉네임 APPROVED · 생일·성별 고정 · 약관 5종 현행 버전 동의 · 개별 동의 2종 없음 · social_tokens 없음</li>
 * </ul>
 *
 * <h4>공유 계정의 상태</h4>
 * 계정 행은 컨텍스트·스위트 전체가 함께 쓴다. 테스트가 바꾼 상태가 다음 테스트로 새면 실행 순서에 따라
 * 결과가 달라진다. 그래서
 * <ul>
 *   <li>{@link #resetToSeed} 가 테스트가 흔히 바꾸는 상태 — 기기 값·기기 정보·개별 동의 — 를 시드로 되돌린다.
 *       (코드 로그인은 기기 값을 쓰지 않지만, 자동 인증 IT 가 일부러 넣는 경우와 구현 실수를 시드에서 끊는다)
 *       코드 로그인 테스트는 매번 이것부터 부른다</li>
 *   <li>되돌리기 어려운 상태(탈퇴·정지·참여 멤버십)를 남기는 테스트는 <b>전용 계정</b>을 쓴다 — 아래 상수의 용도 표기</li>
 * </ul>
 */
public final class ReviewAccountFixture {

    /** 코드 로그인 정상 흐름(ReviewLoginIT). 매 테스트 {@link #resetToSeed} 로 시작한다. */
    public static final UUID JUDGE_1 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e01");
    /** 로그인 IT 에서 계정 간 독립성 확인에 쓰고 매번 되돌린다. */
    public static final UUID JUDGE_2 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e02");
    /** 탈퇴 후 코드로 복원. */
    public static final UUID JUDGE_3 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e03");
    /** 정지 — 계정 상태 게이트는 그대로 적용된다. 정지는 되돌리지 않는다. */
    public static final UUID JUDGE_4 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e04");
    /** 심사 기능 종료 — 재발급 차단·RT 회수·자동 탈퇴(방 나가기·봇 방장). 탈퇴는 {@link #reviveAll} 로 되살린다. */
    public static final UUID JUDGE_5 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e05");
    /** 자동 인증 왕복(ReviewAccountAutoVerificationIT) 전용 — 기기·동의·멤버십을 다른 테스트와 나누지 않는다. */
    public static final UUID JUDGE_6 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e06");
    /** 코드가 폐기된 심사 계정("revoked": true) — 로그인은 안 되고 기존 세션은 스위치와 무관하게 끊긴다. */
    public static final UUID JUDGE_7 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e07");
    /** <b>지난 회차({@link #EARLIER_ROUND}) 계정</b>을 실수로 새 코드(judge-8)에 묶은 경우 — 코드가 맞아도 로그인되지 않아야 한다. */
    public static final UUID JUDGE_8 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e08");
    /**
     * 다음 회차({@link #NEXT_ROUND})에 새로 만든 심사 계정 — 기본 설정에는 없고 ReviewNextRoundIT 의 설정에만 있다.
     * {@link #ensureAll()} 은 만들지 않는다. 시드처럼 새 UUID 로 {@link #ensure} 한다.
     */
    public static final UUID NEXT_ROUND_1 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e11");
    /** 데모 그룹방을 채우는 더미 멤버. 코드가 없어 종료 때 탈퇴되지 않는다. */
    public static final UUID DUMMY = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e51");
    /** Play 심사 계정. REVIEW_ACCOUNT_IDS 에 있지만 코드가 없어, 종료 때 세션이 끊기지 않고 탈퇴되지도 않는다. */
    public static final UUID PLAY_REVIEWER = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e61");

    /** codes 의 모든 항목(폐기 포함) — 종료 대상. */
    public static final List<UUID> JUDGES = List.of(JUDGE_1, JUDGE_2, JUDGE_3, JUDGE_4, JUDGE_5, JUDGE_6, JUDGE_7, JUDGE_8);

    public static final String CODE_1 = "K7QM-4XRD-9WTF";
    public static final String CODE_2 = "A2B3-C4D5-E6F7";
    public static final String CODE_3 = "Q8W9-E2R3-T4Y5";
    public static final String CODE_4 = "M5N6-P7Q8-R9S2";
    public static final String CODE_5 = "H3J4-K5M6-N7P8";
    public static final String CODE_6 = "W2X3-Y4Z5-A6B7";
    /** 폐기된 코드. */
    public static final String CODE_7 = "P3Q4-R5S6-T7U8";
    /** 지난 회차 계정(JUDGE_8)에 잘못 묶인 코드. */
    public static final String CODE_8 = "B4C5-D6E7-F8G9";
    /** 다음 회차 새 계정(NEXT_ROUND_1)의 코드 — ReviewNextRoundIT 설정. printf 'N2P3Q4R5S6T7' | shasum -a 256 */
    public static final String CODE_NEXT_1 = "N2P3-Q4R5-S6T7";
    public static final String CODE_NEXT_1_HASH = "5af346426c59cc1b04e34893e2c991b88e47fbf884bbe1cf325443cdaaa08eb6";
    /** CODE_5 의 해시 — 다음 회차 설정에 지난 회차 계정을 실수로 다시 묶을 때 쓴다. */
    public static final String CODE_5_HASH = "6d7b1e0e8c5682f71a9b6bb2e09510c3f16260114e1bd6fbb0a7caa44ac4eb62";

    /** 형식은 맞지만 목록에 없는 코드. */
    public static final String UNKNOWN_CODE = "ZZZZ-ZZZZ-ZZZZ";

    /** application-test.yaml 의 round — subject 의 회차가 이것과 다르면 지난 회차 계정이라 코드로 로그인되지 않는다. */
    public static final String ROUND = "2029120101";
    /** JUDGE_8 이 속한 지난 회차. */
    public static final String EARLIER_ROUND = "2029100101";
    /** ReviewNextRoundIT 설정의 round — 같은 만료(2030-01-01) 안에서 하루 뒤 시드한 다음 회차. */
    public static final String NEXT_ROUND = "2029120201";

    /** application-test.yaml 의 expires-at. */
    public static final Instant EXPIRES_AT = Instant.parse("2029-12-31T15:00:00Z");

    private final UserRepository userRepository;
    private final ScoreService scoreService;
    private final AgreementService agreementService;
    private final AppProperties props;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public ReviewAccountFixture(UserRepository userRepository, ScoreService scoreService,
                                AgreementService agreementService, AppProperties props,
                                TransactionTemplate tx, JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.scoreService = scoreService;
        this.agreementService = agreementService;
        this.props = props;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /** 심사 계정·더미 멤버·Play 심사 계정을 모두 심는다(있으면 그대로). */
    public void ensureAll() {
        for (UUID id : JUDGES) ensure(id);
        ensure(DUMMY);
        ensure(PLAY_REVIEWER);
    }

    /** 계정 행이 없으면 시드 규칙대로 만들고, 있으면 그대로 돌려준다. */
    public User ensure(UUID id) {
        return tx.execute(status -> userRepository.findById(id).orElseGet(() -> create(id)));
    }

    /**
     * 심사 기능 종료가 탈퇴시킨 심사 계정을 되살린다 — 다음 심사 전 시드 스크립트가 하는 일과 같다.
     *
     * <p>종료 스케줄러는 스위치가 꺼지면 codes 계정을 <b>모두</b> 탈퇴시킨다. 그 테스트 뒤에 도는 테스트(다른 클래스
     * 포함)가 탈퇴 상태의 계정을 받으면 실행 순서에 따라 결과가 갈리므로, 심사 계정을 쓰는 테스트는 매번 이것부터
     * 부른다. 복원은 운영 복원과 같은 {@link User#restore} 다 — 정지 상태로 탈퇴했으면 정지로 돌아간다.
     */
    public void reviveAll() {
        for (UUID id : JUDGES) revive(id);
    }

    /** 한 계정만 되살린다 — 시드 스크립트가 이번 회차 계정을 준비한 상태. 탈퇴 상태가 아니면 그대로 둔다. */
    public void revive(UUID id) {
        tx.executeWithoutResult(status -> {
            User user = userRepository.findById(id).orElseGet(() -> create(id));
            if (user.isWithdrawn()) {
                user.restore(seedOf(id).installationId(), null);
                userRepository.saveAndFlush(user);
            }
        });
    }

    /**
     * 테스트가 바꾸는 상태를 시드로 되돌린다 — 설치·기기 값, 기기 정보(sync 주기 산정 입력), 개별 동의 2종.
     * 계정 상태(탈퇴·정지)·참여 멤버십·세션은 건드리지 않는다. 그런 상태를 남기는 테스트는 전용 계정을 쓴다.
     */
    public void resetToSeed(UUID id) {
        ensure(id);
        Seed seed = seedOf(id);
        jdbc.update("UPDATE users SET installation_id = ?, device_id = NULL, platform = NULL, app_version_code = NULL, "
                        + "app_version_name = NULL, os_version = NULL, sdk_int = NULL, device_model = NULL, "
                        + "manufacturer = NULL, low_ram = NULL, ram_mb = NULL, device_info_updated_at = NULL WHERE id = ?",
                seed.installationId(), bytes(id));
        jdbc.update("DELETE FROM user_agreement_states WHERE user_id = ? AND agreement_type IN ('LOCATION_INFO','HEALTH_INFO')",
                (Object) bytes(id));
    }

    private User create(UUID id) {
        Seed seed = seedOf(id);
        User user = User.create(OAuthProvider.GOOGLE, seed.subject(), null, seed.nickname(), null,
                List.of("EXERCISE", "READING"));
        ReflectionTestUtils.setField(user, "id", id);   // 고정 UUID — env 목록과 묶인다
        user.approveNickname();                           // 심사 중 상태로 시작하지 않는다
        user.registerDemographics(LocalDate.of(1995, 3, 15), Gender.FEMALE);   // 만 14세 이상
        user.attachInstallation(seed.installationId(), null);   // device_id 는 NULL 로 둔다
        userRepository.saveAndFlush(user);
        scoreService.initialize(id);

        Instant now = Instant.now();
        for (AgreementType type : List.of(AgreementType.TOS, AgreementType.PRIVACY, AgreementType.LOCATION,
                AgreementType.MARKETING, AgreementType.EVENT)) {
            agreementService.record(user, type, true, props.client().termsVersions().of(type), now);
        }
        return user;
    }

    private record Seed(String subject, String nickname, String installationId) {}

    private static Seed seedOf(UUID id) {
        // 시드 태스크의 더미(ruleup-demo-NN)와 subject·설치 값이 겹치지 않게 — 같은 DB 에서 시드 테스트가 먼저 돌면
        // ruleup-demo-01 이 다른 UUID 로 이미 있다(uq_users_oauth_identity). 숫자 순번이 아니라 시드 번호 매김에도 안 낀다.
        if (id.equals(DUMMY)) return new Seed("ruleup-demo-fixture", "룰업더미01", "demo-inst-fixture");
        if (id.equals(PLAY_REVIEWER)) return new Seed("ruleup-play-01", "룰업플레이01", "play-inst-01");
        if (id.equals(NEXT_ROUND_1)) return new Seed("ruleup-review-" + NEXT_ROUND + "-01", "룰업심사B01", "review-inst-" + NEXT_ROUND + "-01");
        int n = JUDGES.indexOf(id) + 1;
        if (n == 0) throw new IllegalArgumentException("심사 계정 픽스처에 없는 id: " + id);
        String seq = "0" + n;
        String round = id.equals(JUDGE_8) ? EARLIER_ROUND : ROUND;
        return new Seed("ruleup-review-" + round + "-" + seq, "룰업심사" + seq, "review-inst-" + round + "-" + seq);
    }

    private static byte[] bytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
