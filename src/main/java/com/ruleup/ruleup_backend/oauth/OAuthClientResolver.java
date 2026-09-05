package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** provider 값에 맞는 OAuthClient 구현체를 찾아준다. */
@Component
public class OAuthClientResolver {

    private final Map<OAuthProvider, OAuthClient> clients;

    public OAuthClientResolver(List<OAuthClient> clientList) {
        this.clients = clientList.stream()
                .collect(Collectors.toMap(OAuthClient::provider, Function.identity()));
    }

    public OAuthClient resolve(OAuthProvider provider) {
        OAuthClient client = clients.get(provider);
        // NAVER·APPLE 처럼 enum 에는 있지만 아직 붙이지 않은 provider — 사용자 잘못이 아니라
        // 서버가 아직 지원하지 않는 것이다. "연결 실패"로 뭉치면 재시도만 반복하게 된다.
        if (client == null)
            throw BusinessException.withMessage(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE, "PROVIDER_NOT_SUPPORTED",
                    "아직 지원하지 않는 소셜 로그인이에요. 다른 계정으로 로그인해주세요.");
        return client;
    }
}