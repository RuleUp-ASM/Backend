package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 챌린지 대표 이미지 업로드 + SafeSearch 동기 게이트 (CLAUDE.md §9, API: POST /challenges/image).
 *
 * 처리 순서(에러 우선순위 = API 문서):
 *   1) 크기/형식/손상 검증 — 413 IMAGE_TOO_LARGE / 415 IMAGE_INVALID_TYPE / 400 IMAGE_CORRUPTED
 *   2) SafeSearch 동기 검수 — 명백 위반(성인·폭력 등)은 422 IMAGE_REJECTED 로 동기 차단(저장 안 함)
 *   3) 저장 후 URL 반환
 *
 * - 검수 불가(AI 미설정/실패 = UNAVAILABLE)는 업로드를 막지 않는다(fail-open). 경계값/보류분은
 *   챌린지 비동기 검수 게이트가 사후 재검(§5.1) — 가입/생성 핫패스를 외부 호출 실패로 깨지 않기 위함.
 * - 저장 "전에" 검수해 위반 이미지를 디스크에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeImageService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeImageService.class);

    private final ImageStorageService imageStorageService;
    private final ContentModerationClient moderationClient;

    public String upload(MultipartFile file) {
        ImageStorageService.ValidatedImage img = imageStorageService.validate(file);

        ModerationResult result = moderationClient.moderateImageBytes(img.bytes(), img.mimeType());
        if (result == ModerationResult.REJECTED) {
            throw new BusinessException(ErrorCode.IMAGE_REJECTED);
        }
        if (result == ModerationResult.UNAVAILABLE) {
            log.info("챌린지 이미지 SafeSearch 보류(업로드 허용, 비동기 게이트 재검 대상)");
        }

        return imageStorageService.urlOf(imageStorageService.storeValidated(img));
    }
}
