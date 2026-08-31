package com.ruleup.ruleup_backend.me.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.invitation.InvitationService;
import com.ruleup.ruleup_backend.me.dto.CalendarDayResponse;
import com.ruleup.ruleup_backend.me.dto.CalendarMonthResponse;
import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import com.ruleup.ruleup_backend.me.dto.MeInvitationResponse;
import com.ruleup.ruleup_backend.me.dto.MeStatsResponse;
import com.ruleup.ruleup_backend.me.dto.MeTierHistoryResponse;
import com.ruleup.ruleup_backend.me.dto.MeTierResponse;
import com.ruleup.ruleup_backend.me.service.MeCalendarService;
import com.ruleup.ruleup_backend.me.service.MeHomeService;
import com.ruleup.ruleup_backend.me.service.MeStatsService;
import com.ruleup.ruleup_backend.me.service.MeTierHistoryService;
import com.ruleup.ruleup_backend.me.service.MeTierService;
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

/**
 * 마이 탭 조회 API. base=/api/v1/me. 모두 로그인 필요.
 *
 * <p>전부 <b>읽기 전용 조립</b>이다 — 판정도 점수 계산도 하지 않고 인증·티어 모듈이 쌓아둔 값을
 * 화면 단위로 엮을 뿐이다. 그래서 같은 사실이 화면마다 다르게 보이면 안 된다(단일 원천 원칙).
 */
@Tag(name = "Me", description = "마이 홈 · 캘린더 · 통계 · 티어")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final MeHomeService homeService;
    private final MeCalendarService calendarService;
    private final MeStatsService statsService;
    private final MeTierService tierService;
    private final MeTierHistoryService tierHistoryService;
    private final InvitationService invitationService;

    @Operation(summary = "마이 홈 일괄 조회",
            description = """
                    마이 탭 메인을 1회 호출로 그린다 — 닉네임·사진(+심사 상태), 티어 3종,
                    진행 중/완주/이탈 카운트, 계정 상태.

                    본인 화면이므로 닉네임·사진은 심사 상태와 무관하게 **입력값**을 보여주고,
                    타인에게 지금 어떻게 보이는지는 status 뱃지로 판단한다.
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/home")
    public ApiResponse<MeHomeResponse> home(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(homeService.home(UUID.fromString(userId)));
    }

    @Operation(summary = "월 활동 캘린더",
            description = """
                    월 단위 일자별 요약. **확정 전 2일치는 늦게 도착한 신호로 값이 뒤집힐 수 있으므로**
                    캐시하지 않고 실시간으로 조합한다.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_CALENDAR_MONTH, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/calendar")
    public ApiResponse<CalendarMonthResponse> calendar(@AuthenticationPrincipal String userId,
                                                       @RequestParam String month) {
        return ApiResponse.ok(calendarService.month(UUID.fromString(userId), month));
    }

    @Operation(summary = "일자 상세",
            description = """
                    그날의 챌린지별 판정 결과. **실패·실패 예정 건에는 `appeal` 이 함께 실린다** —
                    별도 조회 없이 이의 버튼의 활성 여부와 기한을 결정할 수 있다.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_CALENDAR_DATE, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/calendar/{date}")
    public ApiResponse<CalendarDayResponse> calendarDay(@AuthenticationPrincipal String userId,
                                                        @PathVariable String date) {
        return ApiResponse.ok(calendarService.day(UUID.fromString(userId), date));
    }

    @Operation(summary = "통계 리포트",
            description = """
                    정책 지표 **4종 고정** — 전체 성공률 / 총 성공 인증 수 / 현재·최고 스트릭 / 완주 개수.
                    기간 파라미터가 없다(구 WEEKLY·MONTHLY·YEARLY 폐기).

                    확정된 판정만 센다. 유예 구간(귀속일+2일 00:00 KST 이전)의 건은 아직 반영되지 않는다.
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/stats")
    public ApiResponse<MeStatsResponse> stats(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(statsService.stats(UUID.fromString(userId)));
    }

    @Operation(summary = "내 티어 상세",
            description = """
                    실제 티어·점수와 **표시 티어**를 분리해 내린다. 점수는 티어마다 0~99 로 끊지 않는
                    계정당 단일 축(0~2,000)이라 승급해도 초과 점수가 사라지지 않는다.

                    강등에만 20점의 유예가 있다 — 표시 티어 시작점보다 1~20점 낮은 동안은 표시 티어를
                    유지하고, 21점 이상 낮아져야 강등이 확정된다. **방 게이팅도 표시 티어로 본다.**
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/tier")
    public ApiResponse<MeTierResponse> tier(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(tierService.tier(UUID.fromString(userId)));
    }

    @Operation(summary = "티어 히스토리",
            description = """
                    월말 스냅샷 그래프의 원천. 정책이 **그래프 형식 + 하락 사유 표기 없음**으로 정해
                    시리즈와 역대 최고만 내린다. 보관 1년 — 그 이전 이력은 조회되지 않는다.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_HISTORY_MONTHS, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/tier/history")
    public ApiResponse<MeTierHistoryResponse> tierHistory(@AuthenticationPrincipal String userId,
                                                          @RequestParam(required = false) Integer months) {
        return ApiResponse.ok(tierHistoryService.history(UUID.fromString(userId), months));
    }

    @Operation(summary = "친구 초대", description = "내 초대 코드/딥링크(유저당 1개, 멱등 생성) + 초대 현황(피초대 가입).")
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/invitation")
    public ApiResponse<MeInvitationResponse> invitation(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(invitationService.myInvitation(UUID.fromString(userId)));
    }
}
