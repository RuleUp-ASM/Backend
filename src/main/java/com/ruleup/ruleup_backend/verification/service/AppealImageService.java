package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 이의 증빙 사진 업로드 (POST /api/v1/appeals/images).
 *
 * <p>크기·매직넘버만 검사한다. 사진은 진위 확인에 쓰지 않으므로 콘텐츠 심사를 걸지 않는다 —
 * 이의 인용은 사진과 무관하게 형식 요건만으로 결정된다.
 */
@Service
@RequiredArgsConstructor
public class AppealImageService {

    private final ImageStorageService imageStorageService;

    public String upload(MultipartFile file) {
        ImageStorageService.ValidatedImage img = imageStorageService.validate(file);
        return imageStorageService.urlOf(imageStorageService.storeValidated(img));
    }
}
