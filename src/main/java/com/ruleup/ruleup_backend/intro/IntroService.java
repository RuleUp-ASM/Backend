package com.ruleup.ruleup_backend.intro;

import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.intro.dto.IntroResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 앱 버전 게이트 로직 (GET /intro).
 *
 * <p>클라가 헤더로 보낸 {@code appVersionCode}(정수, 안드로이드 versionCode)를
 * 서버 설정({@code app.client.min-version-code})과 비교한다.
 * <ul>
 *   <li>appVersionCode &lt; minVersionCode → 강제 업데이트 필요(forceUpdate=true)</li>
 *   <li>그 외 → 정상(forceUpdate=false)</li>
 * </ul>
 * 본문(devTestMsg/minAppVersion/recommendAppVersion)은 두 경우 모두 동일하게 설정값에서 내려준다.
 * HTTP 상태(200/400)와 봉투 미사용 처리는 컨트롤러가 담당한다(웹 의존성은 서비스 밖에 둔다).
 */
@Service
@RequiredArgsConstructor
public class IntroService {

    private final AppProperties appProperties;

    public IntroResult resolve(int appVersionCode) {
        AppProperties.Client client = appProperties.client();

        boolean forceUpdate = appVersionCode < client.minVersionCode();

        IntroResponse body = IntroResponse.of(
                client.devTestMsg(),
                client.minAppVersion(),
                client.recommendAppVersion()
        );

        return new IntroResult(forceUpdate, body);
    }

    /** 버전 판정 결과. forceUpdate면 컨트롤러가 400, 아니면 200으로 내려준다. */
    public record IntroResult(boolean forceUpdate, IntroResponse body) {}
}