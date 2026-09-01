package com.ruleup.ruleup_backend.common.image;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * {@code GET /files/{파일명}} — S3 모드의 이미지 서빙.
 *
 * <h4>왜 리다이렉트인가</h4>
 * 서버가 S3 에서 읽어 바이트를 그대로 흘려보낼 수도 있지만, 그러면 이미지 트래픽이 전부
 * 태스크를 거친다. 프로필 사진 한 장이 목록 한 화면에 수십 개씩 뜨는 구조라 대역폭과 스레드가
 * 그대로 소모된다. <b>302 로 presigned URL 을 넘기면 바이트는 S3 가 직접 준다.</b>
 *
 * <h4>왜 주소를 presigned 로 바로 내리지 않는가</h4>
 * presigned URL 은 만료된다. 그런데 이미지 주소는 {@code users.profile_image_url} ·
 * {@code challenges.image_url} 처럼 <b>DB 에 저장되고 클라이언트가 되돌려 보내는 값</b>이다.
 * 만료되는 값을 저장하면 하루 뒤에 전부 죽고, 왕복 검증(업로드 소유 확인)도 문자열이 매번
 * 달라져 성립하지 않는다. 그래서 <b>저장·왕복용 주소는 이 경로로 고정</b>하고, 만료되는 주소는
 * 매 요청 여기서 새로 만든다.
 *
 * <p>로컬 모드에서는 이 빈이 없고 {@code WebConfig} 의 정적 핸들러가 같은 경로를 맡는다.
 */
@Hidden   // 내부 서빙 경로 — API 문서에 노출하지 않는다
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "s3")
public class ImageFileController {

    private final ImageObjectStore store;

    /**
     * presigned URL 수명. 짧게 두는 이유는 유출 시 노출 창을 줄이기 위해서고, 너무 짧으면
     * 목록을 스크롤하는 동안 만료돼 이미지가 깨진다 — 5분이 그 사이다.
     */
    @Value("${app.upload.s3.presign-ttl-seconds:300}")
    private long presignTtlSeconds;

    @GetMapping("/files/{filename}")
    public ResponseEntity<Void> serve(@PathVariable String filename) {
        Duration ttl = Duration.ofSeconds(presignTtlSeconds);
        return store.directUrl(filename, ttl)
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, url)
                        // 프록시·CDN 이 리다이렉트를 캐시하면 만료된 링크를 계속 나눠 준다.
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
