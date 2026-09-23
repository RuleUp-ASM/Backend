package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUpload;
import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUploadRepository;
import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 챌린지 대표 이미지 업로드 (API: POST /challenges/image).
 *
 * 여기서는 형식·크기만 검사한다 — 413 IMAGE_TOO_LARGE / 415 IMAGE_INVALID_TYPE / 400 IMAGE_CORRUPTED.
 * 콘텐츠 심사는 이 URL 이 생성/수정으로 실제 등록되는 시점에 비동기로 돈다(구 동기 SafeSearch 게이트 폐기).
 * 업로드 소유를 기록해 생성/수정 API 가 임의 외부 URL·타 사용자 객체를 거절할 수 있게 한다.
 * 최종 등록되지 않은 업로드는 24시간 후 정리 배치가 삭제한다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeImageService {

    private final ImageStorageService imageStorageService;
    private final ChallengeImageUploadRepository uploadRepository;

    @Transactional
    public String upload(UUID userId, MultipartFile file) {
        ImageStorageService.ValidatedImage img = imageStorageService.validate(file);
        String url = imageStorageService.urlOf(imageStorageService.storeValidated(img));
        uploadRepository.save(ChallengeImageUpload.of(userId, url));
        return url;
    }
}
