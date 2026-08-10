package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.*;
import com.ruleup.ruleup_backend.challenge.creation.ChallengeCreationService;
import com.ruleup.ruleup_backend.challenge.settings.ChallengeSettingsService;
import com.ruleup.ruleup_backend.challenge.draft.ChallengeDraftService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import com.ruleup.ruleup_backend.common.image.UploadRateLimiter;
import com.ruleup.ruleup_backend.challenge.service.ChallengeImageService;
import com.ruleup.ruleup_backend.challenge.recommendation.RecommendationRateLimiter;

import java.util.UUID;

@Tag(name = "Challenge", description = "챌린지 추천 · 생성 · 조회 · 수정 · 삭제")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeDraftService challengeDraftService;
    private final ChallengeCreationService challengeCreationService;
    private final ChallengeSettingsService challengeSettingsService;
    private final ChallengeService challengeService;
    private final ChallengeImageService challengeImageService;
    private final UploadRateLimiter uploadRateLimiter;
    private final RecommendationRateLimiter recommendationRateLimiter;

    @Operation(summary = "챌린지 초안(경로 B, LLM 5-Step)",
            description = "설명 1~200자만 입력하면 제목 포함 초안 전체를 AI가 생성. Step0 rate limit(1분 10회, 초과 429). "
                    + "Step1·2 차단은 200 result=FALLBACK(재입력 복귀). 초안 원본은 draftId로 24시간 보관(origin=AI).")
    @PostMapping("/draft")
    public ApiResponse<DraftResponse> draft(@AuthenticationPrincipal String userId,
                                            @RequestBody DraftRequest request) {
        recommendationRateLimiter.check(userId);   // Step0: 사용자당 1분 10회
        return ApiResponse.ok(challengeDraftService.draft(UUID.fromString(userId), request.description()));
    }

    @Operation(summary = "추천 3개(지금 시작하기 좋은 루틴)",
            description = "어떤 경우에도 3개 보장. 진행 중 카테고리 루틴 제외(기타 예외)하되 3개 보장이 우선. "
                    + "탭하면 by-template 호출(LLM 미경유).")
    @GetMapping("/recommendations")
    public ApiResponse<CreationRecommendationsResponse> recommendations(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(challengeDraftService.recommendations(UUID.fromString(userId)));
    }

    @Operation(summary = "챌린지 최종 생성",
            description = "확인 화면에서 수정을 마친 초안으로 생성. Idempotency-Key 헤더 필수(DB 유니크 — 재시도 안전). "
                    + "심사 대상은 서버가 draftId 원본 대조로 판정(자가 신고 없음). 201 + UPCOMING.")
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<CreateChallengeResponse> create(
            @AuthenticationPrincipal String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateChallengeRequest request) {
        return ApiResponse.ok(challengeCreationService.create(UUID.fromString(userId), idempotencyKey, request));
    }

    @Operation(summary = "챌린지 대표 이미지 업로드",
            description = "jpg/png, 최대 10MB. 형식·크기만 검사하고 콘텐츠 심사는 등록 시점 비동기. "
                    + "반환된 imageUrl은 본인 소유로 기록되며 생성/수정 body에 넣는다(타인·외부 URL 거절). "
                    + "미등록 업로드는 24시간 후 정리.")
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ChallengeImageResponse> uploadImage(
            @AuthenticationPrincipal String userId,
            @RequestPart("image") MultipartFile image) {
        uploadRateLimiter.check(userId);
        return ApiResponse.ok(new ChallengeImageResponse(
                challengeImageService.upload(UUID.fromString(userId), image)));
    }

    @Operation(summary = "내 챌린지 목록", description = "내가 참여 중인 챌린지 목록(멤버십 기준, 페이지네이션 없음). "
            + "승인제 폐기로 항상 확정 멤버십만. 항목마다 myRole 포함.")
    @GetMapping
    public ApiResponse<ChallengeListResponse> myChallenges(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(challengeService.myChallenges(UUID.fromString(userId)));
    }

    @Operation(summary = "챌린지 상세 + 참여 자격")
    @GetMapping("/{challengeId}")
    public ApiResponse<ChallengeDetailResponse> getDetail(@AuthenticationPrincipal String userId,
                                                          @PathVariable String challengeId) {
        return ApiResponse.ok(challengeService.getDetail(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "챌린지 설정 조회(방장 전용)",
            description = "수정 화면 진입용 — config 원본(심사 대체 미적용) + editableFields(서버 계산) + version + moderation.")
    @GetMapping("/{challengeId}/settings")
    public ApiResponse<ChallengeSettingsResponse> settings(@AuthenticationPrincipal String userId,
                                                           @PathVariable String challengeId) {
        return ApiResponse.ok(challengeSettingsService.settings(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "챌린지 수정(방장)",
            description = "JSON Merge Patch 유사 — 미포함 필드 미변경, null 은 imageUrl(기본 이미지 되돌리기)만 유효. "
                    + "version 필수(불일치 409). 시작 전+혼자=카테고리 제외 전부, 그 외=제목·설명·정원·이미지. "
                    + "제목·설명·이미지는 수정 시 재심사, 반복 거부 잠금 중 429.")
    @PatchMapping("/{challengeId}")
    public ApiResponse<PatchChallengeResponse> update(@AuthenticationPrincipal String userId,
                                                      @PathVariable String challengeId,
                                                      @RequestBody tools.jackson.databind.JsonNode body) {
        return ApiResponse.ok(challengeSettingsService.patch(UUID.fromString(userId), UUID.fromString(challengeId), body));
    }

    @Operation(summary = "챌린지 삭제",
            description = "OWNER만, 하드 삭제+감사 로깅. 참여자 0명일 때만. 판정: OWNER→참여자≥1(CHALLENGE_HAS_MEMBERS)"
                    + "→COMPLETED(CHALLENGE_COMPLETED)→시작전 무패널티/진행중 success 이력 시 패널티. penaltyApplied 반환.")
    @DeleteMapping("/{challengeId}")
    public ApiResponse<DeleteChallengeResponse> delete(@AuthenticationPrincipal String userId,
                                                       @PathVariable String challengeId) {
        return ApiResponse.ok(challengeService.delete(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "추천 선택 → 초안(경로 A, LLM 미경유)",
            description = "추천에서 고른 templateId로 템플릿 기본값 초안을 즉시 구성(대기 0초). "
                    + "draft 응답과 동일 스키마 + draftId(origin=TEMPLATE, 24시간 보관).")
    @PostMapping("/recommendation/by-template")
    public ApiResponse<TemplateDraftResponse> recommendByTemplate(@AuthenticationPrincipal String userId,
                                                                  @RequestBody TemplateRecommendationRequest request) {
        return ApiResponse.ok(challengeDraftService.byTemplate(UUID.fromString(userId), request.templateId()));
    }
}