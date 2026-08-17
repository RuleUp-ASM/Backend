package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.CrossRankingDtos;
import com.ruleup.ruleup_backend.room.service.CrossRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 방 밖 랭킹 — 챌린지끼리의 순위. */
@Tag(name = "Challenge Ranking", description = "방 밖 랭킹 — 같은 모드(GROUP/SOLO)끼리 챌린지를 비교한다. 하루 1회 03시 갱신")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/rankings/challenges")
@RequiredArgsConstructor
public class CrossRankingController {

    private final CrossRankingService service;

    @Operation(
            summary = "방 밖 랭킹",
            description = """
                    사람이 아니라 **챌린지끼리** 비교하는 순위다. 그룹 방은 그룹끼리, 솔로 방은 솔로끼리만 겨루므로
                    `mode` 는 필수다 — 인원 규모가 다른 방을 한 표에 올리면 순위가 의미를 잃는다.

                    **진행 중인 방만** 대상이며 기준은 방 전체 성공률이다. 등재 조건은 모드마다 다르다 —
                    **그룹 50회 · 솔로 10회** 이상 누적 판정이 있어야 순위에 들어온다.

                    수치는 **하루 1회 03시 배치**로 갱신된 스냅샷이다. 실시간이 아니므로 오늘 인증한 결과가 곧바로
                    반영되지 않는다. `updatedAt` 이 그 스냅샷 시각이니 화면에 함께 표시하면 오해가 줄어든다
                    (방 **안** 랭킹은 실시간이라 두 화면의 값이 다를 수 있다).

                    `challengeId` 를 넘기면 그 방의 순위를 `myChallenge` 로 함께 돌려준다 — 목록을 끝까지 넘기지
                    않아도 "내 방은 몇 등"을 보여줄 수 있다. 미등재면 `ranked:false`, `rank:null` 이다.

                    페이징은 커서 방식이며 `nextCursor` 가 null 이면 마지막 페이지다.
                    """
    )
    @ApiErrorCodes({ErrorCode.INVALID_RANKING_MODE, ErrorCode.CURSOR_INVALID, ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<CrossRankingDtos.Response> get(
            @Parameter(description = "비교 대상 모드. GROUP 또는 SOLO.", example = "GROUP", required = true)
            @RequestParam String mode,

            @Parameter(description = "내 방 순위를 함께 받고 싶을 때의 방 id. 생략하면 myChallenge 는 null 이다.")
            @RequestParam(required = false) UUID challengeId,

            @Parameter(description = "직전 응답의 nextCursor. 첫 페이지는 생략한다.")
            @RequestParam(required = false) String cursor,

            @Parameter(description = "한 페이지 개수", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.get(mode, challengeId, cursor, size));
    }
}
