package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.*;
import com.ruleup.ruleup_backend.challenge.service.ChallengeService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.routine.dto.RoutineRecommendationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import com.ruleup.ruleup_backend.common.image.UploadRateLimiter;
import com.ruleup.ruleup_backend.challenge.service.ChallengeRecommendationService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeImageService;

import java.util.UUID;

@Tag(name = "Challenge", description = "챌린지 추천 · 생성 · 조회 · 수정 · 삭제")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeRecommendationService challengeRecommendationService;
    private final ChallengeService challengeService;
    private final ChallengeImageService challengeImageService;
    private final UploadRateLimiter uploadRateLimiter;

    @Operation(summary = "챌린지 추천", description = "제목/설명을 루틴 템플릿과 매칭해 인증·목표값을 추천하고, "
            + "참여방식·일정·패널티·보상 기본값까지 얹어 전체 챌린지 초안을 돌려준다(저장 X). 권한은 보지 않는다.")
    @PostMapping("/recommendation")
    public ApiResponse<ChallengeRecommendationResponse> recommend(@RequestBody RoutineRecommendationRequest request) {
        return ApiResponse.ok(challengeRecommendationService.recommend(request));
    }

    @Operation(summary = "챌린지 생성", description = "추천을 수정·확정한 최종값으로 생성. RECRUITING으로 저장하고 생성자를 OWNER로 등록.")
    @PostMapping
    public ApiResponse<ChallengeResponse> create(@AuthenticationPrincipal String userId,
                                                 @RequestBody CreateChallengeRequest request) {
        return ApiResponse.ok(challengeService.create(UUID.fromString(userId), request));
    }

    @Operation(summary = "챌린지 대표 이미지 업로드",
            description = "jpg/png, 최대 10MB. 업로드 시 SafeSearch 동기 검수로 명백 위반은 422 IMAGE_REJECTED 차단. "
                    + "반환된 imageUrl을 생성/수정 요청 body의 imageUrl에 넣는다.")
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ChallengeImageResponse> uploadImage(
            @AuthenticationPrincipal String userId,
            @RequestPart("image") MultipartFile image) {
        uploadRateLimiter.check(userId);
        return ApiResponse.ok(new ChallengeImageResponse(challengeImageService.upload(image)));
    }

    @Operation(summary = "챌린지 전체 조회", description = "탐색/목록용. 모더레이션 APPROVED·미삭제만 노출. "
            + "category(선택)·status(선택, 기본 RECRUITING+ACTIVE) 필터, page/size 페이지네이션(최신순).")
    @GetMapping
    public ApiResponse<ChallengeListResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(challengeService.list(category, status, page, size));
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

    @Operation(summary = "챌린지 삭제",
            description = "생성자만, 소프트 삭제. §5.8 판정 순서: OWNER → 나 외 ACTIVE 멤버(CHALLENGE_HAS_MEMBERS) "
                    + "→ 잠금(생성 7일 이내·기간 7일 미만 DELETE_LOCKED) → 차감 계산 후 삭제. mannerPenalty 반환.")
    @DeleteMapping("/{challengeId}")
    public ApiResponse<DeleteChallengeResponse> delete(@AuthenticationPrincipal String userId,
                                                       @PathVariable String challengeId) {
        return ApiResponse.ok(challengeService.delete(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "추천 선택 → 초안(LLM 우회)", description = "추천 버튼에서 고른 templateId로 챌린지 초안을 바로 구성. LLM 안 거침.")
    @PostMapping("/recommendation/by-template")
    public ApiResponse<ChallengeRecommendationResponse> recommendByTemplate(@RequestBody TemplateRecommendationRequest request) {
        return ApiResponse.ok(challengeRecommendationService.recommendByTemplate(
                request.templateId(), request.title(), request.description()));
    }
}