package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.agreement.AgreementType;
import com.ruleup.ruleup_backend.agreement.UserAgreement;
import com.ruleup.ruleup_backend.agreement.UserAgreementRepository;
import com.ruleup.ruleup_backend.auth.dto.*;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.oauth.OAuthClient;
import com.ruleup.ruleup_backend.oauth.OAuthClientResolver;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.reputation.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.security.JwtProvider;
import com.ruleup.ruleup_backend.security.TokenType;
import com.ruleup.ruleup_backend.user.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String AGREEMENT_VERSION = "1.0";   // W1 약관 버전

    private final OAuthClientResolver resolver;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final TokenService tokenService;
    private final JwtProvider jwtProvider;
    private final AppProperties props;

    // ===== OAuth 로그인 =====
    @Transactional
    public OAuthLoginResponse oauthLogin(OAuthProvider provider, OAuthLoginRequest req) {
        OAuthClient client = resolver.resolve(provider);
        OAuthUserInfo info = client.fetchUserInfo(req.code(), req.codeVerifier(), req.redirectUri());
        return userRepository.findByOauthProviderAndOauthSubject(provider, info.subject())
                .map(this::loginExisting)
                .orElseGet(() -> issueSignupToken(provider, info));
    }

    private OAuthLoginResponse loginExisting(User user) {
        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        BigDecimal temp = reputationScoreRepository.findById(user.getId())
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);
        return OAuthLoginResponse.existing(pair, user, temp);
    }

    private OAuthLoginResponse issueSignupToken(OAuthProvider provider, OAuthUserInfo info) {
        String token = jwtProvider.issueSignupToken(info.subject(), provider.name(), info.email());
        return OAuthLoginResponse.newUser(token, props.jwt().signupTokenTtl(),
                info.email(), info.profileImageUrl());
    }

    // ===== 가입 =====
    @Transactional
    public SignupResponse signup(SignupRequest req) {
        Claims claims = parseSignupToken(req.signupToken());
        OAuthProvider provider = OAuthProvider.valueOf((String) claims.get("provider"));
        String oauthSubject = claims.getSubject();
        String email = (String) claims.get("email");

        if (!NicknamePolicy.isValid(req.nickname()))
            throw new BusinessException(ErrorCode.NICKNAME_INVALID);
        if (userRepository.existsByNickname(req.nickname()))
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);

        List<String> categories = (req.interestCategories() != null) ? req.interestCategories() : List.of();
        if (!InterestCategory.allValid(categories))
            throw new BusinessException(ErrorCode.INTEREST_CATEGORY_INVALID);

        SignupRequest.Agreements ag = req.agreements();
        if (ag == null || !ag.terms() || !ag.privacy())
            throw new BusinessException(ErrorCode.AGREEMENT_REQUIRED);

        User user = User.create(provider, oauthSubject, email,
                req.nickname(), req.profileImageUrl(), new ArrayList<>(categories));
        userRepository.save(user);
        reputationScoreRepository.save(ReputationScore.createDefault(user));
        saveAgreements(user, ag);

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        return SignupResponse.from(pair, user, ReputationScore.INITIAL_TEMPERATURE);
    }

    private void saveAgreements(User user, SignupRequest.Agreements ag) {
        userAgreementRepository.save(UserAgreement.agree(user, AgreementType.TERMS, AGREEMENT_VERSION));
        userAgreementRepository.save(UserAgreement.agree(user, AgreementType.PRIVACY, AGREEMENT_VERSION));
        if (ag.marketing()) {
            userAgreementRepository.save(UserAgreement.agree(user, AgreementType.MARKETING, AGREEMENT_VERSION));
        }
    }

    // ===== 토큰 재발급 (회전) =====
    @Transactional
    public TokenResponse refresh(String refreshTokenValue) {
        Claims claims = parseRefreshToken(refreshTokenValue);
        UUID userId = UUID.fromString(claims.getSubject());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenService.sha256(refreshTokenValue))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (stored.isRevoked()) throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        stored.revoke();                                   // 기존 무효화(회전)
        return TokenResponse.from(tokenService.issueTokenPair(user));
    }

    // ===== 로그아웃 (멱등) =====
    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByTokenHash(TokenService.sha256(refreshTokenValue))
                .ifPresent(RefreshToken::revoke);          // 없거나 이미 무효여도 성공
    }

    // ===== 닉네임 사용 가능 여부 =====
    @Transactional(readOnly = true)
    public NicknameAvailabilityResponse checkNickname(String nickname) {
        if (!NicknamePolicy.isValid(nickname))
            return new NicknameAvailabilityResponse(false, "NICKNAME_INVALID");
        if (userRepository.existsByNickname(nickname))
            return new NicknameAvailabilityResponse(false, "NICKNAME_DUPLICATED");
        return new NicknameAvailabilityResponse(true, null);
    }

    // ===== 토큰 파싱 헬퍼 =====
    private Claims parseSignupToken(String token) {
        Claims claims = parseOrThrow(token, ErrorCode.SIGNUP_TOKEN_EXPIRED, ErrorCode.SIGNUP_TOKEN_INVALID);
        if (!TokenType.SIGNUP.name().equals(claims.get("type")))
            throw new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID);
        return claims;
    }

    private Claims parseRefreshToken(String token) {
        Claims claims = parseOrThrow(token, ErrorCode.REFRESH_TOKEN_EXPIRED, ErrorCode.REFRESH_TOKEN_INVALID);
        if (!TokenType.REFRESH.name().equals(claims.get("type")))
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
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