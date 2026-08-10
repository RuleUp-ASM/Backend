package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.NoticeDtos;
import com.ruleup.ruleup_backend.room.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 챌린지 공지 API(방 내부기능 §7.1~7.2). base=/api/v1/challenges/{challengeId}/notices. */
@Tag(name = "Challenge Notice", description = "챌린지 공지(방장 작성·고정 · 멤버 조회/읽음)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "공지 목록", description = "고정 우선 → 최신순 최근 10건. 항목별 isRead. ACTIVE 멤버 전용.")
    @GetMapping
    public ApiResponse<NoticeDtos.ListResponse> list(@AuthenticationPrincipal String userId,
                                                     @PathVariable UUID challengeId,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(noticeService.list(UUID.fromString(userId), challengeId, cursor, size));
    }

    @Operation(summary = "공지 작성", description = "방장 전용. pinned=true면 기존 고정 자동 해제(단일 pin). ACTIVE 멤버(작성자 제외) 인앱 알림.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoticeDtos.CreateResponse> create(@AuthenticationPrincipal String userId,
                                                         @PathVariable UUID challengeId,
                                                         @RequestBody NoticeDtos.CreateRequest request) {
        return ApiResponse.ok(noticeService.create(UUID.fromString(userId), challengeId, request));
    }

    @Operation(summary = "공지 상세", description = "부작용 없는 순수 조회. 읽음 상태는 관리하지 않는다.")
    @GetMapping("/{noticeId}")
    public ApiResponse<NoticeDtos.DetailResponse> detail(@AuthenticationPrincipal String userId,
                                                         @PathVariable UUID challengeId,
                                                         @PathVariable UUID noticeId) {
        return ApiResponse.ok(noticeService.detail(UUID.fromString(userId), challengeId, noticeId));
    }

    @Operation(summary = "공지 수정", description = "방장 전용. 수정 시 푸시를 재발송하지 않는다.")
    @PutMapping("/{noticeId}")
    public ApiResponse<NoticeDtos.EditResponse> edit(@AuthenticationPrincipal String userId,
                                                     @PathVariable UUID challengeId,
                                                     @PathVariable UUID noticeId,
                                                     @RequestBody NoticeDtos.EditRequest request) {
        return ApiResponse.ok(noticeService.edit(UUID.fromString(userId), challengeId, noticeId, request));
    }

    @Operation(summary = "공지 삭제", description = "방장 전용. 소프트 삭제 + 읽음 정리.")
    @DeleteMapping("/{noticeId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal String userId,
                                    @PathVariable UUID challengeId,
                                    @PathVariable UUID noticeId) {
        noticeService.delete(UUID.fromString(userId), challengeId, noticeId);
        return ApiResponse.ok();
    }

    @Operation(summary = "공지 고정/해제", description = "방장 전용. pinned=true면 기존 고정 자동 해제(교체). unpinnedNoticeId 반환.")
    @PatchMapping("/{noticeId}/pin")
    public ApiResponse<NoticeDtos.PinResponse> pin(@AuthenticationPrincipal String userId,
                                                   @PathVariable UUID challengeId,
                                                   @PathVariable UUID noticeId,
                                                   @RequestBody NoticeDtos.PinRequest request) {
        return ApiResponse.ok(noticeService.pin(UUID.fromString(userId), challengeId, noticeId, request));
    }
}
