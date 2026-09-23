package com.ruleup.ruleup_backend.challenge.calendar;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 챌린지 단위 월 캘린더 — 솔로 챌린지 상세 화면. */
@Tag(name = "Challenge", description = "챌린지")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class ChallengeCalendarController {

    private final ChallengeCalendarService calendarService;

    @Operation(
            summary = "챌린지 월 캘린더",
            description = """
                    챌린지 하나의 월 단위 판정 결과. 판정 대상일만 내려간다.

                    **`/me/calendar` 에 필터를 붙이지 않고 별도 엔드포인트로 둔 이유** — 계정 단위
                    캘린더의 `status` 는 여러 루틴을 합산한 값이라 `PARTIAL` 이 있는데, 한 챌린지로
                    좁히면 하루 판정 대상이 1건이라 그 값이 의미를 잃는다. 응답 스키마가 다르다.

                    상태값은 `DONE` / `FAILED` / `FAIL_EXPECTED`(유예 창) / `IN_PROGRESS`(오늘) 다.
                    귀속일 다음 날 00:00 ~ 이틀 뒤 00:00 KST 구간이 `FAIL_EXPECTED` 이고 최종 확정
                    후 완료·실패로 바뀐다.

                    `appealable` 은 캘린더에서 바로 이의를 걸 수 있는지다 — 실패·실패 예정이면서
                    기한 안이고 아직 신청하지 않은 건만 true 다.

                    **참여 중이 아니어도 완료·이탈한 챌린지의 내 기록은 조회된다.** 참여한 적이
                    없으면 403 이다.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_CALENDAR_MONTH, ErrorCode.NOT_CHALLENGE_MEMBER,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/api/v1/challenges/{challengeId}/calendar")
    public ApiResponse<ChallengeCalendarResponse> calendar(@AuthenticationPrincipal String userId,
                                                           @PathVariable String challengeId,
                                                           @RequestParam(required = false) String month) {
        return ApiResponse.ok(calendarService.month(
                UUID.fromString(userId), UUID.fromString(challengeId), month));
    }
}
