package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.*;
import com.ruleup.ruleup_backend.challenge.service.ChallengeService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import com.ruleup.ruleup_backend.common.image.UploadRateLimiter;

import java.util.UUID;

/**
 * 챌린지 API (조회/수정/삭제 + 이미지 업로드). 모두 로그인 필요.
 *  - 추천·생성은 루틴 플로우(/api/v1/routines)로 이관됨. 여기엔 생성 이후 라이프사이클만 남는다.
 */
@Tag(name = "Challenge", description = "챌린지 조회 · 수정 · 삭제")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeService challengeService;
    private final ImageStorageService imageStorageService;
    private final UploadRateLimiter uploadRateLimiter;

    @Operation(summary = "챌린지 생성", description = "추천을 수정·확정한 최종값으로 생성. RECRUITING으로 저장하고 생성자를 OWNER로 등록.")
    @PostMapping
    public ApiResponse<ChallengeResponse> create(@AuthenticationPrincipal String userId,
                                                 @RequestBody CreateChallengeRequest request) {
        return ApiResponse.ok(challengeService.create(UUID.fromString(userId), request));
    }

    @Operation(summary = "챌린지 대표 이미지 업로드",
            description = "jpg/png, 최대 10MB. 반환된 imageUrl을 생성/수정 요청 body의 imageUrl에 넣는다.")
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ChallengeImageResponse> uploadImage(
            @AuthenticationPrincipal String userId,
            @RequestPart("image") MultipartFile image) {
        uploadRateLimiter.check(userId);
        return ApiResponse.ok(new ChallengeImageResponse(imageStorageService.storeAndGetUrl(image)));
    }

    @Operation(summary = "챌린지 상세 + 참여 자격")
    @GetMapping("/{challengeId}")
    public ApiResponse<ChallengeDetailResponse> getDetail(@AuthenticationPrincipal String userId,
                                                          @PathVariable String challengeId) {
        return ApiResponse.ok(challengeService.getDetail(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "챌린지 수정", description = "시작 전(RECRUITING), 생성자만. 변경 필드만 보냄. 일정 변경 시 endDate 재파생.")
    @PatchMapping("/{challengeId}")
    public ApiResponse<ChallengeResponse> update(@AuthenticationPrincipal String userId,
                                                 @PathVariable String challengeId,
                                                 @RequestBody UpdateChallengeRequest request) {
        return ApiResponse.ok(challengeService.update(UUID.fromString(userId), UUID.fromString(challengeId), request));
    }

    @Operation(summary = "챌린지 삭제", description = "생성자만, 소프트 삭제(deleted_at 기록). 시작 전만 허용.")
    @DeleteMapping("/{challengeId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal String userId,
                                    @PathVariable String challengeId) {
        challengeService.delete(UUID.fromString(userId), UUID.fromString(challengeId));
        return ApiResponse.ok();
    }
}