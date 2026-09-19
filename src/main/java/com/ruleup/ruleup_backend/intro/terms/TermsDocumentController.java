package com.ruleup.ruleup_backend.intro.terms;

import com.ruleup.ruleup_backend.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 약관 원문 공개 페이지 — 로그인 없이 열린다(개인정보 처리방침 상시 공개 의무).
 *
 * <p>원문을 서버가 해석하지 않는다. 마크다운을 그대로 이스케이프해 줄바꿈만 살려 보여 준다 —
 * 법적 문서를 렌더러가 다르게 그려 문구가 달라 보이는 일이 없게 하고, 원문 파일과 화면이 글자 단위로 같다.
 */
@Tag(name = "Intro")
@RestController
@RequiredArgsConstructor
public class TermsDocumentController {

    private final AppProperties appProperties;

    @Operation(summary = "약관 원문(현행 버전)", description = "slug: terms-of-service · privacy-policy · location-service")
    @GetMapping(value = "/terms/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> current(@PathVariable String slug) throws IOException {
        TermsDocument doc = TermsDocument.ofSlug(slug).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        return render(doc, doc.currentVersion(appProperties.client().termsVersions()));
    }

    @Operation(summary = "약관 원문(특정 버전)", description = "동의 당시 버전의 원문을 연다. 가입 화면 「보기」는 intro 의 termsUrls 를 연다.")
    @GetMapping(value = "/terms/{slug}/{version}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> version(@PathVariable String slug, @PathVariable String version) throws IOException {
        TermsDocument doc = TermsDocument.ofSlug(slug).orElse(null);
        if (doc == null || !version.matches("[0-9A-Za-z.\\-]{1,20}")) return ResponseEntity.notFound().build();
        return render(doc, version);
    }

    private ResponseEntity<String> render(TermsDocument doc, String version) throws IOException {
        ClassPathResource resource = new ClassPathResource(doc.resourcePath(version));
        if (!resource.exists()) return ResponseEntity.notFound().build();
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String html = "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + HtmlUtils.htmlEscape(doc.title()) + " (v" + HtmlUtils.htmlEscape(version) + ")</title>"
                + "<style>body{margin:0;padding:16px;font:15px/1.7 -apple-system,'Noto Sans KR',sans-serif;color:#222;background:#fff}"
                + "main{max-width:760px;margin:0 auto;white-space:pre-wrap;word-break:keep-all;overflow-wrap:anywhere}"
                + "@media (prefers-color-scheme:dark){body{color:#e6e6e6;background:#121212}}</style>"
                + "</head><body><main>" + HtmlUtils.htmlEscape(text) + "</main></body></html>";
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(html);
    }
}
