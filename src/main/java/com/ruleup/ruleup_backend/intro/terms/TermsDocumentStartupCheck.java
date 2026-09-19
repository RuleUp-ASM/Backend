package com.ruleup.ruleup_backend.intro.terms;

import com.ruleup.ruleup_backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 현행 버전 원문 파일이 있는지 기동 때 확인한다. 없으면 가입 화면 「보기」가 404 를 연다.
 * 원문 없는 버전을 intro 가 광고하지 않도록 요청을 받기 전에 기동을 중단한다.
 */
@Component
@RequiredArgsConstructor
public class TermsDocumentStartupCheck implements SmartInitializingSingleton {

    private final AppProperties appProperties;

    @Override
    public void afterSingletonsInstantiated() {
        for (TermsDocument doc : TermsDocument.values()) {
            String version = doc.currentVersion(appProperties.client().termsVersions());
            if (!new ClassPathResource(doc.resourcePath(version)).isReadable()) {
                throw new IllegalStateException("terms_document_missing: classpath:" + doc.resourcePath(version));
            }
        }
    }
}
