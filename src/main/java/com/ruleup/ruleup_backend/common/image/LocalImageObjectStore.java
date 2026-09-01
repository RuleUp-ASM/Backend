package com.ruleup.ruleup_backend.common.image;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;

/**
 * 로컬 디스크 저장 — 개발·테스트 기본값.
 *
 * <p><b>운영에서는 쓰지 않는다.</b> Fargate 태스크의 디스크는 임시 저장소라 배포·재시작마다
 * 통째로 사라진다. 여기 올린 프로필 사진은 다음 배포에서 없어지고, 사용자에게는 사진이
 * 저절로 지워진 것으로 보인다. 그래서 stg 부터는 {@link S3ImageObjectStore} 를 쓴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "local", matchIfMissing = true)
public class LocalImageObjectStore implements ImageObjectStore {

    private final Path uploadDir;

    public LocalImageObjectStore(@Value("${app.upload.dir:./uploads}") String dir) {
        this.uploadDir = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new IllegalStateException("업로드 디렉터리 생성 실패: " + uploadDir, e);
        }
    }

    @Override
    public void put(String filename, byte[] bytes, String contentType) {
        try {
            Files.write(resolve(filename), bytes);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.IMAGE_CORRUPTED);
        }
    }

    @Override
    public void delete(String filename) {
        try {
            Files.deleteIfExists(resolve(filename));
        } catch (IOException e) {
            log.warn("로컬 이미지 삭제 실패 filename={}: {}", filename, e.toString());
        }
    }

    /** 로컬은 정적 핸들러가 직접 서빙하므로 넘길 주소가 없다. */
    @Override
    public Optional<String> directUrl(String filename, Duration ttl) {
        return Optional.empty();
    }

    /**
     * 파일명이 업로드 디렉터리를 벗어나지 못하게 한다.
     *
     * <p>파일명은 서버가 UUID 로 만들지만, 삭제 경로는 <b>DB 에 저장된 URL 에서 되꺼낸 값</b>을
     * 받는다. 그 값이 어떤 경로로든 오염되면 디렉터리 밖 파일을 지우게 되므로 여기서 한 번 막는다.
     */
    private Path resolve(String filename) {
        Path resolved = uploadDir.resolve(filename).normalize();
        if (!resolved.startsWith(uploadDir)) throw new BusinessException(ErrorCode.IMAGE_CORRUPTED);
        return resolved;
    }
}
