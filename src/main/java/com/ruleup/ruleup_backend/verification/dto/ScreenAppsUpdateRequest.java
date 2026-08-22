package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * PUT /api/v1/challenges/{challengeId}/my-screen-apps 요청 — 측정 대상 앱 세트 <b>전체 교체</b>(부분 수정 아님).
 *
 * <p>1~10개, packageName 중복 불가. 적용은 항상 익일 00:00부터라 당일 교체로 인증을 조작할 수 없다.
 * 목표값(N분 이하/이상)은 정책상 변경 불가이므로 이 API에서 다루지 않는다.
 */
public record ScreenAppsUpdateRequest(List<AppDto> apps) {}
