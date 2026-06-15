package com.ruleup.ruleup_backend.routine.dto;

import java.util.List;

/**
 * 추천된 인증 방식 1개.
 *  - method             : AUTO / MANUAL
 *  - recommended        : 기본 선택(자동 지원 템플릿이면 AUTO, 아니면 MANUAL)
 *  - verificationType/signalSource : 인증이 어떻게 동작하는지(정보)
 *  - wearableRequirement: 워치 필요 정도(NONE/OPTIONAL/REQUIRED) — 후순위, 안내용
 *  - externalService    : 외부 연동 필요 시 서비스명(GitHub/RSS/WakaTime 등). 없으면 null.
 *  - requiredPermissions: AUTO 가 동작하려면 클라가 받아둬야 할 권한 목록.
 *
 * ※ 추천 단계에선 권한 "보유 여부"를 따지지 않는다. AUTO 에 필요한 권한 "목록"만 내려주고,
 *   실제 보유 확인은 생성 단계에서 한다(없으면 ROUTINE_PERMISSION_REQUIRED 로 바운스).
 */
public record RoutineOption(
        String method,
        boolean recommended,
        String verificationType,
        String signalSource,
        String wearableRequirement,
        String externalService,
        List<String> requiredPermissions
) {
}