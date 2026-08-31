package com.ruleup.ruleup_backend.sanction;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.sanction.dto.MySanctionsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 내 제재 이력 — 마이페이지 공통 5-2 #10.
 *
 * <p><b>본인 것만 조회하며 {@code userId} 를 받지 않는다.</b> 타인 조회 경로를 만들지 않는 것이
 * 권한 검사보다 확실한 방어다.
 */
@Tag(name = "Sanction", description = "내 제재 통지·이력 열람")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class MySanctionController {

    private final MySanctionService mySanctionService;

    @Operation(
            summary = "내 제재 이력 조회",
            description = """
                    제재 통지와 이력을 열람한다. **열람 전용**이며 이의 제기 버튼을 두지 않는다 —
                    강퇴는 CS 문의, 직권 제재는 CS 경유 재검토 1회로만 다툰다.

                    **자동 제재와 직권 제재를 별개 트랙으로** 내리며 합산하지 않는다.
                    누적 카운트로 승격하는 경로가 없기 때문이다.
                    - `admin` — 운영자 검토를 거친 계정 제재(`FEATURE_SUSPENSION` / `LOCK` / `BAN`)
                    - `auto` — 인증·티어 판정에서 발생한 챌린지 강퇴

                    `activeSanction` 은 현재 효력이 있는 제재이며 없으면 `null` 이다.
                    영구 정지의 `endsAt` 은 `null` — 해제일이 없다.

                    ⚠️ **잠금 상태에서도 접근 가능하다.** 계정 상태 게이트의 허용 화이트리스트에 들어 있다 —
                    잠금 사유와 해제일을 볼 수 없으면 사용자가 상황을 알 방법이 없다.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.ACCOUNT_BANNED})
    @GetMapping("/api/v1/users/me/sanctions")
    public ApiResponse<MySanctionsResponse> mySanctions(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(mySanctionService.of(UUID.fromString(userId)));
    }
}
