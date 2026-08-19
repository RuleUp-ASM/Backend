package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.*;
import com.ruleup.ruleup_backend.challenge.creation.ChallengeCreationService;
import com.ruleup.ruleup_backend.challenge.settings.ChallengeSettingsService;
import com.ruleup.ruleup_backend.challenge.draft.ChallengeDraftService;
import com.ruleup.ruleup_backend.challenge.explore.ChallengeCloneService;
import com.ruleup.ruleup_backend.challenge.explore.ChallengeDetailQueryService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeService;
import com.ruleup.ruleup_backend.challenge.service.MyChallengeQueryService;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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
    private final MyChallengeQueryService myChallengeQueryService;
    private final ChallengeDetailQueryService detailQueryService;
    private final ChallengeCloneService cloneService;
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

    @Operation(
            summary = "내 챌린지 목록",
            description = """
                    홈의 참여 중 목록과 마이페이지의 진행 중 / 완료 / 이탈 탭이 **전부 이 API 하나**를 쓴다 —
                    `filter` 로 탭을 가른다. 승인제가 없으므로 대기 상태는 존재하지 않는다.

                    - `IN_PROGRESS`(기본) — 시작 전(UPCOMING) + 진행 중(ACTIVE)
                    - `COMPLETED` — 완주·기간 만료
                    - `LEFT` — 중도 탈퇴·강퇴·자동 탈퇴. 이 탭에서만 `leftType` · `leftAt` 이 채워진다.

                    무료 동시 참여가 3개라 진행 중 탭은 사실상 한 페이지지만 완료·이탈은 계속 쌓이므로
                    커서 페이지네이션을 지원한다. 정렬은 세 탭 공통으로 종료일 내림차순이다.

                    완료된 방은 배치가 하드 삭제한 뒤에도 이 목록에 계속 나온다 — 삭제 직전에 적재한 이력
                    스냅샷에서 읽기 때문이다.

                    제목·설명·이미지는 심사 상태별 대체 규칙이 적용된 값으로 온다 — 심사 중·거부면
                    제목은 AI 임시 제목, 설명은 null, 이미지는 null(기본 이미지)이다.

                    ### ⚠️ 알려진 제약 (레거시 — 지금 고치지 않는다)

                    **① 완료 탭은 필드 절반이 null 이라고 보고 그려야 한다.**
                    완료된 방은 매일 04:10 배치가 삭제하므로 길어야 하루만 살아 있고, 그 뒤로는
                    제목·이미지·카테고리·기간만 담긴 이력 스냅샷에서 읽힌다. 따라서 `description` ·
                    `mode` · `visibility` · `participantCount` · `capacity` · `minTier` ·
                    `weeklyCount` · `ownerType` 은 **사실상 항상 null** 이다. 값을 지어내면 사용자가
                    자기 기록을 오독하므로 비워 두는 쪽을 택했다 — 완료 카드는 제목·이미지·기간과
                    최종 랭킹만으로 그릴 것. 해소하려면 `challenge_history` 컬럼 추가(마이그레이션)가
                    선행돼야 하고, 적재가 삭제 직전 1회뿐이라 그 이전 삭제분은 소급 복구되지 않는다.

                    **② `leftType` 은 지금 `SELF` · `KICK_BY_OWNER` 두 값만 내려온다.**
                    저장 컬럼이 `enum('LEAVE','KICK')` 이라 그 이상을 구분할 수 없다. 나머지 5종은
                    자동 강퇴 배치를 위한 예약 값이며 **그 배치가 아직 없어 실제 발생 자체가 없다** —
                    값이 뭉개진 이력이 쌓이는 상황은 아니다. 배치를 붙일 때 저장 값부터 갈라야 한다.
                    """
    )
    @ApiErrorCodes({ErrorCode.INVALID_FILTER_VALUE, ErrorCode.CURSOR_INVALID, ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<ChallengeListResponse> myChallenges(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "탭 필터. 기본 IN_PROGRESS.",
                    schema = @Schema(allowableValues = {"IN_PROGRESS", "COMPLETED", "LEFT"}))
            @RequestParam(required = false) String filter,
            @Parameter(description = "이전 응답의 nextCursor. 미지정이면 첫 페이지.")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기. 기본 20, 최대 50.")
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(myChallengeQueryService.myChallenges(
                UUID.fromString(userId), filter, cursor, size));
    }

    @Operation(summary = "챌린지 공개 상세",
            description = "비참여자용 상세 — 조건·통계·입장 자격. 멤버 전용 내부 화면은 /room 이 담당한다. "
                    + "없는 방·비공개 방 비멤버·타인의 솔로 방은 전부 동일한 404(존재 은닉). "
                    + "심사 상태(moderation)는 방장 본인 조회 시에만 내려간다. "
                    + "참여 버튼 노출은 joined 로 판단한다 — joinBlockReason 은 종료가 최우선이라 "
                    + "종료된 방에서는 참여 중이어도 CHALLENGE_COMPLETED 가 내려간다.")
    @GetMapping("/{challengeId}")
    public ApiResponse<ChallengeDetailResponse> getDetail(@AuthenticationPrincipal String userId,
                                                          @PathVariable String challengeId) {
        return ApiResponse.ok(detailQueryService.detail(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "템플릿 복제해서 만들기",
            description = "공개 그룹 방만 복제 가능(비공개·솔로는 403). 원본 설정을 프리필한 초안을 발급하고 "
                    + "확인 화면·생성 API 를 그대로 재사용한다. 시작일은 생성일+1, mode·정원·티어는 생성 기본값으로 리셋, "
                    + "이미지는 복사하지 않는다. 출처(origin=CLONE)는 서버가 draft 행에 기록한다.")
    @PostMapping("/{challengeId}/clone")
    public ApiResponse<CloneResponse> clone(@AuthenticationPrincipal String userId,
                                            @PathVariable String challengeId) {
        return ApiResponse.ok(cloneService.clone(
                UUID.fromString(userId), UUID.fromString(challengeId)));
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

    @Operation(summary = "추천 선택 → 초안(경로 A, LLM 미경유)",
            description = "추천에서 고른 templateId로 템플릿 기본값 초안을 즉시 구성(대기 0초). "
                    + "draft 응답과 동일 스키마 + draftId(origin=TEMPLATE, 24시간 보관).")
    @PostMapping("/recommendation/by-template")
    public ApiResponse<TemplateDraftResponse> recommendByTemplate(@AuthenticationPrincipal String userId,
                                                                  @RequestBody TemplateRecommendationRequest request) {
        return ApiResponse.ok(challengeDraftService.byTemplate(UUID.fromString(userId), request.templateId()));
    }
}