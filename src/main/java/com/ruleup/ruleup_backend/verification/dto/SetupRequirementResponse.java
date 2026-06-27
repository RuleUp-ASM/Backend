package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * 최초 진입 셋업 "요구사항 조회"(§11.4 GET). POST /setup 으로 제출하기 전에
 * "이 챌린지를 인증하려면 무엇을 받아/골라야 하는지"를 클라가 미리 알 수 있게 한다.
 *
 *  - setupStatus           : 내 현재 셋업 상태(PENDING_SETUP / READY).
 *  - manual                : 수동 인증(PHOTO/SELF_CHECK)이면 true — 자동 신호 권한이 거의 필요 없음.
 *  - verificationMethod     : GPS_PRESENCE / HEALTH / SCREEN_TIME / WAKE / SLEEP / PHOTO / SELF_CHECK.
 *  - requiredPermissions    : 받아야 할 디바이스 권한 목록(없으면 빈 배열).
 *  - requiresAnchors        : 인증 장소(앵커) 바인딩 필요(GPS_PRESENCE)인지.
 *  - anchorsConfigured      : 이미 내 앵커가 바인딩돼 있는지.
 *  - requiresTargetPackages : 대상 앱 선택 필요(SCREEN_TIME)인지.
 *
 * 실제 grant·바인딩 제출과 missing[] 판정은 POST /setup(SetupResponse)이 담당한다.
 */
public record SetupRequirementResponse(
        String setupStatus,
        boolean manual,
        String verificationMethod,
        List<String> requiredPermissions,
        boolean requiresAnchors,
        boolean anchorsConfigured,
        boolean requiresTargetPackages
) {}
