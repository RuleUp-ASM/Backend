package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * GET /api/v1/challenges/{challengeId}/my-location 응답 — 위치 셋업/수정 화면 재진입 시 지도 핀 복원용.
 *
 * @param anchors               바인딩된 앵커 목록(1~3개). 반경은 여기 없고 serverRadiusM으로 따로 내려간다
 * @param serverRadiusM         서버 설정 반경(m). 유저가 정하는 값이 아니며 성능 테스트 후 변경될 수 있다
 * @param appliedFrom           현재 앵커 세트가 적용된 시각(ISO-8601, KST)
 * @param changeAvailable       이번 달 앵커 변경 가능 여부 — 수정 버튼 활성/비활성과 안내 문구용
 * @param nextChangeAvailableAt 변경 권한 소진 시 다음 변경 가능 시각(다음 달 1일 00:00 KST).
 *                              changeAvailable이 true면 null
 */
public record MemberLocationResponse(
        List<AnchorDto> anchors,
        Integer serverRadiusM,
        String appliedFrom,
        boolean changeAvailable,
        String nextChangeAvailableAt
) {}
