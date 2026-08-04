package com.ruleup.ruleup_backend.intro;

import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.intro.dto.IntroResponse;
import com.ruleup.ruleup_backend.user.domain.Platform;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 앱 버전 게이트 로직 (GET /intro).
 *
 * <p>클라가 헤더로 보낸 {@code platform}·{@code appVersionCode}를 그 플랫폼의 최소 지원 코드
 * ({@code app.client.<platform>.min-version-code})와 비교해 forceUpdate를 판정한다.
 * 플랫폼을 나누는 이유는 스토어 심사 주기·버전 체계가 달라 최소 지원 버전이 서로 다르기 때문이다.
 * 결과는 다른 API와 동일하게 컨트롤러에서 {@code ApiResponse.ok(data)} 봉투로 감싸 내려간다.
 */
@Service
@RequiredArgsConstructor
public class IntroService {

    private final AppProperties appProperties;

    public IntroResponse resolve(Platform platform, int appVersionCode) {
        AppProperties.Client client = appProperties.client();
        AppProperties.Client.Version version = client.versionOf(platform);

        boolean forceUpdate = appVersionCode < version.minVersionCode();

        return IntroResponse.of(
                forceUpdate,
                client.devTestMsg(),
                version.minAppVersion(),
                client.termsVersions()
        );
    }
}
