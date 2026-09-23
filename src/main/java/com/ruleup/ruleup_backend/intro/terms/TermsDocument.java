package com.ruleup.ruleup_backend.intro.terms;

import com.ruleup.ruleup_backend.config.AppProperties;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/**
 * 공개 원문을 두는 필수 약관 3종. 법적·개인정보 정책상 개인정보 처리방침은 앱·웹에 상시 공개해야 하고,
 * 가입 화면 「보기」가 여는 원문은 동의받는 버전과 같아야 한다.
 *
 * <p>원문 파일은 {@code classpath:terms/{slug}/{version}.md} 에 둔다. 버전 문자열은
 * {@code app.client.terms-versions} 와 같은 값이다 — 개정하면 새 파일을 추가하고 설정 버전을 올린다.
 * 옛 파일은 지우지 않는다(과거에 동의한 원문을 보여 줄 수 있어야 한다).
 */
public enum TermsDocument {
    TERMS_OF_SERVICE("terms-of-service", "서비스 이용약관", AppProperties.Client.TermsVersions::termsOfService),
    PRIVACY_POLICY("privacy-policy", "개인정보 처리방침", AppProperties.Client.TermsVersions::privacyPolicy),
    LOCATION_SERVICE("location-service", "위치기반 서비스 이용약관", AppProperties.Client.TermsVersions::locationService);

    private final String slug;
    private final String title;
    private final Function<AppProperties.Client.TermsVersions, String> currentVersion;

    TermsDocument(String slug, String title, Function<AppProperties.Client.TermsVersions, String> currentVersion) {
        this.slug = slug;
        this.title = title;
        this.currentVersion = currentVersion;
    }

    public String slug() { return slug; }
    public String title() { return title; }
    public String currentVersion(AppProperties.Client.TermsVersions versions) { return currentVersion.apply(versions); }
    public String resourcePath(String version) { return "terms/" + slug + "/" + version + ".md"; }

    public static Optional<TermsDocument> ofSlug(String slug) {
        return Arrays.stream(values()).filter(d -> d.slug.equals(slug)).findFirst();
    }
}
