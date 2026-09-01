package com.ruleup.ruleup_backend.common.image;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;

/**
 * 이미지 업로드 — 크기·매직넘버 검증 후 저장하고 <b>파일명</b>을 반환한다.
 *
 * <h4>저장소는 갈아끼우되 주소는 고정한다</h4>
 * 바이트가 어디에 놓이는지는 {@link ImageObjectStore} 가 정한다(로컬 디스크 / S3). 반면
 * 바깥 주소는 저장소와 무관하게 {@code /files/{파일명}} 으로 고정이다.
 *
 * <p>주소를 고정하는 이유가 핵심이다. 이 값은 응답으로만 나가는 게 아니라
 * {@code users.profile_image_url} · {@code challenges.image_url} ·
 * {@code challenge_image_uploads.image_url} 에 <b>저장되고</b>, 챌린지 생성 때 클라이언트가
 * 되돌려 보내면 서버가 <b>문자열 일치로 업로드 소유를 확인</b>한다. 주소가 저장소 사정으로
 * 바뀌면 그 순간 기존 이미지가 전부 죽고 소유 검증도 깨진다 — 저장소 교체가 곧 데이터
 * 마이그레이션이 되는 구조는 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ImageStorageService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;   // 10MB

    /** 저장소와 무관하게 고정인 서빙 경로. 이 값이 바뀌면 저장된 URL 이 전부 죽는다. */
    static final String PATH_PREFIX = "/files/";

    private final ImageObjectStore store;

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

    /** 검증된 바이트를 저장하고 파일명 반환. 파일명은 UUID v7 이라 추측할 수 없다. */
    public String storeValidated(ValidatedImage image) {
        String filename = UuidGenerator.generate() + "." + image.ext();
        store.put(filename, image.bytes(), image.mimeType());
        return filename;
    }

    /**
     * 저장된 이미지를 지운다. 저장된 <b>URL</b> 을 받아 파일명을 되꺼낸다 — 호출부(모더레이션
     * 거부·업로드 정리)가 들고 있는 값이 URL 이기 때문이다.
     *
     * <p>우리 {@code /files/} 경로가 아닌 주소는 조용히 무시한다. 외부 URL 이 섞여 들어와도
     * 삭제 대상이 아니고, 여기서 예외를 던지면 정리 배치가 그 한 건에 막힌다.
     */
    public void deleteByUrl(String url) {
        filenameOf(url).ifPresent(store::delete);
    }

    /** {@code .../files/{파일명}} 에서 파일명만 꺼낸다. 형식이 다르면 empty. */
    public java.util.Optional<String> filenameOf(String url) {
        if (url == null || url.isBlank()) return java.util.Optional.empty();
        int marker = url.indexOf(PATH_PREFIX);
        if (marker < 0) return java.util.Optional.empty();
        String filename = url.substring(marker + PATH_PREFIX.length());
        int query = filename.indexOf('?');
        if (query >= 0) filename = filename.substring(0, query);
        // 경로 구분자가 남아 있으면 우리가 만든 파일명이 아니다 — 되꺼내지 않는다.
        if (filename.isBlank() || filename.contains("/")) return java.util.Optional.empty();
        return java.util.Optional.of(filename);
    }

    /** 서빙 URL({@code /files/{파일명}}) 생성 — 저장소가 무엇이든 같은 형식이다. */
    public String urlOf(String filename) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(PATH_PREFIX).path(filename).toUriString();
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