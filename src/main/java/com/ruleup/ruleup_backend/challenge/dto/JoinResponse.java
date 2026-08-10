package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;

import java.util.List;

/**
 * 챌린지 가입 응답 — 가입 API 명세 200 OK.
 *
 * @param countFromCycle        판정이 시작되는 날짜(사이클 중간 입장이면 다음 사이클 경계 — 사이클 1주 고정)
 * @param requiredPermissions   필요한 OS 권한(수동 방이면 빈 배열). 실제 확보는 <b>가입 전</b> 클라 책임 —
 *                              서버는 권한 보유를 가입 게이트로 검사하지 않는다(테크스펙 5-1 유형 3)
 * @param personalSetupRequired 첫 입장 개인 설정(앵커·대상 앱) 필요 여부
 */
public record JoinResponse(
        boolean joined,
        String countFromCycle,
        List<String> requiredPermissions,
        boolean personalSetupRequired
) {
    public static JoinResponse of(String countFromCycle, VerificationConfig snapshot) {
        boolean auto = snapshot != null && snapshot.selectedMethod() == SelectedMethod.AUTO;
        List<String> perms = (snapshot != null && snapshot.requiredPermissions() != null)
                ? snapshot.requiredPermissions() : List.of();
        return new JoinResponse(true, countFromCycle, auto ? perms : List.of(), auto);
    }
}
