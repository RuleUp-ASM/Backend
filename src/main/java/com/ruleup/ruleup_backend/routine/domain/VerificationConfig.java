package com.ruleup.ruleup_backend.routine.domain;

import java.util.ArrayList;
import java.util.List;

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