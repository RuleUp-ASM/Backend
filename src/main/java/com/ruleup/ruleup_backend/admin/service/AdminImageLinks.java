package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.common.image.ImageObjectStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 백오피스 이미지 열람 — <b>단기 presigned URL</b>로만 노출한다(백엔드 6절 · 공통 오픈 이슈 #5).
 *
 * <h4>복호화 주체 축소 원칙과의 접점</h4>
 * 법적·개인정보 처리정책 § 6 전략 5 는 복호화 허용 주체를 ECS 태스크 롤 하나로 제한하고 사람을
 * 배제한다. 백오피스가 신고 이미지를 보려면 <b>서비스 롤이 발급한 단기 링크</b>를 거쳐야 하며,
 * 사람에게 버킷 권한을 주는 방식과는 다르다. 발급 사실은 상세 열람 감사({@code SNAPSHOT_VIEW} ·
 * {@code INQUIRY_VIEW})에 이미 남는다.
 *
 * <h4>만료를 함께 내리는 이유</h4>
 * 링크는 만료된다. 만료 시각을 주지 않으면 콘솔은 이미지가 깨진 것과 만료된 것을 구분하지
 * 못하고, 운영자에게는 「이미지가 없는 신고」로 보인다.
 */
@Component
@RequiredArgsConstructor
public class AdminImageLinks {

    /** 검토 한 건을 보는 동안 살아 있으면 된다. 길게 두면 유출 시 노출 창이 그대로 늘어난다. */
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final String PATH_PREFIX = "/files/";

    private final ImageObjectStore store;

    /** 링크 묶음과 그 만료 시각. 이미지가 없으면 만료도 없다(null). */
    public record Links(List<String> urls, String expiresAt) {

        public static Links empty() {
            return new Links(List.of(), null);
        }
    }

    /**
     * 저장된 {@code /files/{파일명}} 주소를 단기 접근 주소로 바꾼다.
     *
     * <p>로컬 저장소는 직접 주소를 주지 않으므로 원래 값을 그대로 둔다 — 개발 환경에서 이미지가
     * 통째로 사라지면 화면을 확인할 수 없다.
     */
    public Links presign(List<String> storedUrls) {
        if (storedUrls == null || storedUrls.isEmpty()) return Links.empty();

        List<String> urls = new ArrayList<>(storedUrls.size());
        for (String stored : storedUrls) {
            if (stored == null || stored.isBlank()) continue;
            urls.add(direct(stored));
        }
        return urls.isEmpty() ? Links.empty()
                : new Links(urls, Instant.now().plus(TTL).toString());
    }

    private String direct(String stored) {
        int at = stored.lastIndexOf(PATH_PREFIX);
        if (at < 0) return stored;                       // 저장 형식이 아니면 손대지 않는다
        String filename = stored.substring(at + PATH_PREFIX.length());
        return store.directUrl(filename, TTL).orElse(stored);
    }
}
