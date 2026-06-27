package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;

import java.util.List;

/**
 * 3.6 참여 신청 응답.
 *  - memberStatus : PENDING(승인 대기) / ACTIVE(즉시 참여).
 *  - setupStatus  : PENDING_SETUP(권한·셋업 전) / READY(평가 대상). 가입 직후엔 보통 PENDING_SETUP.
 *  - selectedMethod / requiredPermissions : 가입 직후 클라가 "무슨 권한을 받아 §11.4 /setup 을 해야 하는지"
 *    바로 알 수 있게 인증 스냅샷에서 내려준다. (실제 grant·확인은 /setup 에서)
 */
public record JoinResponse(
        String memberStatus,
        String setupStatus,
        String selectedMethod,
        List<String> requiredPermissions
) {
    public static JoinResponse of(String memberStatus, String setupStatus, VerificationConfig snapshot) {
        String method = (snapshot != null && snapshot.selectedMethod() != null)
                ? snapshot.selectedMethod().name() : null;
        List<String> perms = (snapshot != null && snapshot.requiredPermissions() != null)
                ? snapshot.requiredPermissions() : List.of();
        return new JoinResponse(memberStatus, setupStatus, method, perms);
    }
}
