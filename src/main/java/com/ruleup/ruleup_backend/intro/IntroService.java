package com.ruleup.ruleup_backend.intro;

import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.intro.dto.IntroResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 앱 버전 게이트 로직 (GET /intro).
 *
 * <p>클라가 헤더로 보낸 {@code appVersionCode}(정수, 안드로이드 versionCode)를
 * 서버 설정({@code app.client.min-version-code})과 비교해 forceUpdate를 판정한다.
 * 결과는 다른 API와 동일하게 컨트롤러에서 {@code ApiResponse.ok(data)} 봉투로 감싸 내려간다.
 */
@Service
@RequiredArgsConstructor
public class IntroService {

    private final AppProperties appProperties;

    public IntroResponse resolve(int appVersionCode) {
        AppProperties.Client client = appProperties.client();

        boolean forceUpdate = appVersionCode < client.minVersionCode();

        return IntroResponse.of(
                forceUpdate,
                client.devTestMsg(),
                client.minAppVersion(),
                client.recommendAppVersion()
        );
    }
}