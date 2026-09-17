package com.ruleup.ruleup_backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 약관 버전은 <b>비울 수 없는 값</b>이다 (QA ONB-12).
 *
 * <h4>왜 기동에서 막는가</h4>
 * 이 값은 법적 동의 입증의 기준이다. 비어도 그럭저럭 굴러가게 만들면 <b>어떤 버전에 동의한
 * 것인지 불분명한 동의 기록</b>이 남는다. 게다가 비어 있으면 사용자가 빠져나올 수 없는 상태가
 * 만들어졌다 — 가입은 통과하는데({@code AuthService.saveAgreement} 는 버전을 검증하지 않는다)
 * 저장 버전이 현행("")과 달라 즉시 재동의 대상이 되고, 재동의 제출은 {@code current.equals(version)}
 * 때문에 400 으로 막힌다.
 *
 * <p>운영이 이 값을 비울 이유가 없으므로 런타임에 사고로 겪기보다 기동에서 끊는 편이 싸다.
 * 잘못된 설정이 조용히 가려지지 않는다는 것이 이 선택의 핵심이다.
 */
class TermsVersionsContractTest {

    private static AppProperties.Client.TermsVersions versions(String tos) {
        return new AppProperties.Client.TermsVersions(
                tos, "1.0", "1.0", "1.0", "1.0", "1.0", "1.0");
    }

    @Test
    @DisplayName("빈 문자열이면 기동하지 못한다 — 어느 항목이 비었는지 이름까지 말한다")
    void blankVersionRefusesStartup() {
        assertThatThrownBy(() -> versions(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms-of-service");
    }

    @Test
    @DisplayName("공백만 있는 값도 빈 값이다")
    void whitespaceOnlyIsBlank() {
        assertThatThrownBy(() -> versions("   ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("설정이 아예 없어 null 로 들어와도 막는다")
    void nullVersionRefusesStartup() {
        assertThatThrownBy(() -> versions(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정상 값은 그대로 통과한다 — 항목별로 꺼내 쓸 수 있다")
    void validVersionsPass() {
        assertThatCode(() -> versions("1.0")).doesNotThrowAnyException();
        assertThat(versions("2.1").of(
                com.ruleup.ruleup_backend.agreement.domain.AgreementType.TOS)).isEqualTo("2.1");
    }
}
