package com.ruleup.ruleup_backend.watcher.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 피감시자가 보는 내 감시자 목록 — API 명세 「감시자 목록 조회」.
 *
 * <p><b>목록 키는 {@code watchers} 다.</b> 구현이 {@code items} 를 내리는 바람에 앱이 목록을 항상
 * 0명으로 그렸고 「감시자 2/3」 표기가 동작하지 않았다(2026-09-07 실측).
 *
 * <p>{@code slots} 는 {@code {used, total}} 이 아니라 {@code {used, freeLimit, subscribed}} 다 —
 * 구독 시 {@code freeLimit} 이 null(무제한)이므로 클라이언트가 {@code used/freeLimit} 을 그대로
 * 그리면 「2/null」이 뜬다.
 *
 * @param slots    슬롯 현황
 * @param watchers 요청한 상태의 감시자·초대 목록
 */
@Schema(name = "WatcherListResponse")
public record WatcherListResponse(Slots slots, List<Item> watchers) {

    @Schema(name = "WatcherSlots", description = "감시자 슬롯. 구독 시 freeLimit 이 null(무제한)이다.")
    public record Slots(
            @Schema(description = "사용 중인 슬롯 — 살아 있는 관계 + 유효한 미수락 초대", example = "2")
            int used,
            @Schema(description = "무료 상한. 구독 중이면 null(무제한)", example = "3")
            Integer freeLimit,
            @Schema(description = "구독 여부. 구독 도메인 도입 전이라 현재는 항상 false", example = "false")
            boolean subscribed) {}

    /**
     * 목록 한 줄. 수락된 관계와 아직 유효한 미수락 초대를 같은 모양으로 내린다.
     *
     * <p>{@code contactMasked} 는 <b>언제나 null</b> 이다. 비유저 감시자와 SMS·이메일 채널이 정책상
     * 폐지되면서 연락처를 어느 테이블에도 두지 않기로 했고(V28), 그래서 마스킹할 원본 자체가 없다.
     * 필드를 남겨 두는 것은 명세 계약이 그 모양이기 때문이며, null 이 곧 「수집하지 않는다」는 증거다.
     */
    @Schema(name = "WatcherListItem")
    public record Item(
            @Schema(description = "ACTIVE 는 관계 id, INVITED 는 초대 id") String watcherId,
            @Schema(description = "감시자는 룰업 유저만 가능하므로 항상 USER", example = "USER") String type,
            @Schema(description = "전달 수단. 성립한 관계는 IN_APP, 미수락 초대는 null", example = "IN_APP")
            String channel,
            @Schema(description = "INVITED(미수락 초대) / PENDING / ACTIVE", example = "ACTIVE") String status,
            @Schema(description = "감시자의 공개 닉네임. 미수락 초대는 누가 받을지 모르므로 null")
            String displayName,
            @Schema(description = "언제나 null — 연락처를 수집하지 않는다") String contactMasked,
            @Schema(description = "초대 발급 시각") String invitedAt,
            @Schema(description = "초대 토큰 만료. 성립한 관계는 null") String expiresAt,
            @Schema(description = "언제나 null — 관계 해제 개념이 없어 재초대 대기가 발생하지 않는다")
            String reinviteAvailableAt) {}
}
