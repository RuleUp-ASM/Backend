package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.ThreadDtos;
import com.ruleup.ruleup_backend.room.service.ThreadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/** 방 내부 메인을 채우는 스레드 피드. Phase 1 은 인증 이벤트 전용. */
@Tag(name = "Challenge Room", description = "방 홈 일괄 조회 · 인증 이벤트 스레드 · 방 안 랭킹 — 전부 ACTIVE 멤버 전용")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/threads")
@RequiredArgsConstructor
public class ThreadController {

    private final ThreadService service;

    @Operation(
            summary = "방 스레드 피드",
            description = """
                    멤버들의 인증 판정 결과가 시간순(최신 먼저)으로 흐르는 피드다. ACTIVE 멤버 전용이다.

                    `type` 은 Phase 1 에서 두 가지다. 공지(`NOTICE`)는 Phase 2 로 이관돼 지금은 내려오지 않지만,
                    **판별자 필드는 그대로 유지**하므로 나중에 값이 하나 늘어도 파싱이 깨지지 않게 처리해두면 좋다.

                    - `VERIFY_SUCCESS` — 성공이 확정되면 **즉시** 뜬다. `streak`(연속 성공 일수)가 함께 온다.
                    - `VERIFY_FAIL` — **바로 뜨지 않는다.** 이의 가능 기간(1일)이 지난 뒤에만 공유되고,
                      그사이 이의가 인용되면 **영원히 공유되지 않는다.**

                    실패 아이템에서 `at` 은 **공유된 시각**이고 `failDate` 가 **실제로 실패한 날짜**다.
                    발생일보다 늦게 도착하므로 화면에는 `failDate` 를 써서 "○월 ○일 루틴을 실패했습니다"처럼
                    **과거형으로 날짜를 명시**해야 한다. `at` 으로 표시하면 이미 지난 실패가 오늘 일처럼 읽힌다.

                    **내가 차단한 사람의 이벤트도 목록에 남는다.** 대신 `user.blocked=true` 로 표시되고 닉네임은
                    임시 닉네임, `profileImageUrl` 은 null(기본 이미지)로 가려진다. 목록에서 빼지 않는 이유는
                    그 자리가 비면 피드에 구멍이 생겨 맥락이 무너지기 때문이다.

                    페이징은 커서 방식이다. 응답의 `nextCursor` 를 그대로 `cursor` 로 넘기고,
                    null 이면 마지막 페이지다. 형식이 깨진 커서는 400 `CURSOR_INVALID` 이며,
                    이때는 커서 없이 처음부터 다시 부른다.

                    가입 직후부터 첫 판정 전까지는 `items` 가 빈 배열인 것이 정상이다.
                    """
    )
    @ApiErrorCodes({ErrorCode.CURSOR_INVALID, ErrorCode.NOT_CHALLENGE_MEMBER,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<ThreadDtos.Response> get(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "방 id", required = true)
            @PathVariable UUID challengeId,

            @Parameter(description = "직전 응답의 nextCursor. 첫 페이지는 생략한다.")
            @RequestParam(required = false) String cursor,

            @Parameter(description = "한 페이지 개수. 기본 20 · 최대 50(초과분은 50으로 잘린다).", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.get(UUID.fromString(userId), challengeId, cursor, size));
    }
}
