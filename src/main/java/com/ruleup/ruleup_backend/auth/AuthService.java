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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_AGREEMENT_VERSION = "1.0";   // 클라가 version 미전송 시 폴백
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
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
    private final LoginSessionService loginSessionService;
    private final SocialTokenService socialTokenService;
    private final AppProperties props;
    private final ApplicationEventPublisher eventPublisher;
    private final CountryResolver countryResolver;
    private final com.ruleup.ruleup_backend.reputation.MilestoneService milestoneService;
    private final com.ruleup.ruleup_backend.invitation.InvitationService invitationService;

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

        // 2) DB 분기 (기존 회원이면 토큰 발급, 신규면 signupToken만)
        return userRepository.findByOauthProviderAndOauthSubject(provider, info.subject())
                .map(user -> loginSessionService.loginExisting(user.getId(), provider, req, info))
                .orElseGet(() -> issueSignupToken(provider, req, info));
    }

    private OAuthLoginResponse issueSignupToken(OAuthProvider provider, OAuthLoginRequest req, OAuthUserInfo info) {
        // 동일 설치(installationId)에 이미 활성 계정이 있으면 신규 가입 분기를 차단한다(회원 정책 §1).
        // → 기존 계정 로그인을 유도 (403 INSTALLATION_ALREADY_REGISTERED)
        if (req.installationId() != null && !req.installationId().isBlank()
                && userRepository.existsActiveByInstallationId(req.installationId())) {
            throw new BusinessException(ErrorCode.INSTALLATION_ALREADY_REGISTERED);
        }
        // DB를 건드리지 않고 JWT(signupToken)만 만들어 돌려준다 → 트랜잭션 불필요
        String token = jwtProvider.issueSignupToken(info.subject(), provider.name(), info.email());
        // 가입 완료 시 social_tokens 로 옮길 IdP 토큰을 jti 기준으로 보류해 둔다
        socialTokenService.hold(jwtProvider.parse(token).getId(), info.idpTokens());
        return OAuthLoginResponse.newUser(token, props.jwt().signupTokenTtl(), info);
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
    // 하나라도 실패하면 전부 롤백되어야 한다 → @Transactional 필수.
    // (외부 호출 없음: signupToken 파싱은 우리 서버 안에서 끝남)
    @Transactional
    public SignupResponse signup(SignupRequest req) {
        Claims claims = parseSignupToken(req.signupToken());
        OAuthProvider provider = OAuthProvider.valueOf((String) claims.get("provider"));
        String oauthSubject = claims.getSubject();
        String email = (String) claims.get("email");

        // signupToken 1회용 — 이미 소모된 jti의 재제출은 즉시 거부(계약: 처음부터 다시)
        if (signupTokenStore.isUsed(claims.getId()))
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);

        // (provider, subject) 중복 가입 차단 — 경합 시 후발 요청은 기존 유저 로그인으로 수렴(테크 스펙 4-3)
        User existing = userRepository.findByOauthProviderAndOauthSubject(provider, oauthSubject).orElse(null);
        if (existing != null) {
            if (existing.isBanned()) throw new BusinessException(ErrorCode.ACCOUNT_BANNED);
            TokenService.TokenPair pair = tokenService.issueTokenPair(existing);
            UserScoreSummary summary = scoreSummaryRepository.findById(existing.getId()).orElse(null);
            return new SignupResponse(false, pair.accessToken(), pair.refreshToken(), "Bearer",
                    pair.expiresIn(), FlushIntervalPolicy.forUser(existing),
                    UserResponse.from(existing, summary));
        }

        // 닉네임 형식 → 중복(신청 PENDING·승인 닉네임 모두 점유로 본다)
        if (!NicknamePolicy.isValid(req.nickname()))
            throw new BusinessException(ErrorCode.NICKNAME_FORMAT_INVALID);
        if (userRepository.isNicknameTaken(req.nickname(), null))
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);

        // 관심 카테고리: 코드 유효성(12종) → 개수(0~6, 건너뛰기 허용)
        List<String> categories = (req.interestCategories() != null) ? req.interestCategories() : List.of();
        if (!InterestCategory.allValid(categories))
            throw new BusinessException(ErrorCode.CATEGORY_INVALID);
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
        if (userRepository.existsActiveByInstallationId(req.installationId()))
            throw new BusinessException(ErrorCode.INSTALLATION_ALREADY_REGISTERED);

        // signupToken 1회용 — 검증을 모두 통과한 시점에 jti를 소모한다
        if (!signupTokenStore.consume(claims.getId()))
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);

        User user = User.create(provider, oauthSubject, email, req.nickname(), null,
                new ArrayList<>(categories));
        // 임시 승인 닉네임(UUID 뒤 8자)이 이미 점유돼 있으면 다른 값으로 재시도 (DB 정리 §6)
        TempNicknameAllocator.assign(user, candidate -> userRepository.isNicknameTaken(candidate, user.getId()));
        user.registerDemographics(birthDate, gender);
        applyDeviceInfo(user, req.deviceInfo());              // 가입 시 기기 정보 최초 저장
        user.attachInstallation(req.installationId(), req.deviceId());
        user.updateCountryCode(countryResolver.resolve(deviceCountry(req.deviceInfo())));   // 지오 헤더 → 기기 지역 → Accept-Language
        userRepository.save(user);

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
                ? item.version() : DEFAULT_AGREEMENT_VERSION;
        userAgreementRepository.save(UserAgreement.of(user, type, agreed, version));
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
        return NicknameAvailabilityResponse.ok();                    // valid:true, available:true
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
