package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * POST /api/v1/challenges/{challengeId}/setup 응답.
 *
 * @param setupStatus   PENDING_SETUP / READY. 필요한 바인딩을 다 채우면 READY로 전환되며 그때부터 판정 대상이 된다
 * @param missing       아직 모자란 항목(ANCHORS_REQUIRED / TARGET_PACKAGES_REQUIRED). READY면 빈 배열.
 *                      클라는 이 값으로 어느 셋업 화면을 다시 띄울지 정한다
 * @param serverRadiusM 현재 서버 설정 반경(m) — GPS 방 지도 원 표시용. GPS 방이 아니면 null
 */
public record SetupResponse(String setupStatus, List<String> missing, Integer serverRadiusM) {}
