package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.dto.OAuthLoginRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.oauth.OAuthClient;
import com.ruleup.ruleup_backend.oauth.OAuthClientResolver;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.reputation.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.security.JwtProvider;
import com.ruleup.ruleup_backend.user.OAuthProvider;
import com.ruleup.ruleup_backend.user.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** OAuth 로그인 오케스트레이션: IdP 검증 → 기존/신규 분기. */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OAuthClientResolver resolver;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final TokenService tokenService;
    private final JwtProvider jwtProvider;
    private final AppProperties props;

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
}