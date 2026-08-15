package com.ruleup.ruleup_backend.auth;
import com.ruleup.ruleup_backend.user.domain.*;
import com.ruleup.ruleup_backend.auth.domain.*;

import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreement;
import com.ruleup.ruleup_backend.agreement.UserAgreementRepository;
import com.ruleup.ruleup_backend.auth.dto.*;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.web.CountryResolver;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.moderation.ModerationRequestRepository;
import com.ruleup.ruleup_backend.moderation.UserModerationRequested;
import com.ruleup.ruleup_backend.moderation.domain.ModerationRequest;
import com.ruleup.ruleup_backend.moderation.domain.ModerationTarget;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.oauth.OAuthClient;
import com.ruleup.ruleup_backend.oauth.OAuthClientResolver;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.security.JwtProvider;
import com.ruleup.ruleup_backend.security.TokenType;
import com.ruleup.ruleup_backend.user.*;
import com.ruleup.ruleup_backend.verification.service.FlushIntervalPolicy;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 임시 승인 닉네임 INSERT 충돌 시 가입 트랜잭션 재시도 상한 (DB 정리 §6). 같은 패키지 테스트가 참조. */
    static final int MAX_SIGNUP_ATTEMPTS = 3;
    /** 재시도 대상 제약 — 임시 승인 닉네임 UNIQUE. 신청 닉네임 충돌(409)은 재시도 대상이 아니다. */
    private static final String APPROVED_NICKNAME_CONSTRAINT = "uq_users_active_approved_nickname";
    /** 동시 가입 경합 — 이 제약이 터졌다면 다른 요청이 먼저 같은 소셜 계정을 만든 것이다. */
    private static final String OAUTH_IDENTITY_CONSTRAINT = "uq_users_oauth_identity";
    /** 만 14세 미만 가입 불가 — 법적 요구사항(가드레일: 통과 0건). */
    private static final int MIN_AGE_YEARS = 14;

    private final OAuthClientResolver resolver;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final ModerationRequestRepository moderationRequestRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final TokenService tokenService;
    private final JwtProvider jwtProvider;
    private final SignupTokenStore signupTokenStore;
    private final TempNicknameAllocator tempNicknameAllocator;
    private final NicknameReleaseLockService releaseLockService;
    private final TransactionTemplate transactionTemplate;
    private final LoginSessionService loginSessionService;
    private final SocialTokenService socialTokenService;
    private final AppProperties props;
    private final ApplicationEventPublisher eventPublisher;
    private final CountryResolver countryResolver;
    private final com.ruleup.ruleup_backend.reputation.MilestoneService milestoneService;
    private final com.ruleup.ruleup_backend.invitation.InvitationService invitationService;
    private final com.ruleup.ruleup_backend.notification.NotificationService notificationService;

    // ===== OAuth 로그인 =====
    // ⚠️ 일부러 @Transactional 을 붙이지 않는다.
    // fetchUserInfo()는 카카오/구글 서버로 나가는 외부 HTTP 호출이라 수 초가 걸릴 수 있다.
    // 그걸 트랜잭션 안에 넣으면 그 시간 내내 DB 커넥션을 붙잡고 있게 된다(= 커넥션 고갈 위험).
    // 그래서 "느린 외부 호출"은 트랜잭션 밖에서 하고,
    // "빠른 DB 쓰기"만 TokenService.issueTokenPair()의 짧은 트랜잭션으로 처리한다.
    public OAuthLoginResponse oauthLogin(OAuthProvider provider, OAuthLoginRequest req) {
        // deviceId·deviceInfo는 로그인·가입 양쪽 필수(계약). 외부 호출 전에 빠르게 거부.
        requireValidDevice(req.deviceId(), req.deviceInfo());
        requireValidOAuthRequest(provider, req);

        OAuthClient client = resolver.resolve(provider);

        // 1) 외부 IdP 호출 (트랜잭션 밖)
        OAuthUserInfo info = client.fetchUserInfo(req.code(), req.codeVerifier(), req.redirectUri());

        // 2) DB 분기 — 활성 회원이면 토큰 발급, 그 외(신규·탈퇴)는 signupToken 만.
        //    탈퇴 계정을 여기서 되살리지 않는 이유: 복귀도 "회원가입을 거쳐 로그인"하는 흐름이기 때문이다.
        //    복원 판단은 가입 요청 시점에 한다(회원 정책 §6).
        User active = userRepository.findByOauthProviderAndOauthSubject(provider, info.subject())
                .filter(u -> !u.isWithdrawn())
                .orElse(null);
        if (active != null) {
            return loginSessionService.loginExisting(active.getId(), provider, req, info);
        }
        return issueSignupToken(provider, req, info);
    }

    private OAuthLoginResponse issueSignupToken(OAuthProvider provider, OAuthLoginRequest req, OAuthUserInfo info) {
        // 이 설치의 주인이 다른 소셜 계정이면(활성·탈퇴 무관) 여기서 막는다 — 온보딩을 다 채운 뒤
        // 거절당하지 않도록 가장 이른 지점에서 끊는다. 본인이 돌아오는 경우는 통과시킨다.
        requireInstallationAvailable(req.installationId(), provider, info.subject());
        // DB를 건드리지 않고 JWT(signupToken)만 만들어 돌려준다 → 트랜잭션 불필요
        String token = jwtProvider.issueSignupToken(info.subject(), provider.name(), info.email());
        // 가입 완료 시 social_tokens 로 옮길 IdP 토큰을 jti 기준으로 보류해 둔다
        socialTokenService.hold(jwtProvider.parse(token).getId(), info.idpTokens());

        // 돌아온 사람인지 미리 알려준다 — 클라는 true 면 온보딩 입력 화면을 건너뛰고 바로 가입을 요청한다.
        // (이전 정보가 있으면 입력받을 게 없다. 실제 판정은 가입 요청에서 서버가 다시 한다)
        boolean returning = userRepository
                .findByOauthProviderAndOauthSubject(provider, info.subject()).isPresent();
        return OAuthLoginResponse.newUser(token, props.jwt().signupTokenTtl(), info, returning);
    }

    // ===== iOS 카카오 로그인용 서버 콜백 브리지 =====
    // 카카오 REST API는 redirect_uri를 https만 허용한다. iOS 앱은 그 https redirect를
    // (Universal Links 없이는) 직접 잡을 수 없으므로, 서버가 https 콜백을 대신 받아
    // 인가코드를 앱 커스텀 스킴 딥링크로 넘겨준다. 앱은 그 code로 기존
    // POST /login/{provider} 를 호출해 토큰을 받는다(토큰 교환 로직은 그대로 재사용).
    public URI buildAppCallbackRedirect(String provider, String code, String state, String error) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromUriString(props.oauth().appRedirectUri())
                .queryParam("provider", provider);
        if (error != null && !error.isBlank()) {
            b.queryParam("error", error);          // 사용자가 동의 취소/실패한 경우
        } else {
            b.queryParam("code", code);
        }
        if (state != null && !state.isBlank()) {
            b.queryParam("state", state);          // 앱이 CSRF 검증에 사용
        }
        return b.encode().build().toUri();
    }

    // ===== 가입 =====
    // 여러 테이블(users, user_information, user_interests, user_agreements,
    // moderation_requests, user_score_summaries, refresh_tokens)을 함께 쓰므로
    // 하나라도 실패하면 전부 롤백되어야 한다 → 트랜잭션 필수.
    // (외부 호출 없음: signupToken 파싱은 우리 서버 안에서 끝남)
    //
    // @Transactional 대신 TransactionTemplate 을 쓰는 이유: 임시 승인 닉네임이 INSERT 시점에
    // UNIQUE 충돌하면(사전 검사와 INSERT 사이의 경합) 같은 트랜잭션에서 복구할 수 없어
    // — 제약 위반이 나면 영속성 컨텍스트가 깨진다 — 트랜잭션을 새로 열어 재시도해야 한다.
    public SignupResponse signup(SignupRequest req) {
        // 토큰 소모는 요청당 한 번만. 재시도로 두 번 소모되면 스스로 INVALID_SIGNUP_TOKEN 이 된다.
        boolean[] tokenConsumed = {false};

        for (int attempt = 1; ; attempt++) {
            try {
                final int current = attempt;
                return requireNotBanned(transactionTemplate.execute(status -> register(req, tokenConsumed, current)));
            } catch (DataIntegrityViolationException e) {
                // 동시 가입 경합 — 사전 조회 후 다른 요청이 먼저 가입했다.
                // 계약: "후발 요청은 기존 유저 로그인으로 수렴"(테크 스펙 4-3).
                if (isConstraintViolation(e, OAUTH_IDENTITY_CONSTRAINT)) {
                    log.info("동시 가입 경합 감지 — 기존 계정 로그인으로 수렴합니다.");
                    return convergeToExistingLogin(req.signupToken());
                }
                // 신청 닉네임 충돌 등 사용자에게 알려야 할 위반은 그대로 올려보낸다(409).
                if (!isApprovedNicknameConflict(e)) throw e;
                if (attempt >= MAX_SIGNUP_ATTEMPTS) {
                    // 사용자 입력 문제가 아니라 서버가 빈 임시 닉네임을 못 찾은 것 →
                    // "닉네임 중복(409)"으로 위장하면 원인을 오해하게 된다.
                    log.error("임시 승인 닉네임 충돌이 {}회 연속 발생해 가입을 포기했습니다.",
                            MAX_SIGNUP_ATTEMPTS, e);
                    throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
                }
                log.warn("임시 승인 닉네임 INSERT 충돌 — 가입 재시도 {}/{}", attempt, MAX_SIGNUP_ATTEMPTS);
            }
        }
    }

    /**
     * 정지 계정이면 토큰을 주지 않는다 — <b>가입(복원)은 커밋된 뒤</b> 응답만 403 으로 돌린다.
     * "회원가입까지는 되고 로그인이 막힌다"는 정책이라, 트랜잭션 안에서 던져 복원을 롤백시키면 안 된다.
     */
    private SignupResponse requireNotBanned(SignupResponse res) {
        if (res != null && res.user() != null
                && UserStatus.BANNED.name().equals(res.user().accountStatus()))
            throw new BusinessException(ErrorCode.ACCOUNT_BANNED);
        return res;
    }

    /** 가입 본체(트랜잭션 안). 임시 닉네임 충돌로 재시도될 수 있으므로 부수효과는 전부 트랜잭션 안에 둔다. */
    private SignupResponse register(SignupRequest req, boolean[] tokenConsumed, int attempt) {
        Claims claims = parseSignupToken(req.signupToken());
        OAuthProvider provider = OAuthProvider.valueOf((String) claims.get("provider"));
        String oauthSubject = claims.getSubject();
        String email = (String) claims.get("email");

        // signupToken 1회용 — 이미 소모된 jti의 재제출은 즉시 거부(계약: 처음부터 다시).
        // 단, 이 요청이 방금 소모한 경우(닉네임 충돌 재시도)는 같은 요청의 연장이므로 통과시킨다.
        if (!tokenConsumed[0] && signupTokenStore.isUsed(claims.getId()))
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);

        // (provider, subject) 로 이전 계정을 먼저 본다.
        //  · 살아 있는 계정 → 동시 가입 경합. 후발 요청은 기존 유저 로그인으로 수렴(테크 스펙 4-3)
        //  · 탈퇴한 계정 → <b>복귀</b>. 입력값을 받지 않고 이전 정보를 그대로 살려 로그인시킨다(회원 정책 §6)
        User existing = userRepository.findByOauthProviderAndOauthSubject(provider, oauthSubject).orElse(null);
        if (existing != null && !existing.isWithdrawn()) return loginResponseFor(existing);
        if (existing != null) return restoreAndLogin(existing, req, claims, tokenConsumed);

        // 이 설치의 주인이 다른 소셜 계정이면 신규 가입을 막는다(로그인 단계에서 이미 걸렀지만 서버 단독으로도 성립해야 한다).
        // 소셜만 바꿔 점수·제재를 리셋하는 우회로를 닫는 것이다 — 돌아오려면 원래 소셜 계정으로 와야 한다.
        requireInstallationAvailable(req.installationId(), provider, oauthSubject);

        // 닉네임 형식 → 중복(신청 PENDING·승인 닉네임 모두 점유로 본다)
        if (!NicknamePolicy.isValid(req.nickname()))
            throw new BusinessException(ErrorCode.NICKNAME_FORMAT_INVALID);
        if (userRepository.isNicknameTaken(req.nickname(), null))
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        // 남이 변경으로 버린 닉네임의 1주일 잠금 — 신규 가입자는 절대 그 "본인"일 수 없다(회원 정책 §3)
        releaseLockService.requireNotLocked(req.nickname(), null);

        // 관심 카테고리: 코드 유효성(12종) → 중복 제거 → 개수(0~6, 건너뛰기 허용).
        // 중복은 user_interests PK(user_id, category) 위반이라 그대로 두면 500 이 된다. 클라 버그로
        // 가입을 막지 않도록 서버가 정규화한다(온보딩 완주율 우선) — 고유값 기준으로 상한을 센다.
        List<String> requested = (req.interestCategories() != null) ? req.interestCategories() : List.of();
        if (!InterestCategory.allValid(requested))
            throw new BusinessException(ErrorCode.CATEGORY_INVALID);
        List<String> categories = requested.stream().distinct().toList();
        if (!InterestCategory.isCountValid(categories))
            throw new BusinessException(ErrorCode.INTEREST_LIMIT_EXCEEDED);

        // 생일: 필수·형식·미래 불가 → 만 14세 미만 차단(서버 재검증 — 가드레일 0건)
        LocalDate birthDate = parseBirthDate(req.birthDate());
        // 성별: 필수 필드 — MALE/FEMALE/NON_BINARY (UI 건너뛰기 시 클라가 NON_BINARY 전송)
        Gender gender = parseGender(req.gender());

        // 약관 6종: 필수 3(이용약관·개인정보·위치기반) 모두 동의해야 가입 가능
        SignupRequest.Agreements ag = req.agreements();
        if (ag == null || !ag.requiredAllAgreed())
            throw new BusinessException(ErrorCode.REQUIRED_AGREEMENT_MISSING);

        // 기기: deviceId·deviceInfo 필수(계약) / installationId는 다계정 차단 판정 키
        requireValidDevice(req.deviceId(), req.deviceInfo());
        if (req.installationId() == null || req.installationId().isBlank())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        // signupToken 1회용 — 검증을 모두 통과한 시점에 jti를 소모한다.
        // 재시도(2회차 이상)는 이미 이 요청이 소모한 것이므로 다시 소모하지 않는다.
        if (!tokenConsumed[0]) {
            if (!signupTokenStore.consume(claims.getId()))
                throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
            tokenConsumed[0] = true;
        }

        User user = User.create(provider, oauthSubject, email, req.nickname(), null,
                new ArrayList<>(categories));
        // 임시 승인 닉네임(8자)이 이미 점유돼 있으면 다른 값으로 재시도 (DB 정리 §6)
        tempNicknameAllocator.assign(user, candidate -> userRepository.isNicknameTaken(candidate, user.getId()));
        if (attempt > 1) tempNicknameAllocator.reassign(user);   // 직전 시도가 INSERT 충돌 → 새 후보로
        user.registerDemographics(birthDate, gender);
        applyDeviceInfo(user, req.deviceInfo());              // 가입 시 기기 정보 최초 저장
        user.attachInstallation(req.installationId(), req.deviceId());
        user.updateCountryCode(countryResolver.resolve(deviceCountry(req.deviceInfo())));   // 지오 헤더 → 기기 지역 → Accept-Language
        userRepository.save(user);
        // 잠금이 만료된 뒤 이 닉네임을 가져간 경우 — 죽은 잠금 행을 남겨두지 않는다
        releaseLockService.clear(req.nickname());

        reputationScoreRepository.save(ReputationScore.createDefault(user));   // 매너온도 병존(전환 전)
        UserScoreSummary summary = scoreSummaryRepository.save(UserScoreSummary.initialize(user.getId()));   // 브론즈 10점
        milestoneService.recordSignup(user.getId(), LocalDate.now(KST));
        invitationService.recordSignup(req.inviteCode(), user.getId(), java.time.Instant.now());   // 친구 초대 기록(선택)
        saveAgreements(user, ag);
        moderationRequestRepository.save(
                ModerationRequest.request(user.getId(), ModerationTarget.NICKNAME, req.nickname()));
        socialTokenService.flushPending(claims.getId(), user.getId(), provider);   // IdP 토큰 암호화 저장

        // 가입은 여기서 그대로 완료(닉네임 상태는 PENDING — 심사 중 기능 제한 없음).
        // 커밋 후 비동기로 LLM 검수 → 문제면 타인에게 임시 닉네임 + 알림.
        eventPublisher.publishEvent(new UserModerationRequested(user.getId()));

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        int flushIntervalSec = FlushIntervalPolicy.forUser(user);   // deviceInfo 확정 저장 시점 → 주기 부트스트랩
        return SignupResponse.from(pair, user, summary, flushIntervalSec);
    }

    /**
     * 이 설치에서 가입을 진행해도 되는지 — 하나의 설치는 하나의 소셜 계정에만 묶인다(회원 정책 §1).
     *
     * <p>점유자가 <b>본인</b>(같은 provider + subject)이면 통과시킨다. 탈퇴했다가 돌아오는 경로이기 때문이다.
     * 다른 계정이면 활성·탈퇴 가리지 않고 막는다 — 소셜만 바꿔 점수·제재를 리셋하는 우회로이기 때문이다.
     * 앱을 지웠다 깔면 installationId 가 바뀌므로 기기 재사용 자체가 막히지는 않는다.
     *
     * <p>거절할 때 어느 소셜로 가야 하는지 알려준다 — {@code error.reason} 에 그 계정의 provider 를 싣는다.
     * 이게 없으면 사용자는 "가입이 안 된다"만 알고 무엇을 해야 할지 모른다.
     */
    private void requireInstallationAvailable(String installationId, OAuthProvider provider, String subject) {
        if (installationId == null || installationId.isBlank()) return;
        User holder = userRepository.findActiveHolderOfInstallation(installationId)
                .or(() -> userRepository.findWithdrawnHolderOfInstallation(installationId))
                .orElse(null);
        if (holder == null || holder.hasIdentity(provider, subject)) return;
        throw new BusinessException(ErrorCode.INSTALLATION_ALREADY_REGISTERED,
                holder.getOauthProvider().name());
    }

    /**
     * 탈퇴 계정의 복귀 — 가입 요청으로 들어오지만 실제로는 <b>복원 + 로그인</b>이다.
     *
     * <p>이전 정보(닉네임·관심사·생일·성별·점수·매너온도)가 그대로 살아 있으므로 요청 바디의
     * 온보딩 입력은 보지 않는다. 클라이언트도 로그인 응답의 {@code returningUser=true} 를 보고
     * 입력 화면을 띄우지 않는다.
     *
     * <p>정지 상태로 탈퇴했다면 여기서 막지 않는다 — 복원까지 마치고 커밋한 뒤
     * {@link #signup} 이 응답만 403 으로 돌린다("가입은 되고 로그인이 막힌다").
     */
    private SignupResponse restoreAndLogin(User user, SignupRequest req, Claims claims, boolean[] tokenConsumed) {
        requireValidDevice(req.deviceId(), req.deviceInfo());
        if (req.installationId() == null || req.installationId().isBlank())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        if (!tokenConsumed[0]) {
            if (!signupTokenStore.consume(claims.getId()))
                throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
            tokenConsumed[0] = true;
        }

        // 쓰던 닉네임을 그사이 타인이 가져갔으면 임시 닉네임으로 들여보내고 변경을 요청한다.
        // 여기서 가입을 막으면 "닉네임 때문에 못 돌아오는" 상태가 된다.
        boolean nicknameTaken = userRepository.isNicknameTaken(user.getApprovedNickname(), user.getId())
                || (user.isNicknamePending() && userRepository.isNicknameTaken(user.getNickname(), user.getId()));
        if (nicknameTaken) {
            tempNicknameAllocator.assign(user, candidate -> userRepository.isNicknameTaken(candidate, user.getId()));
            user.markNicknameConflict();
            notificationService.notify(user.getId(), NotificationType.SYSTEM,
                    "닉네임을 변경해주세요",
                    "쓰시던 닉네임을 다른 분이 사용 중이라 임시 닉네임으로 시작했어요. 프로필에서 새 닉네임을 정해주세요.");
        }

        user.restore(req.installationId(), req.deviceId());   // 탈퇴 직전 상태(정지·잠금 포함)로 되돌린다
        applyDeviceInfo(user, req.deviceInfo());
        user.updateCountryCode(countryResolver.resolve(deviceCountry(req.deviceInfo())));
        socialTokenService.flushPending(claims.getId(), user.getId(), user.getOauthProvider());

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        UserScoreSummary summary = scoreSummaryRepository.findById(user.getId()).orElse(null);
        return SignupResponse.restored(pair, user, summary, FlushIntervalPolicy.forUser(user));
    }

    /** 임시 승인 닉네임 UNIQUE 위반인지 — 이 경우에만 새 후보로 가입을 재시도한다. */
    private boolean isApprovedNicknameConflict(DataIntegrityViolationException e) {
        return isConstraintViolation(e, APPROVED_NICKNAME_CONSTRAINT);
    }

    private boolean isConstraintViolation(DataIntegrityViolationException e, String constraint) {
        Throwable root = e.getMostSpecificCause();
        String message = (root != null) ? root.getMessage() : null;
        return message != null && message.contains(constraint);
    }

    /**
     * 이미 만들어진 같은 소셜 계정으로 로그인 응답을 만든다(동시 가입 수렴).
     * 가입 트랜잭션은 롤백된 뒤이므로 여기서 다시 조회한다.
     */
    private SignupResponse convergeToExistingLogin(String signupToken) {
        Claims claims = parseSignupToken(signupToken);
        OAuthProvider provider = OAuthProvider.valueOf((String) claims.get("provider"));
        User existing = userRepository.findByOauthProviderAndOauthSubject(provider, claims.getSubject())
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
        return loginResponseFor(existing);
    }

    /** 기존 계정에 토큰을 발급해 가입 응답 형태(isNewUser=false)로 감싼다. */
    private SignupResponse loginResponseFor(User existing) {
        if (existing.isBanned()) throw new BusinessException(ErrorCode.ACCOUNT_BANNED);
        TokenService.TokenPair pair = tokenService.issueTokenPair(existing);
        UserScoreSummary summary = scoreSummaryRepository.findById(existing.getId()).orElse(null);
        return new SignupResponse(false, false, pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresIn(), FlushIntervalPolicy.forUser(existing),
                UserResponse.from(existing, summary));
    }

    /** 생일 파싱·검증. 누락/형식/미래 = BIRTHDATE_INVALID, 만 14세 미만 = BIRTHDATE_UNDERAGE. */
    private LocalDate parseBirthDate(String raw) {
        if (raw == null || raw.isBlank())
            throw new BusinessException(ErrorCode.BIRTHDATE_INVALID);
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BIRTHDATE_INVALID);
        }
        LocalDate today = LocalDate.now(KST);
        if (birthDate.isAfter(today))
            throw new BusinessException(ErrorCode.BIRTHDATE_INVALID);
        if (birthDate.isAfter(today.minusYears(MIN_AGE_YEARS)))
            throw new BusinessException(ErrorCode.BIRTHDATE_UNDERAGE);
        return birthDate;
    }

    /**
     * 성별 파싱 — 저장 필수 필드. 허용값 4종(MALE/FEMALE/NON_BINARY/PREFER_NOT_TO_SAY)을 모두 받는다.
     * "미응답" 표현은 API 계약(NON_BINARY)과 DB 정리 문서(PREFER_NOT_TO_SAY)가 아직 상충 중이라
     * 합의 전까지 둘 다 수용한다. 누락/허용 외 값은 GENDER_REQUIRED.
     */
    private Gender parseGender(String raw) {
        if (raw == null || raw.isBlank())
            throw new BusinessException(ErrorCode.GENDER_REQUIRED);
        try {
            return Gender.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.GENDER_REQUIRED);
        }
    }

    /**
     * OAuth 요청 자체의 형식 검증 (IdP 호출 전 조기 거부 — 테크 스펙 4-4).
     *  · code·codeVerifier(PKCE) 누락 → 400 LOGIN_FAILED (검증 자체가 불가)
     *  · 구글은 redirectUri 검증 필수 — 등록값과 다르면 400 INVALID_REDIRECT_URI.
     *    카카오는 카카오톡 간편 로그인 경로에서 SDK가 내부 처리해 null 이 올 수 있어 검증하지 않는다.
     */
    private void requireValidOAuthRequest(OAuthProvider provider, OAuthLoginRequest req) {
        if (req.code() == null || req.code().isBlank()
                || req.codeVerifier() == null || req.codeVerifier().isBlank())
            throw new BusinessException(ErrorCode.LOGIN_FAILED);

        if (provider == OAuthProvider.GOOGLE) {
            String registered = props.oauth().google().redirectUri();
            if (registered != null && !registered.isBlank() && !registered.equals(req.redirectUri()))
                throw new BusinessException(ErrorCode.INVALID_REDIRECT_URI);
        }
    }

    /** deviceId·deviceInfo는 로그인·가입 양쪽 필수. 누락/형식오류면 INVALID_DEVICE_INFO. */
    private void requireValidDevice(String deviceId, DeviceInfoRequest device) {
        if (deviceId == null || deviceId.isBlank())
            throw new BusinessException(ErrorCode.INVALID_DEVICE_INFO);
        if (device == null || !device.isValid())
            throw new BusinessException(ErrorCode.INVALID_DEVICE_INFO);
    }

    /** 기기 정보를 유저에 반영(최신 1건 갱신). 호출 전 requireValidDevice 로 검증됨. */
    private void applyDeviceInfo(User user, DeviceInfoRequest device) {
        if (device == null) return;
        user.updateDeviceInfo(device.toPlatform(), device.versionCode(), device.versionName(),
                device.osVersion(), device.sdkInt(), device.deviceModel(),
                device.manufacturer(), device.lowRam());
    }

    /** deviceInfo 의 기기 지역(국가 코드 폴백 소스). null 안전. */
    private String deviceCountry(DeviceInfoRequest device) {
        return (device != null) ? device.country() : null;
    }

    /** 약관 6종 append-only 저장 — 미동의(false)·항목 누락(선택 약관)도 false 행으로 남긴다. */
    private void saveAgreements(User user, SignupRequest.Agreements ag) {
        saveAgreement(user, AgreementType.TOS, ag.termsOfService());
        saveAgreement(user, AgreementType.PRIVACY, ag.privacyPolicy());
        saveAgreement(user, AgreementType.LOCATION, ag.locationService());
        saveAgreement(user, AgreementType.MARKETING, ag.marketing());
        saveAgreement(user, AgreementType.EVENT, ag.event());
        saveAgreement(user, AgreementType.NIGHT_PUSH, ag.nightPush());
    }

    private void saveAgreement(User user, AgreementType type, SignupRequest.AgreementItem item) {
        boolean agreed = item != null && item.isAgreed();
        String version = (item != null && item.version() != null && !item.version().isBlank())
                ? item.version() : currentVersionOf(type);   // 미전송 시 서버가 아는 현행 버전으로
        userAgreementRepository.save(UserAgreement.of(user, type, agreed, version));
    }

    /** 인트로가 내려주는 현행 약관 버전과 같은 값 — 클라가 version 을 생략해도 기록이 어긋나지 않게. */
    private String currentVersionOf(AgreementType type) {
        AppProperties.Client.TermsVersions v = props.client().termsVersions();
        return switch (type) {
            case TOS -> v.termsOfService();
            case PRIVACY -> v.privacyPolicy();
            case LOCATION -> v.locationService();
            case MARKETING -> v.marketing();
            case EVENT -> v.event();
            case NIGHT_PUSH -> v.nightPush();
        };
    }

    // ===== 토큰 재발급 (회전) =====
    @Transactional
    public TokenResponse refresh(String refreshTokenValue) {
        Claims claims = parseRefreshToken(refreshTokenValue);
        UUID userId = UUID.fromString(claims.getSubject());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenService.sha256(refreshTokenValue))
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_EXPIRED));

        // 재사용 감지 — 이미 폐기된 토큰이 다시 제출되면 탈취 의심 → family 전체 revoke (DB 정리 §11.3)
        // 401 예외로 이 트랜잭션이 롤백돼도 흔적·revoke 는 남아야 하므로 REQUIRES_NEW 로 커밋한다.
        if (stored.isRevoked()) {
            tokenService.recordReuseAndRevokeFamily(stored.getId(), stored.getFamilyId());
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        }
        if (stored.isExpired()) throw new BusinessException(ErrorCode.SESSION_EXPIRED);

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_EXPIRED));

        stored.revoke();                                   // 기존 무효화(회전)
        return TokenResponse.from(tokenService.issueRotatedPair(user, stored.getFamilyId(), stored.getId()));
    }

    // ===== 로그아웃 (멱등) =====
    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByTokenHash(TokenService.sha256(refreshTokenValue))
                .ifPresent(RefreshToken::revoke);          // 없거나 이미 무효여도 성공
    }

    // ===== 닉네임 형식/중복 검사 (스펙 4.6) =====
    @Transactional(readOnly = true)
    public NicknameAvailabilityResponse checkNickname(String nickname) {
        if (!NicknamePolicy.isValid(nickname))
            return NicknameAvailabilityResponse.formatFail();        // valid:false, reason:FORMAT
        if (userRepository.isNicknameTaken(nickname, null))
            return NicknameAvailabilityResponse.duplicated();        // valid:true, available:false, reason:DUPLICATED
        // 변경으로 버려진 닉네임의 1주일 잠금(회원 정책 §3). 무인증 API라 "본인 되돌리기" 예외를
        // 판정할 수 없어 버린 본인에게도 잠김으로 보인다 — 변경 주기가 월 1회라 실제로는 겹치지 않는다.
        return releaseLockService.lockedUntilFor(nickname, null)
                .map(NicknameAvailabilityResponse::recentlyReleased)
                .orElseGet(NicknameAvailabilityResponse::ok);        // valid:true, available:true
    }

    // ===== 토큰 파싱 헬퍼 =====
    private Claims parseSignupToken(String token) {
        Claims claims = parseOrThrow(token, ErrorCode.INVALID_SIGNUP_TOKEN, ErrorCode.INVALID_SIGNUP_TOKEN);
        if (!TokenType.SIGNUP.name().equals(claims.get("type")))
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
        return claims;
    }

    private Claims parseRefreshToken(String token) {
        Claims claims = parseOrThrow(token, ErrorCode.SESSION_EXPIRED, ErrorCode.SESSION_EXPIRED);
        if (!TokenType.REFRESH.name().equals(claims.get("type")))
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        return claims;
    }

    private Claims parseOrThrow(String token, ErrorCode onExpired, ErrorCode onInvalid) {
        try {
            return jwtProvider.parse(token);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(onExpired);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(onInvalid);
        }
    }
}
