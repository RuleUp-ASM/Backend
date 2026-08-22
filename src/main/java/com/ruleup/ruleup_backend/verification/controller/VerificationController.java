package com.ruleup.ruleup_backend.verification.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.verification.dto.ChallengeProgress;
import com.ruleup.ruleup_backend.verification.dto.ProgressListResponse;
import com.ruleup.ruleup_backend.verification.dto.SyncRequest;
import com.ruleup.ruleup_backend.verification.dto.SyncResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationAckResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationCancelResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroRequest;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse;
import com.ruleup.ruleup_backend.verification.service.VerificationAckService;
import com.ruleup.ruleup_backend.verification.service.VerificationIntroService;
import com.ruleup.ruleup_backend.verification.service.VerificationManualService;
import com.ruleup.ruleup_backend.verification.service.VerificationReadService;
import com.ruleup.ruleup_backend.verification.service.VerificationSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** 챌린지에 매이지 않는 인증 API. base = /api/v1/verifications. */
@Tag(name = "인증 구현", description = "인증 신호 전송 · 판정 결과 확인 · 수동 인증 취소 · 진행률")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/verifications")
@RequiredArgsConstructor
public class VerificationController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final VerificationSyncService syncService;
    private final VerificationReadService readService;
    private final VerificationIntroService introService;
    private final VerificationAckService ackService;
    private final VerificationManualService manualService;

    @Operation(summary = "인증 신호 전송",
            description = """
                    클라가 버퍼에 모아둔 인증 신호를 **주기적으로 일괄 전송**하는 통로.
                    주기는 서버가 내리는 `flushIntervalSec`(기본 30분)를 따른다.
                    도착한 신호로 자동 인증을 평가하고 진행률을 갱신한다(셋업 READY 멤버만 평가).

                    - **신호는 압축·요약 없이 원본 그대로** 보낸다. 클라가 계산한 집계값은 판정 입력으로 쓰지 않는다.
                    - **전송 구간을 선언한다(필수)** — `coveredFrom`/`coveredUntil`은 "이 구간의 신호를 빠짐없이 담았다"는
                      선언이다. 이게 없으면 서버는 "신호가 없다"와 "아직 안 왔다"를 구분할 수 없어 `INVALID_SIGNAL_PAYLOAD` 다.
                    - **장기 오프라인 복귀분은 `backlog=true`** 로 보낸다 — 레이트리밋에 별도 허용치가 적용돼 복구가 막히지 않는다.
                      판정에 쓰이는 최근 48시간 구간을 먼저, 그보다 오래된 기록용 구간은 낮은 순위로 보낸다.
                    - **양이 많으면 구간을 쪼개 여러 번** 호출한다. 순번·마지막 플래그는 없고 구간으로만 합쳐지므로
                      순서가 바뀌거나 일부가 유실돼도 안전하다.
                    - **보낼 신호가 없어도 sync는 친다** — 대신 `gaps[]`에 공백 사유를 담는다.
                    - 멱등은 신호 단위(`recordId` 우선). 요청 gzip(`Content-Encoding: gzip`) 지원(선택).

                    `SYNC_PAYLOAD_TOO_LARGE` 를 받으면 구간을 반으로 쪼개 재전송한다.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_SIGNAL_PAYLOAD, ErrorCode.LOGIN_REQUIRED,
            ErrorCode.SYNC_PAYLOAD_TOO_LARGE, ErrorCode.SYNC_TOO_FREQUENT})
    @PostMapping("/sync")
    public ApiResponse<SyncResponse> sync(@AuthenticationPrincipal String userId,
                                          @RequestBody SyncRequest request) {
        return ApiResponse.ok(syncService.sync(UUID.fromString(userId), request));
    }

    @Operation(summary = "판정 결과 확인",
            description = """
                    판정 결과 모달을 **봤다는 확인**. 호출하면 이후 `today` 응답에서 `unacknowledgedResult`가 사라진다.
                    **멱등** — 중복 호출 안전.
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.VERIFICATION_NOT_FOUND})
    @PostMapping("/{verificationId}/ack")
    public ApiResponse<VerificationAckResponse> acknowledge(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "판정 ID — today 응답의 unacknowledgedResult.verificationId")
            @PathVariable UUID verificationId) {
        return ApiResponse.ok(ackService.acknowledge(UUID.fromString(userId), verificationId));
    }

    @Operation(summary = "수동 인증 취소",
            description = """
                    수동 체크 **취소** — "당일 마감" 정책의 취소 경로. 취소하면 그 날짜는 다시 `IN_PROGRESS`로 돌아간다.

                    자동 판정 건은 취소할 수 없고(`NOT_MANUAL_VERIFICATION`), 해당 날짜(KST)가 지나면
                    `CANCEL_WINDOW_CLOSED` 다.
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.VERIFICATION_NOT_FOUND,
            ErrorCode.NOT_MANUAL_VERIFICATION, ErrorCode.CANCEL_WINDOW_CLOSED})
    @DeleteMapping("/{verificationId}")
    public ApiResponse<VerificationCancelResponse> cancel(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "인증 건 ID — 수동 인증 제출 응답의 verificationId")
            @PathVariable UUID verificationId) {
        return ApiResponse.ok(manualService.cancel(UUID.fromString(userId), verificationId));
    }

    @Operation(summary = "Phase 0 인트로",
            description = "디바이스 프로필·권한 스냅샷 수신 → sync 정책(flush 주기·신호 cadence·backoff·sessionId) 회신.")
    @PostMapping("/intro")
    public ApiResponse<VerificationIntroResponse> intro(@AuthenticationPrincipal String userId,
                                                        @RequestBody VerificationIntroRequest request) {
        return ApiResponse.ok(introService.resolve(UUID.fromString(userId), request));
    }

    @Operation(summary = "진행률 일괄 조회",
            description = "내 챌린지 진행률을 한 번에. 홈/리스트 렌더용. status=ACTIVE(기본)/ALL. "
                    + "응답은 {asOf, challenges[]} 봉투.")
    @GetMapping("/progress")
    public ApiResponse<ProgressListResponse> progress(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "ACTIVE(기본) / ALL") @RequestParam(defaultValue = "ACTIVE") String status) {
        List<ChallengeProgress> challenges = readService.progress(UUID.fromString(userId), status);
        String asOf = ZonedDateTime.ofInstant(Instant.now(), KST)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return ApiResponse.ok(new ProgressListResponse(asOf, challenges));
    }
}
