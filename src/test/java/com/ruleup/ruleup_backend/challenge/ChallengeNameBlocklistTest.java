package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.challenge.moderation.ChallengeNameBlocklist;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 규칙 기반 동기 blocklist(§5.1) — 명백 비속어만 즉시 차단, 일반 이름은 통과.
 */
class ChallengeNameBlocklistTest {

    private final ChallengeNameBlocklist blocklist = new ChallengeNameBlocklist();

    @Test
    @DisplayName("일반 챌린지 이름은 통과")
    void clean_passes() {
        assertThatCode(() -> blocklist.validate("아침 7시 기상")).doesNotThrowAnyException();
        assertThatCode(() -> blocklist.validate(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("명백 비속어는 CHALLENGE_NAME_REJECTED(422)로 차단 — 공백/특수문자 우회 일부 흡수")
    void profanity_blocked() {
        assertThatThrownBy(() -> blocklist.validate("씨발 챌린지"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_NAME_REJECTED);

        // "병 신"처럼 공백을 끼워 넣어도 정규화로 잡힌다.
        assertThatThrownBy(() -> blocklist.validate("병 신"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("ErrorCode 매핑은 422 UNPROCESSABLE_ENTITY")
    void errorcode_http_status() {
        assertThat(ErrorCode.CHALLENGE_NAME_REJECTED.getStatus().value()).isEqualTo(422);
    }
}
