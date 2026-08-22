package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.error.ErrorResponse;
import com.ruleup.ruleup_backend.verification.service.MonthlyChangeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앵커·측정 대상 앱 변경의 월 1회 한도 (인증 구현 API 명세).
 *
 *  - "저장 1회"가 단위이고 리셋은 <b>매월 1일 00:00 KST</b>다. 마지막 변경 +30일 같은 상대 기간이 아니다.
 *  - 첫 설정(POST /setup)은 소진하지 않는다 — 그래서 판정 기준이 "마지막 저장"이 아니라 "마지막 변경"이다.
 *  - 한도에 걸린 429 는 다음 변경 가능 시각을 <b>응답 본문에 함께</b> 실어야 한다.
 *    클라가 그 값으로 "다음 달 1일부터 바꿀 수 있어요"를 바로 띄우기 때문.
 */
class SettingChangeLimitTest {

    /** 2026-08-22 12:00 KST. */
    private static final Instant AUG_22 = Instant.parse("2026-08-22T03:00:00Z");

    @Test
    @DisplayName("변경 이력이 없으면(첫 설정만 한 상태) 이번 달 변경이 가능하다")
    void firstChangeIsAlwaysAvailable() {
        assertThat(MonthlyChangeLimit.available(null, AUG_22)).isTrue();
        assertThat(MonthlyChangeLimit.nextChangeAvailableAtOrNull(null, AUG_22)).isNull();
    }

    @Test
    @DisplayName("같은 달에 이미 바꿨으면 소진 — 30일이 안 지났어도 다음 달 1일까지 막힌다")
    void sameMonthChangeIsExhausted() {
        Instant changedThisMonth = Instant.parse("2026-08-01T00:30:00Z");   // 8/1 09:30 KST

        assertThat(MonthlyChangeLimit.available(changedThisMonth, AUG_22)).isFalse();
        assertThat(MonthlyChangeLimit.nextChangeAvailableAtOrNull(changedThisMonth, AUG_22))
                .isEqualTo("2026-09-01T00:00:00+09:00");
    }

    @Test
    @DisplayName("지난달에 바꿨으면 하루밖에 안 지났어도 다시 가능하다 — 리셋은 달 경계다")
    void previousMonthChangeIsResetOnMonthBoundary() {
        Instant lastDayOfJuly = Instant.parse("2026-07-31T14:00:00Z");      // 7/31 23:00 KST
        Instant firstDayOfAugust = Instant.parse("2026-08-01T01:00:00Z");   // 8/1 10:00 KST — 11시간 뒤

        assertThat(MonthlyChangeLimit.available(lastDayOfJuly, firstDayOfAugust)).isTrue();
    }

    @Test
    @DisplayName("KST 기준으로 달을 가른다 — UTC로 보면 아직 지난달인 시각도 KST로는 이번 달이다")
    void monthBoundaryFollowsKst() {
        // 2026-07-31T16:00Z = 2026-08-01 01:00 KST → KST 기준 8월
        Instant kstAugustButUtcJuly = Instant.parse("2026-07-31T16:00:00Z");

        assertThat(MonthlyChangeLimit.available(kstAugustButUtcJuly, AUG_22)).isFalse();
    }

    @Test
    @DisplayName("429 응답 본문에 다음 변경 가능 시각이 실린다 — reason 없이 실어도 사라지지 않는다")
    void limitErrorCarriesNextChangeAvailableAt() {
        BusinessException e = BusinessException.settingChangeLimit("2026-09-01T00:00:00+09:00");

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SETTING_CHANGE_LIMIT);
        assertThat(e.getDetail()).isNull();

        ErrorResponse body = ErrorResponse.of(e);
        assertThat(body.code()).isEqualTo("SETTING_CHANGE_LIMIT");
        assertThat(body.nextChangeAvailableAt()).isEqualTo("2026-09-01T00:00:00+09:00");
        assertThat(body.reason()).isNull();            // 없는 부가 필드는 직렬화에서 빠진다
        assertThat(body.rejoinAvailableAt()).isNull();
    }

    @Test
    @DisplayName("그 코드가 아니면 부가 필드는 실리지 않는다")
    void otherCodesCarryNoExtras() {
        ErrorResponse body = ErrorResponse.of(new BusinessException(ErrorCode.INVALID_ANCHOR));

        assertThat(body.nextChangeAvailableAt()).isNull();
        assertThat(body.rejoinAvailableAt()).isNull();
    }
}
