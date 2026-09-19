package com.ruleup.ruleup_backend.config;

import com.ruleup.ruleup_backend.intro.terms.TermsDocumentStartupCheck;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TermsDocumentStartupCheckTest {
    private TermsDocumentStartupCheck check(String tosVersion) {
        AppProperties properties = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(properties.client().termsVersions()).thenReturn(new AppProperties.Client.TermsVersions(
                tosVersion, "1.0", "1.0", "1.0", "1.0", "1.0", "1.0"));
        return new TermsDocumentStartupCheck(properties);
    }

    @Test void currentDocumentsArePackaged() {
        assertThatCode(check("1.0")::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test void versionWithoutDocumentCannotStart() {
        assertThatThrownBy(check("missing-version")::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms/terms-of-service/missing-version.md");
    }
}
