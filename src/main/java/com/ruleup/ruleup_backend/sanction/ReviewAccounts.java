package com.ruleup.ruleup_backend.sanction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 스토어 심사 계정을 <b>자동 제재에서만</b> 빼 준다.
 *
 * <h4>왜 필요한가</h4>
 * 룰업은 소셜 로그인 전용이라 심사자가 계정을 직접 만들 수 없다. 온보딩까지 끝낸 전용 계정을
 * 넘겨야 하는데, 정책을 그대로 적용하면 그 계정이 스스로 망가진다 — 심사자가 한 번 보고 나가면
 * 아무도 인증하지 않으므로 연속 실패가 쌓여 샘플 챌린지에서 강퇴되고, 30일이 지나면 휴면으로
 * 챌린지가 전부 정리되며, 1년이면 계정 자체가 사라진다. 다음 심사 때 계정을 열면 보여 줄 것이 없다.
 *
 * <h4>왜 표도 열도 아닌 설정인가</h4>
 * 어느 계정이 심사용인가는 <b>사용자의 속성이 아니라 배포 환경의 설정</b>이다. 심사가 끝나면
 * 사라지는 한시적 정보이고, 환경마다 다르며, 심사 반려로 급히 바꿔야 할 때가 가장 흔하다.
 * {@code users} 에 열을 두면 도메인 모델에 운영 관심사가 섞이고, 값을 바꾸려면 마이그레이션이
 * 아니라 해도 최소한 DB 를 건드려야 한다. 환경변수면 값만 바꿔 태스크를 새로 띄우면 끝이다 —
 * 코드 배포가 아니다.
 *
 * <p>소스에 상수로 박지 않는 이유도 같다. 계정을 바꿀 때마다 배포해야 하고, 사용자 UUID 가
 * git 이력에 영구히 남는다.
 *
 * <h4>한계</h4>
 * 만료가 없다. 지우기 전까지 면제가 유지되므로, 심사가 끝나면 환경변수에서 빼야 한다. 대신
 * 목록이 태스크 정의에 그대로 보이고, 면제가 실제로 걸릴 때마다 로그를 남긴다 — 「왜 이 계정만
 * 제재가 안 되는가」를 나중에 설명할 수 있어야 한다.
 *
 * <p>값이 비어 있으면 아무도 면제되지 않는다. 그것이 기본값이라, 이 클래스가 있다고 해서
 * 평소 동작이 달라지지 않는다.
 */
@Component
public class ReviewAccounts {

    private static final Logger log = LoggerFactory.getLogger(ReviewAccounts.class);

    private final Set<UUID> ids;

    public ReviewAccounts(@Value("${app.review-accounts:}") String raw) {
        this.ids = parse(raw);
        if (!ids.isEmpty()) {
            // 켜져 있다는 사실을 기동 로그에 남긴다 — 이런 예외는 잊히는 것이 가장 위험하다.
            log.warn("심사 계정 자동 제재 면제가 켜져 있다 count={}", ids.size());
        }
    }

    /** 이 사용자를 자동 제재에서 빼야 하는가. */
    public boolean isExempt(UUID userId) {
        return userId != null && ids.contains(userId);
    }

    private static Set<UUID> parse(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ReviewAccounts::toUuid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static UUID toUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // 오타로 기동을 막지는 않는다 — 면제가 안 걸릴 뿐이고, 못 미더울 때는 제재하는 쪽이 안전하다.
            log.error("심사 계정 id 를 읽을 수 없어 무시한다: {}", value);
            return null;
        }
    }
}
