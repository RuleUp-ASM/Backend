package com.ruleup.ruleup_backend.devtoken;

import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.sanction.SanctionService;
import com.ruleup.ruleup_backend.sanction.domain.FeatureCode;
import com.ruleup.ruleup_backend.sanction.domain.SanctionReason;
import com.ruleup.ruleup_backend.sanction.domain.SanctionSource;
import com.ruleup.ruleup_backend.sanction.domain.SanctionTrack;
import com.ruleup.ruleup_backend.sanction.domain.SanctionType;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.domain.TierBands;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 소셜 로그인을 거치지 않고 테스트용 토큰을 발급한다 — Postman · 통합테스트 · QA 용.
 *
 * <p><b>이건 인증 우회로다.</b> 놓치면 사고가 아니라 침해라서 방어를 프로필로 건다.
 * {@code @Profile("!prod")} 는 설정값 토글과 달리 <b>빈 등록 자체를 막는다</b> — 플래그 방식은
 * 오설정 한 줄로 prod 에서 켜진다.
 *
 * <p>발급되는 토큰은 운영 토큰과 <b>동일한 서명·클레임 구조</b>다. 따로 분기하면 "개발용에서만 되는"
 * 테스트가 되어 검증 가치가 사라진다.
 *
 * <p>만드는 계정은 닉네임이 {@code test_} 로 시작한다 — 실계정과 구분되지 않으면 나중에 지울 때
 * 골라낼 방법이 없다.
 */
@Service
@Profile("!prod")
@RequiredArgsConstructor
public class DevTokenService {

    private static final Logger log = LoggerFactory.getLogger(DevTokenService.class);

    private static final String TEST_PREFIX = "test_";
    /** 닉네임은 2~12자라 접두사 5자를 빼면 7자가 상한이다. */
    private static final int RANDOM_SUFFIX_LENGTH = 7;
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final UserRepository userRepository;
    private final UserScoreSummaryRepository scoreRepository;
    private final TokenService tokenService;
    private final SanctionService sanctionService;
    private final AgreementService agreementService;
    private final AppProperties props;

    @Transactional
    public DevTokenDtos.Response issue(DevTokenDtos.Request request) {
        DevTokenDtos.Request req = (request != null) ? request
                : new DevTokenDtos.Request(null, null, null, null, null, null, null);

        boolean created = req.userId() == null;
        User user = created ? createTestAccount(req) : loadExisting(req.userId());
        UserScoreSummary summary = scoreRepository.findById(user.getId())
                .orElseGet(() -> scoreRepository.save(UserScoreSummary.initialize(user.getId())));

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        // 누가 언제 어느 계정으로 받았는지 남긴다 — 우회로에는 흔적이 있어야 한다.
        log.warn("dev_token_issued userId={} created={} status={} tier={}",
                user.getId(), created, user.getStatus(), summary.getActualTier());

        return new DevTokenDtos.Response(pair.accessToken(), pair.refreshToken(), pair.expiresIn(),
                created,
                new DevTokenDtos.Response.User(user.getId().toString(), user.getNickname(),
                        user.getStatus().name(), summary.getActualTier().name(),
                        summary.getDisplayTier().name(), summary.getTotalScore()));
    }

    private User loadExisting(String userId) {
        UUID id;
        try {
            id = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private User createTestAccount(DevTokenDtos.Request req) {
        long score = validatedScore(req.score());
        Tier tier = validatedTier(req.tier());
        UserStatus status = validatedStatus(req.status());

        User user = User.create(OAuthProvider.KAKAO, "dev-" + UUID.randomUUID(), null,
                uniqueNickname(req.nickname()), null, new ArrayList<>());
        // 모더레이션을 건너뛰고 승인 상태로 둔다 — 테스트가 심사 대기 때문에 갈리면 안 된다.
        user.approveNickname();
        userRepository.save(user);

        scoreRepository.save(injectScore(user.getId(), score, tier));
        if (!Boolean.FALSE.equals(req.agreements())) agreeAll(user);
        if (status == UserStatus.SUSPENDED) imposeSanction(user, req.sanction());
        return user;
    }

    /**
     * 점수·티어 주입. 이 둘이 없으면 최소 입장 티어 분기를 테스트하려고 DB 를 손으로 고쳐야 한다.
     *
     * <p>실제 티어는 점수가 정한다 — 그게 정책이다. {@code tier} 는 <b>표시 티어만</b> 덮어써
     * 강등 유예 상태(실제는 실버인데 표시는 골드)를 재현하는 용도다. 정상 경로로는 사이클을 여러 번
     * 돌려야 나오는 상태라 주입이 없으면 사실상 테스트할 수 없다.
     */
    private UserScoreSummary injectScore(UUID userId, long score, Tier displayTierOverride) {
        UserScoreSummary summary = UserScoreSummary.initialize(userId);
        summary.applyScore(score);
        if (displayTierOverride != null) summary.forceDisplayTierForTest(displayTierOverride);
        return summary;
    }

    private void agreeAll(User user) {
        Instant now = Instant.now();
        for (AgreementType type : AgreementType.values())
            agreementService.record(user, type, true, props.client().termsVersions().of(type), now);
    }

    /**
     * 제재 게이트 재현. {@code endsAtAfterDays} 가 없으면 {@code endsAt} 이 null 이 되고,
     * 그것이 곧 영구 제재다.
     */
    private void imposeSanction(User user, DevTokenDtos.Request.Sanction sanction) {
        if (sanction == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        SanctionType type;
        try {
            type = SanctionType.valueOf(sanction.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        FeatureCode featureCode = null;
        if (sanction.featureCode() != null) {
            try {
                featureCode = FeatureCode.valueOf(sanction.featureCode());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
        Instant endsAt = (sanction.endsAtAfterDays() == null) ? null
                : Instant.now().plus(sanction.endsAtAfterDays(), ChronoUnit.DAYS);
        sanctionService.impose(user.getId(), SanctionTrack.DISCRETIONARY, type, featureCode,
                SanctionReason.SYSTEM_ABUSE, "개발용 토큰 발급 시 주입", SanctionSource.DIRECT,
                null, null, endsAt);
    }

    // ===== 입력 검증 =====

    private long validatedScore(Integer score) {
        if (score == null) return UserScoreSummary.INITIAL_SCORE;
        if (score < 0 || score > TierBands.MAX_SCORE) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return score;
    }

    /** 미지정이면 null — 점수가 정한 표시 티어를 그대로 둔다. */
    private Tier validatedTier(String tier) {
        if (tier == null) return null;
        try {
            return Tier.valueOf(tier);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private UserStatus validatedStatus(String status) {
        if (status == null) return UserStatus.ACTIVE;
        try {
            return UserStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * 겹치면 서픽스를 붙여 피한다 — 테스트가 닉네임 중복 때문에 실패하면 안 된다.
     * 닉네임 규칙(2~12자)을 넘지 않도록 앞을 잘라 자리를 만든다.
     */
    private String uniqueNickname(String requested) {
        String base = (requested == null || requested.isBlank()) ? TEST_PREFIX + random(RANDOM_SUFFIX_LENGTH)
                                                                 : requested.trim();
        if (!userRepository.isNicknameTaken(base, null)) return base;
        for (int i = 0; i < 20; i++) {
            String suffix = random(2);
            String head = base.length() > 10 ? base.substring(0, 10) : base;
            String candidate = head + suffix;
            if (!userRepository.isNicknameTaken(candidate, null)) return candidate;
        }
        return TEST_PREFIX + random(RANDOM_SUFFIX_LENGTH);
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            sb.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
