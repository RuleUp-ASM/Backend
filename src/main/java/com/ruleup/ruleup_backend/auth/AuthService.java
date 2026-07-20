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
import com.ruleup.ruleup_backend.moderation.UserModerationRequested;
import com.ruleup.ruleup_backend.oauth.OAuthClient;
import com.ruleup.ruleup_backend.oauth.OAuthClientResolver;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_AGREEMENT_VERSION = "1.0";   // 클라가 version 미전송 시 폴백

    private final OAuthClientResolver resolver;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final TokenService tokenService;
    private final JwtProvider jwtProvider;
    private final AppProperties props;
    private final ApplicationEventPublisher eventPublisher;
    private final CountryResolver countryResolver;

    // ===== OAuth 로그인 =====
    // ⚠️ 일부러 @Transactional 을 붙이지 않는다.
    // fetchUserInfo()는 카카오/구글 서버로 나가는 외부 HTTP 호출이라 수 초가 걸릴 수 있다.
    // 그걸 트랜잭션 안에 넣으면 그 시간 내내 DB 커넥션을 붙잡고 있게 된다(= 커넥션 고갈 위험).
    // 그래서 "느린 외부 호출"은 트랜잭션 밖에서 하고,
    // "빠른 DB 쓰기"만 TokenService.issueTokenPair()의 짧은 트랜잭션으로 처리한다.
    public OAuthLoginResponse oauthLogin(OAuthProvider provider, OAuthLoginRequest req) {
        // deviceInfo는 로그인·가입 양쪽 필수(계약). 외부 호출 전에 빠르게 거부.
        requireValidDeviceInfo(req.deviceInfo());

        OAuthClient client = resolver.resolve(provider);

        // 1) 외부 IdP 호출 (트랜잭션 밖)
        OAuthUserInfo info = client.fetchUserInfo(req.code(), req.codeVerifier(), req.redirectUri());

        // 2) DB 분기 (기존 회원이면 토큰 발급, 신규면 signupToken만)
        return userRepository.findByOauthProviderAndOauthSubject(provider, info.subject())
                .map(user -> loginExisting(user, req))
                .orElseGet(() -> issueSignupToken(provider, info));
    }

    private OAuthLoginResponse loginExisting(User user, OAuthLoginRequest req) {
        // 로그인마다: 기기 정보 갱신 + 국가 코드(서버가 요청에서 해석) 최신화.
        // user는 트랜잭션 밖에서 조회된 detached 엔티티 → save(merge)로 반영(변경 없으면 UPDATE 생략).
        applyDeviceInfo(user, req.deviceInfo());
        user.updateCountryCode(countryResolver.resolve(deviceCountry(req.deviceInfo())));
        userRepository.save(user);
        TokenService.TokenPair pair = tokenService.issueTokenPair(user);   // 내부에서 @Transactional
        BigDecimal temp = reputationScoreRepository.findById(user.getId())
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);
        int flushIntervalSec = FlushIntervalPolicy.forUser(user);   // 기기 스펙 기반 sync 주기 부트스트랩
        return OAuthLoginResponse.existing(pair, user, temp, flushIntervalSec);
    }

    private OAuthLoginResponse issueSignupToken(OAuthProvider provider, OAuthUserInfo info) {
        // DB를 건드리지 않고 JWT(signupToken)만 만들어 돌려준다 → 트랜잭션 불필요
        String token = jwtProvider.issueSignupToken(info.subject(), provider.name(), info.email());
        return OAuthLoginResponse.newUser(token, props.jwt().signupTokenTtl(),
                info.email(), info.profileImageUrl());
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
    // 여러 테이블(user, reputation, agreements, refresh_token)을 함께 쓰므로
    // 하나라도 실패하면 전부 롤백되어야 한다 → @Transactional 필수.
    // (외부 호출 없음: signupToken 파싱은 우리 서버 안에서 끝남)
    @Transactional
    public SignupResponse signup(SignupRequest req) {
        Claims claims = parseSignupToken(req.signupToken());
        OAuthProvider provider = OAuthProvider.valueOf((String) claims.get("provider"));
        String oauthSubject = claims.getSubject();
        String email = (String) claims.get("email");

        // 닉네임 형식 → 중복
        if (!NicknamePolicy.isValid(req.nickname()))
            throw new BusinessException(ErrorCode.NICKNAME_FORMAT_INVALID);
        if (userRepository.existsByNickname(req.nickname()))
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);

        // 관심 카테고리: 개수(1~6) → 코드 유효성
        List<String> categories = (req.interestCategories() != null) ? req.interestCategories() : List.of();
        if (!InterestCategory.isCountValid(categories))
            throw new BusinessException(ErrorCode.CATEGORY_LIMIT_EXCEEDED);
        if (!InterestCategory.allValid(categories))
            throw new BusinessException(ErrorCode.CATEGORY_INVALID);

        // 약관: terms·privacy 필수(동의 true)
        SignupRequest.Agreements ag = req.agreements();
        if (ag == null
                || !Boolean.TRUE.equals(ag.terms())
                || !Boolean.TRUE.equals(ag.privacy()))
            throw new BusinessException(ErrorCode.AGREEMENT_REQUIRED);

        // deviceInfo는 가입에도 필수(계약).
        requireValidDeviceInfo(req.deviceInfo());

        User user = User.create(provider, oauthSubject, email,
                req.nickname(), req.profileImageUrl(), new ArrayList<>(categories));
        applyDeviceInfo(user, req.deviceInfo());              // 가입 시 기기 정보 최초 저장
        user.updateCountryCode(countryResolver.resolve(deviceCountry(req.deviceInfo())));   // 지오 헤더 → 기기 지역 → Accept-Language
        userRepository.save(user);
        reputationScoreRepository.save(ReputationScore.createDefault(user));
        saveAgreements(user, ag);

        // 가입은 여기서 그대로 완료(닉네임/사진 상태는 PENDING).
        // 커밋 후 비동기로 LLM 검수 → 문제면 타인에게 임시 닉네임/숨김 + 알림.
        eventPublisher.publishEvent(new UserModerationRequested(user.getId()));

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        int flushIntervalSec = FlushIntervalPolicy.forUser(user);   // deviceInfo 확정 저장 시점 → 주기 부트스트랩
        return SignupResponse.from(pair, user, ReputationScore.INITIAL_TEMPERATURE, flushIntervalSec);
    }

    /** deviceInfo는 로그인·가입 양쪽 필수. 누락/형식오류면 INVALID_DEVICE_INFO. */
    private void requireValidDeviceInfo(DeviceInfoRequest device) {
        if (device == null || !device.isValid())
            throw new BusinessException(ErrorCode.INVALID_DEVICE_INFO);
    }

    /** 기기 정보를 유저에 반영(최신 1건 갱신). 호출 전 requireValidDeviceInfo 로 검증됨. */
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

    private void saveAgreements(User user, SignupRequest.Agreements ag) {
        userAgreementRepository.save(UserAgreement.agree(user, AgreementType.TERMS, DEFAULT_AGREEMENT_VERSION));
        userAgreementRepository.save(UserAgreement.agree(user, AgreementType.PRIVACY, DEFAULT_AGREEMENT_VERSION));
        if (Boolean.TRUE.equals(ag.marketing())) {
            userAgreementRepository.save(UserAgreement.agree(user, AgreementType.MARKETING, DEFAULT_AGREEMENT_VERSION));
        }
    }

    // ===== 토큰 재발급 (회전) =====
    @Transactional
    public TokenResponse refresh(String refreshTokenValue) {
        Claims claims = parseRefreshToken(refreshTokenValue);
        UUID userId = UUID.fromString(claims.getSubject());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenService.sha256(refreshTokenValue))
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_EXPIRED));
        if (stored.isRevoked()) throw new BusinessException(ErrorCode.SESSION_EXPIRED);

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_EXPIRED));

        stored.revoke();                                   // 기존 무효화(회전)
        return TokenResponse.from(tokenService.issueTokenPair(user));
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
        if (userRepository.existsByNickname(nickname))
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