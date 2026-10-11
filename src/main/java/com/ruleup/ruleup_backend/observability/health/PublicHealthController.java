package com.ruleup.ruleup_backend.observability.health;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 공개 점검(EDGE-01)·Route 53 백스톱(NATIVE-01)이 두드리는 무인증 경로 — 노션 「알람 카탈로그」 §4.
 *
 * <p>점검이 확인하려는 것은 <b>사용자와 같은 길(DNS → Cloudflare → ALB → 태스크)로 응답이 오는가</b>다.
 * 그래서 DB·Redis 는 묻지 않는다 — 내부 의존성 장애는 SLI·자원 경보가 따로 보고, 여기서 함께 보면
 * Redis 하나가 흔들릴 때 「공개 경로 전면 장애」 P0 가 같이 울린다. 내부 상태도 내리지 않는다.
 *
 * <p>본문은 고정 토큰 {@value #BODY} 이고 {@code no-store} 다. CDN 이 캐시한 200 을 받으면 서버가 죽어도 점검이
 * 통과하므로, 점검 쪽은 본문 토큰까지 맞아야 성공으로 친다(Route 53 HTTPS_STR_MATCH 도 같은 문자열).
 * SLI 에는 들어가지 않는다 — CUJ Operation 이 아니다.
 */
@RestController
public class PublicHealthController {

    public static final String PATH = "/health/public";
    public static final String BODY = "ruleup-ok";

    @GetMapping(PATH)
    public ResponseEntity<String> publicHealth() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_PLAIN)
                .body(BODY);
    }
}
