package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.service.LocationPurgeService;
import com.ruleup.ruleup_backend.verification.service.SignalPartitionMaintainer;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GPS 파기 타이머와 판정 원본 수명이 <b>서로 어긋나지 않는지</b> (공통 5-6 · 백엔드 4-1-1).
 *
 * <p>두 스펙이 다른 말을 한다. 공통 문서는 GPS 좌표를 「확정 후 30일 예정」이라 적었고, 백엔드
 * 문서는 판정 원본을 「D+2 확정 직후 파티션째」 걷으라고 적었다. 둘을 그대로 코드에 옮기면
 * <b>좌표가 파기 타이머에 닿기 전에 파티션과 함께 사라진다</b> — 실제로 그 상태였다. 결과가
 * 같아 보이지만 다르다. 「지웠다」는 기록({@code purgedAt}) 없이 없어지므로, 파기 사실을
 * 증명해야 할 때 근거가 없다.
 *
 * <p>둘 중 짧은 쪽이 위치정보법의 목적 달성 시 즉시 파기 원칙에 맞으므로 그쪽을 택했다.
 * 이 테스트는 <b>나중에 누가 30일로 되돌리는 것을 막는 자물쇠</b>다.
 */
class GpsRetentionCoherenceTest {

    /** 지정하지 않은 값은 record 가 기본값으로 채운다. */
    private VerificationProperties properties(Integer gpsRetentionDays, Integer signalRetentionDays) {
        return new VerificationProperties(null, null, null, null, null,
                signalRetentionDays, null, gpsRetentionDays, null, null, false, false);
    }

    @Test
    @DisplayName("[P1] 기본값에서 GPS 파기가 원본 파티션 파기보다 먼저 온다")
    void gpsPurgeLandsBeforeThePartitionDrop() {
        VerificationProperties defaults = properties(null, null);

        assertThat(defaults.gpsRetentionDays())
                .as("파티션이 먼저 떨어지면 purgedAt 경로가 한 번도 돌지 않는다")
                .isLessThan(defaults.signalRetentionDays());
    }

    @Test
    @DisplayName("[P1] 파기 시각은 귀속일의 확정 경계로 정해진다 — 먼저 확정한 챌린지 기준이 아니다")
    void purgeTimeIsDerivedFromTheDayNotFromWhoConfirmedFirst() {
        LocalDate targetDate = LocalDate.of(2026, 9, 10);
        Instant boundary = VerificationDeadlines.finalizeAfter(targetDate);   // D+2 00:00 KST

        // 보관 0일 기본값이면 경계가 지나는 즉시 파기 대상이다.
        assertThat(purgeService(properties(null, null)).purgeAfterFor(targetDate))
                .as("위치 원본은 여러 챌린지가 공유한다 — 하나가 아침에 성공했다고 그날 좌표를 "
                        + "일찍 지우면 D+2 에 확정되는 다른 챌린지가 판정할 근거를 잃는다")
                .isEqualTo(boundary);

        assertThat(purgeService(properties(2, 5)).purgeAfterFor(targetDate))
                .isEqualTo(boundary.plus(java.time.Duration.ofDays(2)));
    }

    /**
     * 파기 시각 계산만 쓰는 순수 메서드라 협력자 없이 만든다 — DB·지표를 건드리지 않는다.
     */
    private LocationPurgeService purgeService(VerificationProperties props) {
        return new LocationPurgeService(null, props, null);
    }

    @Test
    @DisplayName("[P1] 보관 일수는 「날짜 수」다 — 3이면 오늘·어제·그제 셋")
    void retentionDaysMeansTheNumberOfDatesKept() {
        LocalDate today = LocalDate.of(2026, 9, 14);

        // today-3 을 경계로 쓰면 네 날짜가 남아 스펙의 「최대 30일」이 31일이 된다.
        assertThat(SignalPartitionMaintainer.oldestKept(today, 3))
                .isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(SignalPartitionMaintainer.oldestKept(today, 30))
                .as("이상탐지 입력은 스펙이 최대 30일로 못 박았다")
                .isEqualTo(today.minusDays(29));
    }

    @Test
    @DisplayName("[P1] 파기 타이머를 원본 수명보다 길게 잡으면 기동에서 막는다")
    void longerGpsRetentionThanRawIsRejectedAtStartup() {
        assertThatThrownBy(() -> properties(30, 3))
                .as("설정만으로 조용히 어긋나면 파기 경로가 죽은 것을 아무도 모른다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gps-retention-days");
    }

    @Test
    @DisplayName("[P1] 보관 기간을 늘리려면 원본 수명도 함께 늘려야 한다")
    void longerWindowRequiresRaisingBothTogether() {
        VerificationProperties widened = properties(20, 30);

        assertThat(widened.gpsRetentionDays()).isEqualTo(20);
        assertThat(widened.signalRetentionDays()).isEqualTo(30);
    }
}
