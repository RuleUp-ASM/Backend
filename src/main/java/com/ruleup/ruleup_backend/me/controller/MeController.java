package com.ruleup.ruleup_backend.me.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.me.dto.CalendarDayResponse;
import com.ruleup.ruleup_backend.me.dto.CalendarMonthResponse;
import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import com.ruleup.ruleup_backend.me.service.MeCalendarService;
import com.ruleup.ruleup_backend.me.service.MeHomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 마이프로필(마이 탭) API. base=/api/v1/me. 모두 로그인 필요. */
@Tag(name = "Me", description = "마이 홈 · 캘린더 · 통계 · 평판 · 초대")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final MeHomeService homeService;
    private final MeCalendarService calendarService;

    @Operation(summary = "마이 홈 일괄 조회", description = "닉네임·검수상태·프로필이미지·매너온도 + 카운트(완주·진행·그룹).")
    @GetMapping("/home")
    public ApiResponse<MeHomeResponse> home(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(homeService.home(UUID.fromString(userId)));
    }

    @Operation(summary = "월 활동 캘린더", description = "월 단위 일자별 day status(과거=RoutineOutcome / 당일=VerificationDaily). 판정 대상일만.")
    @GetMapping("/calendar")
    public ApiResponse<CalendarMonthResponse> calendar(@AuthenticationPrincipal String userId,
                                                       @RequestParam String month) {
        return ApiResponse.ok(calendarService.month(UUID.fromString(userId), month));
    }

    @Operation(summary = "일자 상세", description = "특정 일자의 챌린지별 결과(status/verifiedVia/verifiedAt/failureReason).")
    @GetMapping("/calendar/{date}")
    public ApiResponse<CalendarDayResponse> calendarDay(@AuthenticationPrincipal String userId,
                                                        @PathVariable String date) {
        return ApiResponse.ok(calendarService.day(UUID.fromString(userId), date));
    }
}
