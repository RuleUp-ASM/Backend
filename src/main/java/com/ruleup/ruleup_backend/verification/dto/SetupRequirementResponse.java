package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * GET /api/v1/challenges/{challengeId}/setup 응답 — 챌린지 첫 진입 때 "인증을 시작하려면 뭘 묶어야 하는지"를
 * 알려주는 읽기 전용 조회.
 *
 * <p>OS 권한은 생성/가입 단계에서 클라가 이미 받았으므로 여기서 받지 않는다.
 * {@code requiredPermissions}는 클라가 스스로 재확인하는 참고 목록이고, 서버는 보유 여부를 저장하지 않는다.
 *
 * @param setupStatus            PENDING_SETUP / READY. READY가 아니면 sync 신호를 받아두되 판정은 건너뛴다
 * @param manual                 수동 인증(SELF_CHECK) 방이면 true. true면 앵커·대상 앱 바인딩이 모두 불필요
 * @param verificationMethod     GPS_PRESENCE / SCREEN_TIME / HEALTH / WAKE / SLEEP / SELF_CHECK
 * @param requiredPermissions    클라 재확인용 OS 권한 목록(없으면 빈 배열)
 * @param requiresAnchors        장소(앵커) 바인딩이 필요한지 — GPS_PRESENCE이면 true
 * @param anchorsConfigured      이미 바인딩됐는지. 재진입·재참여 시 true일 수 있다
 * @param requiresTargetPackages 측정 대상 앱 선택이 필요한지 — SCREEN_TIME이면 true
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
