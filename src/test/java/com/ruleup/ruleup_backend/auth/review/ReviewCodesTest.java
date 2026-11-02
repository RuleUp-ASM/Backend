package com.ruleup.ruleup_backend.auth.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 심사 코드의 정규화·해시 (심사 계정 테크 스펙 5-2 · 5-3 · 5-7).
 *
 * <p>Secrets Manager 에는 원문 대신 <b>정규화한 코드의 SHA-256 hex</b> 만 둔다. 운영자가 해시를 만드는
 * 규칙과 서버가 입력을 비교하는 규칙이 한 글자라도 다르면 코드가 통째로 안 먹으므로, 규칙 자체를 못 박는다.
 */
class ReviewCodesTest {

    @Test
    @DisplayName("대문자로 바꾸고 하이픈·공백을 지운다 — 심사위원이 받은 그대로 치든 붙여 치든 같은 코드다")
    void normalizesCaseHyphensAndWhitespace() {
        assertThat(ReviewCodes.normalize("K7QM-4XRD-9WTF")).isEqualTo("K7QM4XRD9WTF");
        assertThat(ReviewCodes.normalize("k7qm-4xrd-9wtf")).isEqualTo("K7QM4XRD9WTF");
        assertThat(ReviewCodes.normalize("  k7qm 4xrd\t9wtf \n")).isEqualTo("K7QM4XRD9WTF");
        assertThat(ReviewCodes.normalize("K7QM4XRD9WTF")).isEqualTo("K7QM4XRD9WTF");
    }

    @Test
    @DisplayName("비었거나 null 이면 빈 문자열 — 비교에서 어떤 코드와도 맞지 않는다")
    void blankBecomesEmpty() {
        assertThat(ReviewCodes.normalize(null)).isEmpty();
        assertThat(ReviewCodes.normalize("")).isEmpty();
        assertThat(ReviewCodes.normalize(" - - ")).isEmpty();
    }

    @Test
    @DisplayName("해시는 정규화한 코드의 SHA-256 소문자 hex 64자다 — 운영자가 shasum -a 256 으로 만든 값과 같다")
    void hashIsLowercaseSha256Hex() {
        // printf 'K7QM4XRD9WTF' | shasum -a 256
        assertThat(ReviewCodes.sha256Hex("K7QM4XRD9WTF"))
                .isEqualTo("5902c09a4fe0fd68805ec09248a272c86e513c32b67cba756923a3a9ea955ee4");
    }

    @Test
    @DisplayName("해시는 정규화 뒤에 한다 — 하이픈을 넣어 만든 해시와는 다르다")
    void hashIsOverNormalizedForm() {
        assertThat(ReviewCodes.sha256Hex(ReviewCodes.normalize("k7qm-4xrd-9wtf")))
                .isEqualTo(ReviewCodes.sha256Hex("K7QM4XRD9WTF"))
                .isNotEqualTo(ReviewCodes.sha256Hex("K7QM-4XRD-9WTF"));
    }
}
