package com.ruleup.ruleup_backend.routine.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 챌린지가 보관하는 "인증 방식 스냅샷". challenges.verification_config(JSON) 에 저장된다.
 *  - 챌린지 생성 시점에 템플릿에서 떠서 박아둔다 → 나중에 템플릿이 바뀌어도 이 챌린지는 그대로.
 *  - 인증 방식·신호·필요 권한은 전부 템플릿(서버)이 진실(신뢰 경계).
 */
public record VerificationConfig(
        SelectedMethod selectedMethod,
        VerificationType verificationType,
        SignalSource signalSource,
        WearableRequirement wearableReq,
        List<String> requiredPermissions,
        String externalService
) {
    /**
     * "설정형" 권한 — OS 설정 화면을 열어 사용자가 직접 켜야 하는 무거운 권한(챌린지 생성 플로우 §5.1).
     * 생성 버튼 시점(팝업)엔 받을 수 없고, 가입 후 최초 진입 셋업(§11.4 /setup)에서 등록한다.
     * 따라서 생성 시점 권한 검증에서는 이 목록을 제외한다(나머지 즉시형만 강제).
     *  - ACCESS_BACKGROUND_LOCATION : 헬스장 등 백그라운드 위치
     *  - PACKAGE_USAGE_STATS        : 스크린타임/대상 앱 사용정보
     */
    public static final Set<String> DEFERRED_TO_SETUP =
            Set.of("ACCESS_BACKGROUND_LOCATION", "PACKAGE_USAGE_STATS");

    /**
     * 생성 버튼 시점에 즉시(인앱 팝업) 받을 수 있어 생성 시 검증 대상인 권한만 추린다.
     * 설정형(DEFERRED_TO_SETUP)은 셋업으로 이연하므로 제외한다.
     */
    public List<String> immediateRequiredPermissions() {
        if (requiredPermissions == null) return List.of();
        return requiredPermissions.stream()
                .filter(p -> !DEFERRED_TO_SETUP.contains(p))
                .toList();
    }

    /** 자동 인증. 호출 전 template.supportsAuto() 가 보장돼야 한다. */
    public static VerificationConfig auto(RoutineTemplate t) {
        WearableRequirement wear = (t.getAutoWearableReq() != null)
                ? t.getAutoWearableReq() : WearableRequirement.NONE;
        return new VerificationConfig(
                SelectedMethod.AUTO,
                t.getAutoVerificationType(),
                t.getAutoSignalSource(),
                wear,
                new ArrayList<>(t.getAutoRequiredPermissions()),
                t.getAutoExternalService());
    }

    /** 수동 인증. template 은 null 가능(매칭 실패 = 직접 입력). 사진이면 카메라 권한 필요. */
    public static VerificationConfig manual(RoutineTemplate t) {
        SignalSource signal = (t != null) ? t.getManualSignalSource() : SignalSource.PHOTO;
        List<String> perms = (signal == SignalSource.PHOTO)
                ? new ArrayList<>(List.of("CAMERA")) : new ArrayList<>();
        return new VerificationConfig(
                SelectedMethod.MANUAL, VerificationType.MANUAL, signal,
                WearableRequirement.NONE, perms, null);
    }
}