package com.ruleup.ruleup_backend.oauth;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.OAuthProvider;
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
        if (client == null) throw new BusinessException(ErrorCode.LOGIN_PROVIDER_UNAVAILABLE);
        return client;
    }
}