package com.ruleup.ruleup_backend.intro.dto;

import com.ruleup.ruleup_backend.config.AppProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /intro 응답 data (공통 봉투 ApiResponse 안에 실린다).
 *
 * <p>최종 응답 형태(다른 API와 동일한 봉투):
 * <pre>
 * { "success": true,
 *   "data": { "forceUpdate": false, "devTestMsg": null, "minAppVersion": "1.0.0",
 *             "termsVersions": { "termsOfService": "1.0", ... } },
 *   "error": null }
 * </pre>
 *
 * <p>서버가 헤더 platform·appVersionCode를 그 플랫폼의 최소 지원 코드와 비교해 {@code forceUpdate}를
 * 판정한다. 업데이트 정책은 예외 없이 강제라 "권장 버전"은 내려주지 않는다.
 * 클라는 봉투를 풀어({@code getOrThrow()}) data를 받고, forceUpdate면 강제 업데이트 화면을 띄운다
 * (표시 문구는 minAppVersion 사용). 별도 400/에러 분기가 필요 없다.
 * 빈 문자열 설정값은 null로 내려, 클라의 {@code ?: UNKNOWN} 폴백이 동작하도록 한다.
 */
@Schema(description = "앱 인트로/버전 안내 (ApiResponse.data)")
public record IntroResponse(

        @Schema(description = "강제 업데이트 필요 여부", example = "false")
        boolean forceUpdate,

        @Schema(description = "개발/점검용 안내 메시지 (없으면 null)", example = "점검 중입니다.")
        String devTestMsg,

        @Schema(description = "지원하는 최소 앱 버전명 (이 미만이면 강제 업데이트)", example = "1.0.0")
        String minAppVersion,

        @Schema(description = "현행 동의 버전 7종(약관 5종 + 법정 개별 동의 2종) — 가입 동의 버전 기록·약관 개정 시 재동의 판정용")
        AppProperties.Client.TermsVersions termsVersions,

        @Schema(description = "필수 약관 3종 원문 URL — termsVersions 와 같은 버전을 가리킨다. 가입 화면 「보기」가 연다.")
        TermsUrls termsUrls
) {

    @Schema(name = "TermsUrls", description = "필수 약관 원문 공개 URL(로그인 불필요)")
    public record TermsUrls(
            @Schema(example = "https://staging-api.ruleup.co.kr/terms/terms-of-service/1.0") String termsOfService,
            @Schema(example = "https://staging-api.ruleup.co.kr/terms/privacy-policy/1.0") String privacyPolicy,
            @Schema(example = "https://staging-api.ruleup.co.kr/terms/location-service/1.0") String locationService) {

        public static TermsUrls of(String baseUrl, AppProperties.Client.TermsVersions versions) {
            java.util.function.Function<com.ruleup.ruleup_backend.intro.terms.TermsDocument, String> url =
                    d -> baseUrl + "/terms/" + d.slug() + "/" + d.currentVersion(versions);
            return new TermsUrls(url.apply(com.ruleup.ruleup_backend.intro.terms.TermsDocument.TERMS_OF_SERVICE),
                    url.apply(com.ruleup.ruleup_backend.intro.terms.TermsDocument.PRIVACY_POLICY),
                    url.apply(com.ruleup.ruleup_backend.intro.terms.TermsDocument.LOCATION_SERVICE));
        }
    }


    /** 버전 문구의 빈 문자열·공백은 null로 정규화해서 만든다. */
    public static IntroResponse of(boolean forceUpdate, String devTestMsg, String minAppVersion,
                                   AppProperties.Client.TermsVersions termsVersions, String baseUrl) {
        return new IntroResponse(
                forceUpdate,
                blankToNull(devTestMsg),
                blankToNull(minAppVersion),
                termsVersions,
                TermsUrls.of(baseUrl, termsVersions)
        );
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
