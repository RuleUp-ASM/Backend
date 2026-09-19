package com.ruleup.ruleup_backend.intro.terms;

import com.ruleup.ruleup_backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 현행 버전 원문 파일이 있는지 기동 때 확인한다. 없으면 가입 화면 「보기」가 404 를 연다.
 * 기동을 막지는 않는다 — 원문 누락이 로그인·인증 전체를 멈출 이유는 없다.
 */
@Component
@RequiredArgsConstructor
public class TermsDocumentStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(TermsDocumentStartupCheck.class);
    private final AppProperties appProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        for (TermsDocument doc : TermsDocument.values()) {
            String version = doc.currentVersion(appProperties.client().termsVersions());
            if (!new ClassPathResource(doc.resourcePath(version)).exists()) {
                log.warn("terms_document_missing slug={} version={} path=classpath:{}",
                        doc.slug(), version, doc.resourcePath(version));
            }
        }
    }
}
