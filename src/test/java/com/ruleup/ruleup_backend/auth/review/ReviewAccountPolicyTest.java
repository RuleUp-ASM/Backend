package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.sanction.ReviewAccounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 심사 계정 판정 컴포넌트 (심사 계정 테크 스펙 5-1 · 5-3 · 5-5).
 *
 * <p>모든 적용 지점이 이 컴포넌트 하나만 부른다 — 여기서 새면 탐색·참여·코드 로그인 전부가 샌다.
 * 그래서 「꺼져 있다」「아니다」 쪽을 먼저 못 박는다.
 *
 * <h4>구성</h4>
 * <ul>
 *   <li>방 격리는 없다 — 심사 계정은 탐색·참여·집계에서 일반 유저와 같다. {@code REVIEW_ACCOUNT_IDS} 는 기존 면제
 *       (Play Integrity·연속 실패 강퇴·휴면)와 코드 매핑 검증에만 쓴다</li>
 *   <li>코드 로그인 대상 = {@code codes} 에 매핑된 계정만 — 더미 멤버·Play 심사 계정은 코드가 없다</li>
 *   <li>스위치 = {@code enabled} 이고 {@code expiresAt} 전일 때만 켜짐. {@code expiresAt} 은 요청마다 현재 시각과 비교</li>
 *   <li>{@code codes} 의 userId 가 {@code REVIEW_ACCOUNT_IDS} 에 없으면 <b>기동 중단</b> — 일반 유저 계정에 코드 로그인이 열리는 사고 방지</li>
 *   <li><b>코드 폐기</b>는 항목을 지우지 않고 {@code "revoked": true} 로 바꾼다 — 그 코드로는 로그인되지 않지만 계정은
 *       종료 대상에 남아, 기존 세션이 스위치와 무관하게 끊긴다. 항목을 지우면 기존 세션이 종료 대상에서 빠진다</li>
 *   <li>세션 종료 판정 {@code isCodeSessionEnded(userId)} = 코드 계정이고 (스위치 꺼짐 또는 코드 폐기) — 재발급·종료
 *       스케줄러가 이것 하나로 판정한다</li>
 *   <li><b>심사 회차</b> {@code round} — 회차마다 새 계정을 쓴다. 지난 회차 계정에는 이전 심사위원의 동의·데이터가 남아 있으므로
 *       코드 로그인 대상은 <b>이번 회차 계정</b>뿐이다. 회차는 계정의 바뀌지 않는 {@code oauth_subject}
 *       ({@code ruleup-review-{round}-NN})에서 읽는다 — 탈퇴 시각으로 가르면 아직 ACTIVE 인 지난 계정이나 종료가 늦어
 *       새 회차에 탈퇴된 지난 계정을 놓친다({@code isCurrentRoundAccount})</li>
 *   <li>round 형식은 {@code yyyyMMdd}(시드 실행일, KST) + 순번 2자리이고 시드 생성 모드가 발급한다 — 같은 회차의 subject 가
 *       이미 있으면 시드가 실패하므로 새 회차는 새 값이 된다. 기동 시 round 날짜가 expiresAt 전이고 그로부터
 *       {@value #MAX_REVIEW_DAYS}일 안인지 본다 — 지난 설정을 복사해 expiresAt 만 늦추면 기동이 멈춘다</li>
 * </ul>
 */
class ReviewAccountPolicyTest {

    static final UUID JUDGE_1 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e01");
    static final UUID JUDGE_2 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e02");
    /** 코드가 폐기된 심사 계정. */
    static final UUID JUDGE_3 = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e03");
    static final UUID DUMMY = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e51");
    static final UUID PLAY_REVIEWER = UUID.fromString("01930c1e-5a7b-7c2d-9e4f-1a2b3c4d5e61");
    static final UUID NORMAL_USER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** printf 'K7QM4XRD9WTF' | shasum -a 256 */
    static final String JUDGE_1_HASH = "5902c09a4fe0fd68805ec09248a272c86e513c32b67cba756923a3a9ea955ee4";
    /** printf 'A2B3C4D5E6F7' | shasum -a 256 */
    static final String JUDGE_2_HASH = "5ff07f62e9e0fb0d3d993603bfcf355030ca2f3e945ff6d3755344fe7693d08d";
    /** printf 'Q8W9E2R3T4Y5' | shasum -a 256 */
    static final String JUDGE_3_HASH = "b8179045e76db0648c7a9e311897ea1834d7efa5324e7999e144b26d5d87b5d0";

    static final String ROUND = "2026110101";
    /** 한 회차의 최대 길이 — round 날짜부터 expiresAt 까지. */
    static final int MAX_REVIEW_DAYS = 60;
    static final String EXPIRES_AT = "2026-11-15T00:00:00+09:00";
    static final Instant EXPIRY = Instant.parse("2026-11-14T15:00:00Z");

    static final ReviewAccounts ACCOUNTS = new ReviewAccounts(
            String.join(",", JUDGE_1.toString(), JUDGE_2.toString(), JUDGE_3.toString(), DUMMY.toString(),
                    PLAY_REVIEWER.toString()));

    static String codesJson() {
        return """
                [
                  {"label":"judge-1","codeHash":"%s","userId":"%s"},
                  {"label":"judge-2","codeHash":"%s","userId":"%s"},
                  {"label":"judge-3","codeHash":"%s","userId":"%s","revoked":true}
                ]""".formatted(JUDGE_1_HASH, JUDGE_1, JUDGE_2_HASH, JUDGE_2, JUDGE_3_HASH, JUDGE_3);
    }

    static ReviewAccountPolicy offPolicy() {
        return policy(true, EXPIRES_AT, codesJson(), at(EXPIRY.plusSeconds(1)));
    }

    static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    static ReviewAccountPolicy policy(boolean enabled, String expiresAt, String codes, Clock clock) {
        return policy(enabled, ROUND, expiresAt, codes, clock);
    }

    static ReviewAccountPolicy policy(boolean enabled, String round, String expiresAt, String codes, Clock clock) {
        return new ReviewAccountPolicy(ACCOUNTS, new ReviewLoginProperties(enabled, round, expiresAt, codes), clock);
    }

    static ReviewAccountPolicy onPolicy() {
        return policy(true, EXPIRES_AT, codesJson(), at(EXPIRY.minus(Duration.ofDays(3))));
    }

    // =====================================================================
    @Nested
    @DisplayName("심사 기능 스위치")
    class Switch {

        @Test
        @DisplayName("enabled 이고 expiresAt 전이면 켜져 있다")
        void onBeforeExpiry() {
            assertThat(policy(true, EXPIRES_AT, codesJson(), at(EXPIRY.minusSeconds(1))).isSwitchOn()).isTrue();
        }

        @Test
        @DisplayName("expiresAt 이 되는 순간부터 꺼진다 — 평가 마지막 날 다음 날 00:00 KST")
        void offAtAndAfterExpiry() {
            assertThat(policy(true, EXPIRES_AT, codesJson(), at(EXPIRY)).isSwitchOn()).isFalse();
            assertThat(policy(true, EXPIRES_AT, codesJson(), at(EXPIRY.plusSeconds(1))).isSwitchOn()).isFalse();
        }

        @Test
        @DisplayName("enabled=false 면 만료 전이어도 꺼진다 — 긴급 차단")
        void disabledIsOff() {
            assertThat(policy(false, EXPIRES_AT, codesJson(), at(EXPIRY.minus(Duration.ofDays(3)))).isSwitchOn())
                    .isFalse();
        }

        @Test
        @DisplayName("코드가 없으면 expiresAt 이 없어도 꺼진 채 기동한다 — 기본 설정")
        void missingExpiryWithoutCodesIsOff() {
            assertThat(policy(true, null, null, at(EXPIRY.minus(Duration.ofDays(3)))).isSwitchOn()).isFalse();
            assertThat(policy(true, "", "", at(EXPIRY.minus(Duration.ofDays(3)))).isSwitchOn()).isFalse();
        }

        @Test
        @DisplayName("enabled=false 면 expiresAt 이 없어도 기동한다 — 긴급 차단 재배포가 기동 실패로 막히면 안 된다")
        void disabledWithoutExpiryStillBoots() {
            assertThat(policy(false, null, codesJson(), at(EXPIRY.minus(Duration.ofDays(3)))).isSwitchOn()).isFalse();
            assertThat(policy(false, "", codesJson(), at(EXPIRY.minus(Duration.ofDays(3)))).isSwitchOn()).isFalse();
        }

        @Test
        @DisplayName("설정이 통째로 비어 있으면 꺼져 있고 코드도 없다 — 기본값이라 평소 동작이 달라지지 않는다")
        void emptyConfigIsOff() {
            ReviewAccountPolicy policy = policy(false, null, null, null, Clock.systemUTC());

            assertThat(policy.isSwitchOn()).isFalse();
            assertThat(policy.match("K7QM-4XRD-9WTF")).isEmpty();
            assertThat(policy.codeAccountIds()).isEmpty();
        }

        @Test
        @DisplayName("expiresAt 은 부트 때 한 번이 아니라 부를 때마다 현재 시각과 비교한다 — 재배포 없이 자동 종료")
        void expiryIsEvaluatedPerCall() {
            MovableClock clock = new MovableClock(EXPIRY.minusSeconds(60));
            ReviewAccountPolicy policy = policy(true, EXPIRES_AT, codesJson(), clock);
            assertThat(policy.isSwitchOn()).isTrue();

            clock.now = EXPIRY.plusSeconds(1);

            assertThat(policy.isSwitchOn()).isFalse();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("코드 대조")
    class Match {

        @Test
        @DisplayName("정규화한 코드의 해시가 목록에 있으면 그 계정과 label 을 돌려준다")
        void matchesNormalizedCode() {
            var matched = onPolicy().match("k7qm-4xrd 9wtf");

            assertThat(matched).isPresent();
            assertThat(matched.get().userId()).isEqualTo(JUDGE_1);
            assertThat(matched.get().label()).isEqualTo("judge-1");
        }

        @Test
        @DisplayName("코드마다 다른 계정이다 — 1인 1계정")
        void eachCodeMapsToItsOwnAccount() {
            assertThat(onPolicy().match("A2B3-C4D5-E6F7")).get()
                    .extracting(ReviewLoginCode::userId).isEqualTo(JUDGE_2);
        }

        @Test
        @DisplayName("목록에 없는 코드·빈 값·null 은 맞지 않는다")
        void unknownOrBlankDoesNotMatch() {
            ReviewAccountPolicy policy = onPolicy();

            assertThat(policy.match("ZZZZ-ZZZZ-ZZZZ")).isEmpty();
            assertThat(policy.match("")).isEmpty();
            assertThat(policy.match("   ")).isEmpty();
            assertThat(policy.match(null)).isEmpty();
        }

        @Test
        @DisplayName("해시 값 자체를 코드로 넣어도 맞지 않는다 — 비교 대상은 입력의 해시다")
        void hashItselfIsNotACode() {
            assertThat(onPolicy().match(JUDGE_1_HASH)).isEmpty();
        }

        @Test
        @DisplayName("코드 계정은 codes 의 모든 항목이다 — 폐기된 항목 포함, 더미 멤버·Play 심사 계정은 빠진다(종료 대상)")
        void codeAccountsAreOnlyMappedOnes() {
            assertThat(onPolicy().codeAccountIds()).containsExactlyInAnyOrder(JUDGE_1, JUDGE_2, JUDGE_3);
        }

        @Test
        @DisplayName("폐기된 코드로는 로그인되지 않는다 — 목록에 없는 코드와 같다")
        void revokedCodeDoesNotMatch() {
            assertThat(onPolicy().match("Q8W9-E2R3-T4Y5")).isEmpty();
            assertThat(onPolicy().revokedCodeAccountIds()).containsExactly(JUDGE_3);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("세션 종료 판정 — 재발급·종료 스케줄러가 함께 쓴다")
    class SessionEnded {

        @Test
        @DisplayName("스위치가 켜져 있고 코드가 살아 있으면 세션을 유지한다")
        void liveWhileOn() {
            assertThat(onPolicy().isCodeSessionEnded(JUDGE_1)).isFalse();
        }

        @Test
        @DisplayName("스위치가 꺼지면 모든 코드 계정의 세션이 끝난다")
        void endedWhenOff() {
            ReviewAccountPolicy off = offPolicy();

            assertThat(off.isCodeSessionEnded(JUDGE_1)).isTrue();
            assertThat(off.isCodeSessionEnded(JUDGE_3)).isTrue();
        }

        @Test
        @DisplayName("코드가 폐기되면 스위치가 켜져 있어도 그 계정의 세션이 끝난다 — 유출 대응")
        void endedWhenCodeRevoked() {
            assertThat(onPolicy().isCodeSessionEnded(JUDGE_3)).isTrue();
            assertThat(onPolicy().isCodeSessionEnded(JUDGE_2)).isFalse();
        }

        @Test
        @DisplayName("코드 계정이 아니면 꺼져도 끝나지 않는다 — Play 심사 계정·더미 멤버·일반 유저")
        void othersNeverEnd() {
            ReviewAccountPolicy off = offPolicy();

            assertThat(off.isCodeSessionEnded(PLAY_REVIEWER)).isFalse();
            assertThat(off.isCodeSessionEnded(DUMMY)).isFalse();
            assertThat(off.isCodeSessionEnded(NORMAL_USER)).isFalse();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("심사 회차 — 이번 회차 계정만 코드 로그인 대상")
    class ReviewRound {

        @Test
        @DisplayName("subject 의 회차가 round 와 같으면 이번 회차 계정이다 — 탈퇴했든 아니든")
        void sameRoundSubjectIsCurrent() {
            assertThat(onPolicy().isCurrentRoundAccount("ruleup-review-2026110101-01")).isTrue();
            assertThat(onPolicy().isCurrentRoundAccount("ruleup-review-2026110101-12")).isTrue();
        }

        @Test
        @DisplayName("다른 회차·회차 없는 옛 형식·접두만 겹치는 값·일반 구글 subject·빈 값은 이번 회차 계정이 아니다")
        void otherSubjectsAreNotCurrent() {
            ReviewAccountPolicy policy = onPolicy();

            assertThat(policy.isCurrentRoundAccount("ruleup-review-2026050101-01")).as("지난 회차").isFalse();
            assertThat(policy.isCurrentRoundAccount("ruleup-review-2026110102-01")).as("같은 날 다음 순번").isFalse();
            assertThat(policy.isCurrentRoundAccount("ruleup-review-01")).as("회차 없는 옛 형식").isFalse();
            assertThat(policy.isCurrentRoundAccount("ruleup-review-20261101010-01")).as("회차가 접두만 겹침").isFalse();
            assertThat(policy.isCurrentRoundAccount("ruleup-demo-01")).as("더미 멤버").isFalse();
            assertThat(policy.isCurrentRoundAccount("109876543210987654321")).as("일반 구글 subject").isFalse();
            assertThat(policy.isCurrentRoundAccount("")).isFalse();
            assertThat(policy.isCurrentRoundAccount(null)).isFalse();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("기동 검증")
    class BootValidation {

        @Test
        @DisplayName("codes 의 userId 가 REVIEW_ACCOUNT_IDS 에 없으면 기동을 중단한다 — 일반 유저에게 코드 로그인이 열리는 사고")
        void codeForNonReviewAccountAbortsBoot() {
            String codes = """
                    [{"label":"judge-x","codeHash":"%s","userId":"%s"}]""".formatted(JUDGE_1_HASH, NORMAL_USER);

            assertThatThrownBy(() -> policy(true, EXPIRES_AT, codes, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("judge-x");
        }

        @Test
        @DisplayName("스위치가 꺼져 있어도 매핑 검사는 한다 — 켜는 순간 사고가 나면 안 된다")
        void validatesEvenWhenDisabled() {
            String codes = """
                    [{"label":"judge-x","codeHash":"%s","userId":"%s"}]""".formatted(JUDGE_1_HASH, NORMAL_USER);

            assertThatThrownBy(() -> policy(false, EXPIRES_AT, codes, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("codes 를 읽을 수 없으면 기동을 중단한다 — 조용히 무시하면 심사 당일에야 안다")
        void malformedCodesAbortBoot() {
            assertThatThrownBy(() -> policy(true, EXPIRES_AT, "[{not json", Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("켜 둔 채 코드가 있는데 expiresAt 이 없으면 기동을 중단한다 — 꺼진 것으로 보면 종료 스케줄러가 기동 직후 코드 계정을 탈퇴시킨다")
        void enabledCodesWithoutExpiryAbortBoot() {
            // 준비해 둔 방에서 나가고 방장이 봇으로 바뀌는 일이 설정 한 줄 누락으로 일어난다 — 로그인만 막히는 게 아니다.
            for (String missing : java.util.Arrays.asList(null, "", "  ")) {
                assertThatThrownBy(() -> policy(true, missing, codesJson(), Clock.systemUTC()))
                        .as(String.valueOf(missing)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("expiresAt");
            }
        }

        @Test
        @DisplayName("expiresAt 형식이 틀리면 기동을 중단한다")
        void malformedExpiryAbortsBoot() {
            assertThatThrownBy(() -> policy(true, "next friday", codesJson(), Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("코드가 있는데 round 가 없으면 기동을 중단한다 — 회차를 가를 수 없으면 지난 계정이 열린다")
        void missingRoundAbortsBoot() {
            assertThatThrownBy(() -> policy(true, null, EXPIRES_AT, codesJson(), Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("round");
            assertThatThrownBy(() -> policy(true, " ", EXPIRES_AT, codesJson(), Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("round");
        }

        @Test
        @DisplayName("round 는 yyyyMMdd + 순번 2자리만 받는다 — 하이픈·짧은 값·없는 날짜는 기동 중단")
        void malformedRoundAbortsBoot() {
            for (String bad : List.of("26-11", "2611", "t1", "20261101", "202611010", "2026113201", "2026-11-01-01")) {
                assertThatThrownBy(() -> policy(true, bad, EXPIRES_AT, codesJson(), Clock.systemUTC()))
                        .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("round");
            }
        }

        @Test
        @DisplayName("round 날짜가 expiresAt 보다 늦거나 같으면 기동을 중단한다")
        void roundAfterExpiryAbortsBoot() {
            assertThatThrownBy(() -> policy(true, "2026111501", EXPIRES_AT, codesJson(), Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("round");
        }

        @Test
        @DisplayName("지난 설정을 복사해 expiresAt 만 늦추면 기동을 중단한다 — round 날짜에서 최대 회차 길이를 넘는다")
        void reusedOldRoundWithLaterExpiryAbortsBoot() {
            // 9월에 시드한 회차의 round 를 그대로 두고 만료만 11월 중순으로 — 60일을 넘는다
            assertThatThrownBy(() -> policy(true, "2026090101", EXPIRES_AT, codesJson(), Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("round");
        }

        @Test
        @DisplayName("round 날짜부터 최대 회차 길이 안의 만료는 받는다 — 평가 연장은 그 안에서 expiresAt 만 늦춘다")
        void expiryWithinMaxReviewLengthIsAccepted() {
            // 2026-09-16 KST 시드 → 2026-11-15 KST 만료 = 60일
            assertThat(policy(true, "2026091601", EXPIRES_AT, codesJson(), Clock.systemUTC()).isCurrentRoundAccount(
                    "ruleup-review-2026091601-01")).isTrue();
        }

        @Test
        @DisplayName("같은 해시가 두 계정에 묶여 있으면 기동을 중단한다 — 한 코드로 누구에게 들어갈지 정할 수 없다")
        void duplicateHashAbortsBoot() {
            String codes = """
                    [
                      {"label":"judge-1","codeHash":"%s","userId":"%s"},
                      {"label":"judge-2","codeHash":"%s","userId":"%s"}
                    ]""".formatted(JUDGE_1_HASH, JUDGE_1, JUDGE_1_HASH, JUDGE_2);

            assertThatThrownBy(() -> policy(true, EXPIRES_AT, codes, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("폐기된 항목의 계정에 살아 있는 코드를 또 묶으면 기동을 중단한다 — 새 코드는 예비 계정에 묶는다")
        void liveCodeOnRevokedAccountAbortsBoot() {
            // 세션은 어느 코드로 열렸는지 모른다 — 같은 계정이면 새 코드로 연 세션까지 폐기 판정에 끊긴다.
            String codes = """
                    [
                      {"label":"judge-3","codeHash":"%s","userId":"%s","revoked":true},
                      {"label":"judge-3b","codeHash":"%s","userId":"%s"}
                    ]""".formatted(JUDGE_3_HASH, JUDGE_3, JUDGE_2_HASH, JUDGE_3);

            assertThatThrownBy(() -> policy(true, EXPIRES_AT, codes, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("judge-3b");
        }
    }

    static final class MovableClock extends Clock {
        volatile Instant now;

        MovableClock(Instant now) { this.now = now; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
