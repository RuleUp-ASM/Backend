package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.domain.TierBands;
import com.ruleup.ruleup_backend.score.domain.TierNotice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 티어 고지 판정 — <b>표시 티어</b>가 기준이고, 대부분의 점수 변동은 아무것도 보내지 않는다.
 *
 * <p>점수는 매일 움직인다. 그래서 이 판정이 헐거우면 예외가 아니라 <b>매일 반복되는 잘못된 알림</b>이
 * 된다 — 방향이 뒤집히거나, 이미 승급했는데 "코앞"이 계속 울리거나, 밴드 한가운데서 예고가 나간다.
 */
class TierNoticeTest {

    @Nested
    @DisplayName("티어 변동")
    class Changed {

        @Test
        @DisplayName("올라가면 UP, 내려가면 DOWN")
        void direction() {
            assertThat(TierNotice.of(Tier.BRONZE, Tier.SILVER, 100))
                    .satisfies(n -> {
                        assertThat(n.kind()).isEqualTo(TierNotice.Kind.CHANGED);
                        assertThat(n.direction()).isEqualTo(TierNotice.UP);
                    });
            assertThat(TierNotice.of(Tier.GOLD, Tier.SILVER, 270))
                    .satisfies(n -> {
                        assertThat(n.kind()).isEqualTo(TierNotice.Kind.CHANGED);
                        assertThat(n.direction()).isEqualTo(TierNotice.DOWN);
                    });
        }

        @Test
        @DisplayName("가입 직후 UNRANKED 에서의 첫 부여는 승급이 아니다")
        void firstAssignmentIsNotPromotion() {
            assertThat(TierNotice.of(Tier.UNRANKED, Tier.BRONZE, 10).isNone()).isTrue();
            assertThat(TierNotice.of(Tier.BRONZE, Tier.UNRANKED, 0).isNone()).isTrue();
        }
    }

    @Nested
    @DisplayName("경계 근접")
    class BoundaryNear {

        @Test
        @DisplayName("다음 티어까지 10점 이내면 예고한다")
        void nearPromotion() {
            // 실버 시작점 100 — 브론즈 95점이면 5점 남았다.
            assertThat(TierNotice.of(Tier.BRONZE, Tier.BRONZE, 95))
                    .satisfies(n -> {
                        assertThat(n.kind()).isEqualTo(TierNotice.Kind.BOUNDARY_NEAR);
                        assertThat(n.direction()).isEqualTo(TierNotice.UP);
                    });
        }

        @Test
        @DisplayName("밴드 한가운데서는 아무것도 보내지 않는다 — 매일 울리면 안 된다")
        void middleOfBandIsSilent() {
            assertThat(TierNotice.of(Tier.BRONZE, Tier.BRONZE, 50).isNone()).isTrue();
            assertThat(TierNotice.of(Tier.GOLD, Tier.GOLD, 400).isNone()).isTrue();
        }

        @Test
        @DisplayName("강등선까지 10점 이내면 예고한다")
        void nearDemotion() {
            // 실버 시작점 100, 강등 확정선은 79(=100-20-1). 85점이면 6점 여유다.
            assertThat(TierNotice.of(Tier.SILVER, Tier.SILVER, 85))
                    .satisfies(n -> {
                        assertThat(n.kind()).isEqualTo(TierNotice.Kind.BOUNDARY_NEAR);
                        assertThat(n.direction()).isEqualTo(TierNotice.DOWN);
                    });
        }

        @Test
        @DisplayName("브론즈는 강등 예고를 하지 않는다 — 더 내려갈 티어가 없다")
        void bronzeHasNoDemotion() {
            assertThat(TierNotice.of(Tier.BRONZE, Tier.BRONZE, 3).isNone())
                    .as("0점 근처여도 강등 예고는 없다").isTrue();
        }

        @Test
        @DisplayName("루비는 승급 예고를 하지 않는다 — 더 올라갈 티어가 없다")
        void rubyHasNoPromotion() {
            assertThat(TierNotice.of(Tier.RUBY, Tier.RUBY, TierBands.MAX_SCORE).isNone()).isTrue();
        }

        @Test
        @DisplayName("이미 경계를 넘었으면 예고가 아니라 변동이다")
        void crossedIsChangedNotNear() {
            // 100점은 실버 시작점 — 남은 점수가 0이라 예고할 자리가 아니다.
            assertThat(TierNotice.of(Tier.SILVER, Tier.SILVER, 100).isNone()).isTrue();
        }
    }
}
