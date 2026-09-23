package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * POST /api/v1/challenges/{challengeId}/setup 요청 — 앵커·대상 앱 <b>바인딩 제출</b>.
 *
 * <p>인증 방식에 따라 둘 중 하나만 채워 보낸다(GPS 방이면 location, 스크린타임 방이면 targetPackages).
 * OS 권한은 생성/가입 단계에서 이미 받았으므로 여기서 받지 않는다 — 서버는 권한 보유 여부를 저장하지 않는다.
 *
 * <p>첫 설정은 월 1회 변경 횟수를 소진하지 않는다. 소진은 이후 <i>변경</i>(PUT my-location / my-screen-apps)부터.
 *
 * @param location       GPS 방일 때의 앵커 바인딩. GPS 방이 아니면 null
 * @param targetPackages 스크린타임 방일 때의 측정 대상 앱(1~10개, packageName 중복 불가). 아니면 null
 */
public record SetupRequest(LocationBinding location, List<AppDto> targetPackages) {

    /**
     * 앵커 바인딩. 최대 3개(구 스펙 10개에서 축소) — 4개 이상이면 ANCHOR_LIMIT_EXCEEDED.
     * 반경은 요청에 없다(서버 설정 단일값).
     */
    public record LocationBinding(List<AnchorDto> anchors) {}
}
