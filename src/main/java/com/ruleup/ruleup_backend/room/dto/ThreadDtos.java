package com.ruleup.ruleup_backend.room.dto;

import java.util.List;

/**
 * 방 스레드 피드 응답(Phase 1).
 *
 * <p>공지·댓글이 Phase 2로 빠지면서 피드의 원천은 인증 판정 하나뿐이다. 그래서 고정 공지 배너
 * (`pinnedNotice`)와 아이템의 `title`·`commentCount` 는 필드째 없앴다 — 항상 null·0 인 필드는
 * 클라이언트가 "언젠가 값이 온다"고 오해할 여지만 남긴다.
 */
public final class ThreadDtos {
    private ThreadDtos() {}

    public record Response(List<Item> items, String nextCursor) {}

    /**
     * @param type     VERIFY_SUCCESS · VERIFY_FAIL. Phase 2에서 NOTICE 가 합류한다.
     * @param at       피드 정렬·표시 시각. 성공은 확정 시각, 실패는 <b>공유 가능 시각</b>이다.
     * @param streak   성공 이벤트의 연속 성공 일수(실패는 null).
     * @param failDate 실패 이벤트가 가리키는 <b>원래 날짜</b>. 노출이 하루 늦으므로 과거형 표기에 쓴다.
     */
    public record Item(String type, String id, User user, String at, Integer streak, String failDate) {}

    public record User(String userId, String nickname, String profileImageUrl, boolean blocked) {}
}
