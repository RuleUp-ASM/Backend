package com.ruleup.ruleup_backend.routine.dto;

import java.util.List;

/**
 * 추천된 인증 방식 1개.
 *  - method            : AUTO / MANUAL
 *  - available         : 지금 바로 이 방식으로 인증 가능한가?
 *                        (AUTO=필요 권한을 모두 보유 / MANUAL=항상 true)
 *  - recommended       : 기본 선택(자동 가능하면 자동, 아니면 수동)
 *  - missingPermissions: AUTO 인데 아직 없는 권한(클라가 권한 요청 유도). MANUAL 은 빈 배열.
 *  - externalService   : 외부 연동 필요 시 서비스명(GitHub/Codeforces/RSS/WakaTime). 없으면 null.
 *  - wearableRequirement: 워치 필요 정도(NONE/OPTIONAL/REQUIRED) — 클라가 안내.
 */
public record RoutineOption(
        String method,
        boolean available,
        boolean recommended,
        String verificationType,
        String signalSource,
        String wearableRequirement,
        String externalService,
        List<String> requiredPermissions,
        List<String> missingPermissions
) {
}