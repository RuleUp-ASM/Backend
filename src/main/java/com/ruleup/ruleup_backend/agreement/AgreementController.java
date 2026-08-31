package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.agreement.dto.AgreementDtos;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 동의 상태 조회·제출 — 온보딩 테크 스펙 5-2 #11·#12 (2026-08-31 신설).
 *
 * <p>이 두 엔드포인트가 없어서 그동안 403 {@code AGREEMENT_REQUIRED}에 걸린 사용자는
 * 빠져나올 경로가 없었다. 약관 개정 재동의도 같은 경로를 쓴다.
 */
@Tag(name = "Agreement", description = "약관 5종 + 법정 개별 동의 2종의 상태 조회 · 제출 · 철회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/me/agreements")
@RequiredArgsConstructor
public class AgreementController {

    private final AgreementService agreementService;

    @Operation(
            summary = "동의 상태 조회",
            description = """
                    내 동의 **현재 상태**를 내린다. 약관 5종과 법정 개별 동의 2종을 같은 구조로 담고 `type` 으로 갈라낸다.

                    쓰임새는 둘이다.
                    - **약관 개정 재동의 판정** — 서버가 `reconsentRequired` 를 계산해 주므로 클라이언트가 버전을 비교할 필요가 없다.
                    - **설정 화면 토글 초기값** — 마케팅·이벤트 수신 동의가 알림 설정 기본값이 된다.

                    `required` 는 **가입 시** 필수 여부다. 개별 동의 2종은 가입 필수가 아니라 `false` 지만,
                    해당 인증 수단(위치·건강)을 쓰려면 필수다.

                    `agreed: false` 이고 `version: null` 이면 **한 번도 동의한 적 없음**이다.
                    `agreed: false` 인데 `version` 이 있으면 동의 후 철회한 것이다.

                    잠금(LOCKED) 계정도 조회할 수 있다 — 동의 상태는 잠금과 무관하게 유지된다.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<AgreementDtos.StatusResponse> status(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(agreementService.status(UUID.fromString(userId)));
    }

    @Operation(
            summary = "동의 제출·철회",
            description = """
                    동의를 제출하거나 철회한다. 세 경로가 이 하나를 공유한다.
                    - **법정 개별 동의 2종** — 위치·건강 인증 수단을 처음 쓰는 시점에 받는다.
                      403 `AGREEMENT_REQUIRED` 를 받은 뒤 동의 화면이 호출하는 것이 바로 이 API 다.
                    - **선택 약관 동의·철회** — 마케팅·이벤트 수신.
                    - **개정 약관 재동의** — `reconsentRequired` 에 있던 항목을 새 버전으로 다시 보낸다.

                    배열로 받아 **여러 항목을 한 번에** 처리한다 — 개정 약관이 동시에 여러 개 나올 수 있기 때문이다.
                    **전체가 한 트랜잭션**이므로 하나라도 실패하면 전부 롤백된다.

                    `version` 은 서버의 현재 유효 버전과 같아야 한다. 구 버전을 동의본으로 남기면 입증이 깨진다.

                    필수 약관 3종(이용약관·개인정보·위치기반)은 **철회할 수 없다**. 철회하려면 탈퇴해야 한다.

                    응답은 **갱신된 항목만** 담으며, `reconsentRequired` 는 처리 후 남은 재동의 항목이다.
                    """
    )
    @ApiErrorCodes({ErrorCode.AGREEMENT_REVOKE_FORBIDDEN, ErrorCode.AGREEMENT_VERSION_MISMATCH,
            ErrorCode.INVALID_REQUEST, ErrorCode.ACCOUNT_LOCKED, ErrorCode.LOGIN_REQUIRED})
    @PostMapping
    public ApiResponse<AgreementDtos.StatusResponse> submit(
            @AuthenticationPrincipal String userId,
            @RequestBody AgreementDtos.SubmitRequest request) {
        return ApiResponse.ok(agreementService.submit(UUID.fromString(userId), request));
    }
}
