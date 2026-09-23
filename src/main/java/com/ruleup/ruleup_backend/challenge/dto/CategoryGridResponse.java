package com.ruleup.ruleup_backend.challenge.dto;

import java.util.List;

/**
 * 홈 카테고리 그리드 — 카테고리 API 명세.
 *
 * <p>관심 분야 정책 12종을 <b>고정 순서</b>로 항상 전부 내려준다(값이 없으면 0). 화면이 빈칸 없이
 * 같은 격자를 유지해야 하기 때문이다.
 *
 * @param items code = 관심 카테고리 enum, name = 한글 표시명,
 *              activeGroupCount = 진행 중 <b>공개</b> 그룹 방 수(비공개·솔로·종료·시작 전 미집계)
 */
public record CategoryGridResponse(List<Item> items) {

    public record Item(String code, String name, int activeGroupCount) {}
}
