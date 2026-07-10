package com.ruleup.ruleup_backend.common.image;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 프로필 이미지 로컬 저장 (W1). 크기·매직넘버 검증 후 파일로 저장하고 파일명을 반환한다.
 */
@Service
public class ImageStorageService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;   // 10MB

    private final Path uploadDir;

    public ImageStorageService(@Value("${app.upload.dir:./uploads}") String dir) {
        this.uploadDir = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new IllegalStateException("업로드 디렉터리 생성 실패: " + uploadDir, e);
        }
    }

    /**
     * 검증 통과한 이미지 바이트 + 확장자. "검증"과 "저장"을 분리해, 그 사이에 동기 모더레이션
     * (SafeSearch §9) 을 끼울 수 있게 한다.
     */
    public record ValidatedImage(byte[] bytes, String ext) {
        public String mimeType() {
            return "png".equals(ext) ? "image/png" : "image/jpeg";
        }
    }

    /** 크기·매직넘버 검증만(저장 X). 실패 시 413/415/400. */
    public ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.IMAGE_CORRUPTED);
        if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.IMAGE_CORRUPTED);
        }
        String ext = detectExtension(bytes);                 // 매직넘버로 판별 (헤더 신뢰 X)
        return new ValidatedImage(bytes, ext);
    }

    /** 검증된 바이트를 디스크에 저장하고 파일명 반환. */
    public String storeValidated(ValidatedImage image) {
        String filename = UuidGenerator.generate() + "." + image.ext();
        try {
            Files.write(uploadDir.resolve(filename), image.bytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.IMAGE_CORRUPTED);
        }
        return filename;
    }

    /** 정적 서빙 URL(/files/{파일명}) 생성. */
    public String urlOf(String filename) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/files/").path(filename).toUriString();
    }

    public String store(MultipartFile file) {
        return storeValidated(validate(file));
    }

    /** 파일 앞부분 바이트(매직넘버)로 jpg/png 판별. 그 외엔 거부. */
    private String detectExtension(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF)
            return "jpg";                                     // JPEG: FF D8 FF
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47)
            return "png";                                     // PNG: 89 50 4E 47
        throw new BusinessException(ErrorCode.IMAGE_INVALID_TYPE);
    }
}