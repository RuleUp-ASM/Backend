package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.RoomDtos;
import com.ruleup.ruleup_backend.room.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 그룹 랭킹 + 방 홈(방 내부기능 §7.3). ACTIVE 멤버 전용. */
@Tag(name = "Challenge Room", description = "그룹 랭킹 · 방 홈 일괄 조회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @Operation(summary = "그룹 랭킹", description = "ACTIVE 멤버 progressRate desc→successDays desc→joinedAt asc. myRank(gapToAbove) 포함.")
    @GetMapping("/ranking")
    public ApiResponse<RoomDtos.RankingResponse> ranking(@AuthenticationPrincipal String userId,
                                                         @PathVariable UUID challengeId) {
        return ApiResponse.ok(roomService.ranking(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "방 홈 일괄 조회", description = "요약 + 고정 공지 + 미읽음 수 + 상위 3 + 내 오늘 상태 + myRole. ACTIVE 멤버 전용.")
    @GetMapping("/room")
    public ApiResponse<RoomDtos.RoomResponse> room(@AuthenticationPrincipal String userId,
                                                   @PathVariable UUID challengeId) {
        return ApiResponse.ok(roomService.room(UUID.fromString(userId), challengeId));
    }
}
