package com.ruleup.ruleup_backend.routine.dto;

import java.util.List;

/**
 * 1단계 추천 요청. 제목(필수) + 설명(선택) + 현재 단말이 보유한 권한 목록.
 *
 * grantedPermissions: 안드로이드 런타임 권한은 단말에만 있는 상태라 서버가 알 수 없다.
 *   → 클라이언트가 "지금 허용돼 있는 권한"을 그대로 보내면, 서버가 템플릿이 요구하는 권한과
 *     비교해 자동 인증 가능 여부를 판정한다. (별도 권한 테이블/동기화 없이 항상 최신)
 *   예: ["ACCESS_FINE_LOCATION", "ACTIVITY_RECOGNITION", "PACKAGE_USAGE_STATS"]
 */
public record RoutineRecommendationRequest(
        String title,
        String description,
        List<String> grantedPermissions
) {
    public List<String> grantedPermissionsOrEmpty() {
        return (grantedPermissions != null) ? grantedPermissions : List.of();
    }
}