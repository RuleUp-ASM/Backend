package com.ruleup.ruleup_backend.auth.review.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시드 일회성 태스크의 인자 (심사 계정 테크 스펙 5-8).
 *
 * <p>인자 계약이 곧 운영 실수 방어다 — round 는 생성 모드가 <b>발급</b>하므로 어느 모드도 round 를 고르는 인자를 받지
 * 않는다. 리셋은 서버와 같은 설정에서 round 를 읽고, 운영자가 넣는 것은 「이 round 가 맞다」는 확인 값뿐이다.
 */
class ReviewSeedCommandTest {

    @Nested
    @DisplayName("create — 새 회차")
    class Create {

        @Test
        @DisplayName("계정 수와 평가 종료일을 받는다")
        void parsesAccountsAndEvaluationEnd() {
            ReviewSeedCommand cmd = ReviewSeedCommand.parse("--mode=create", "--accounts=6", "--evaluation-end=2026-11-20");

            assertThat(cmd).isEqualTo(new ReviewSeedCommand.Create(6, LocalDate.of(2026, 11, 20)));
        }

        @Test
        @DisplayName("계정 수나 평가 종료일이 빠지면 거절한다 — 기본값으로 만들지 않는다")
        void requiresAccountsAndEvaluationEnd() {
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=create", "--evaluation-end=2026-11-20"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=create", "--accounts=6"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("계정 수는 1~10 — subject 순번 2자리 안, 집계에 섞이는 심사 계정은 10개 이내(5-6)")
        void accountsWithinRange() {
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=create", "--accounts=0", "--evaluation-end=2026-11-20"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=create", "--accounts=11", "--evaluation-end=2026-11-20"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=create", "--accounts=six", "--evaluation-end=2026-11-20"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("round 를 인자로 받지 않는다 — round 는 생성 모드가 실행일 + 순번으로 발급한다")
        void refusesRoundArgument() {
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=create", "--accounts=6",
                    "--evaluation-end=2026-11-20", "--round=2026110301"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reset — 같은 회차 원상 복구")
    class Reset {

        @Test
        @DisplayName("확인용 round 는 선택이다")
        void confirmRoundIsOptional() {
            assertThat(ReviewSeedCommand.parse("--mode=reset")).isEqualTo(new ReviewSeedCommand.Reset(null));
            assertThat(ReviewSeedCommand.parse("--mode=reset", "--confirm-round=2026110301"))
                    .isEqualTo(new ReviewSeedCommand.Reset("2026110301"));
        }

        @Test
        @DisplayName("round 를 고르는 인자는 받지 않는다 — 대상 round 는 서버와 같은 설정에서 읽는다")
        void refusesRoundArgument() {
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=reset", "--round=2026110301"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("생성 모드 인자를 섞으면 거절한다")
        void refusesCreateArguments() {
            assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=reset", "--accounts=6"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("모드가 없거나 모르는 모드면 거절한다")
    void requiresKnownMode() {
        assertThatThrownBy(() -> ReviewSeedCommand.parse("--accounts=6", "--evaluation-end=2026-11-20"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewSeedCommand.parse("--mode=wipe"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
