package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.challenge.service.ChallengeImageService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.image.ImageStorageService;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 챌린지 이미지 SafeSearch 동기 게이트(§9) — Docker 없이.
 *  - 검증(형식)이 검수보다 먼저.
 *  - REJECTED → 422 IMAGE_REJECTED + 디스크에 저장 안 함.
 *  - APPROVED → 저장 + URL 반환.
 *  - UNAVAILABLE → 업로드 허용(fail-open) + 저장.
 */
class ChallengeImageServiceTest {

    @TempDir Path tempDir;

    private FakeModerationClient moderation;
    private ChallengeImageService service;

    /** 결과를 제어하고 호출 여부를 기록하는 가짜 검수 클라이언트. */
    static class FakeModerationClient implements ContentModerationClient {
        ModerationResult imageResult = ModerationResult.APPROVED;
        boolean imageBytesCalled = false;
        public ModerationResult moderateNickname(String n) { return ModerationResult.APPROVED; }
        public ModerationResult moderateImage(String url) { return ModerationResult.APPROVED; }
        public ModerationResult moderateImageBytes(byte[] b, String mime) {
            imageBytesCalled = true;
            return imageResult;
        }
    }

    @BeforeEach
    void setUp() {
        ImageStorageService storage = new ImageStorageService(tempDir.toString());
        moderation = new FakeModerationClient();
        service = new ChallengeImageService(storage, moderation);
        // URL 빌더(ServletUriComponentsBuilder)가 동작하도록 요청 컨텍스트 설정.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockMultipartFile png() {
        // PNG 매직넘버(89 50 4E 47) + 패딩
        byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        return new MockMultipartFile("image", "a.png", "image/png", bytes);
    }

    private long storedFileCount() throws IOException {
        try (var s = Files.list(tempDir)) { return s.count(); }
    }

    @Test
    @DisplayName("REJECTED → 422 IMAGE_REJECTED, 디스크에 저장 안 함")
    void rejected_blocks_and_does_not_store() throws IOException {
        moderation.imageResult = ModerationResult.REJECTED;

        assertThatThrownBy(() -> service.upload(png()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_REJECTED);

        assertThat(storedFileCount()).isZero();
    }

    @Test
    @DisplayName("APPROVED → 저장 + /files/ URL 반환")
    void approved_stores_and_returns_url() throws IOException {
        moderation.imageResult = ModerationResult.APPROVED;

        String url = service.upload(png());

        assertThat(url).contains("/files/").endsWith(".png");
        assertThat(storedFileCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("UNAVAILABLE(AI 보류) → 업로드 허용(fail-open) + 저장")
    void unavailable_is_fail_open() throws IOException {
        moderation.imageResult = ModerationResult.UNAVAILABLE;

        String url = service.upload(png());

        assertThat(url).contains("/files/");
        assertThat(storedFileCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("형식 검증이 검수보다 먼저 — 잘못된 형식은 검수 호출 없이 415")
    void validation_precedes_moderation() {
        byte[] notImage = new byte[]{0, 1, 2, 3, 4, 5};
        MockMultipartFile bad = new MockMultipartFile("image", "a.txt", "text/plain", notImage);

        assertThatThrownBy(() -> service.upload(bad))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_INVALID_TYPE);

        assertThat(moderation.imageBytesCalled).isFalse();
    }
}
