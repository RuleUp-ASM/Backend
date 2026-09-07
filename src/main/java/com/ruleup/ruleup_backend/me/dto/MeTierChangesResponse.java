package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 점수 변동 이력 전체 보기(GET /me/tier/changes) — 내 티어 화면의 「최근 변동 → 전체 보기」.
 *
 * <p>{@code GET /me/tier} 의 {@code recentChanges[]} 와 <b>동일한 항목 구조</b>이며 10건 제한을
 * 풀고 커서 페이징을 붙인 것이다. 같은 데이터를 더 보는 것뿐이므로 표기 규칙도 같다.
 *
 * <p><b>{@code /me/tier/history} 와는 다른 API 다.</b> 그쪽은 그래프 원천(스냅샷)이라
 * {@code reason}·{@code delta}·{@code challengeId} 가 없다. 그래프는 <b>기간</b>으로 읽고
 * 이력은 <b>건수</b>로 읽어 페이징 단위가 다르므로 합치지 않는다.
 *
 * <p>마이페이지 정책 §2-5 의 「하락 사유 표기 없음」은 <b>그래프 한정</b>으로 범위가 축소됐다
 * (2026-09-07) — 이 목록은 사유를 표기한다.
 */
@Schema(name = "MeTierChangesResponse", description = "점수 변동 이력 한 페이지(최신순, 최대 50건)")
public record MeTierChangesResponse(

        @Schema(description = "변동 이력. 최신순, 최대 50건")
        List<MeTierResponse.Change> items,

        @Schema(description = "다음 페이지 커서. 불투명 문자열이며 마지막 페이지면 null")
        String nextCursor,

        @Schema(description = "보관 기간(일) — 점수 및 티어 정책 §5", example = "365")
        int retentionDays) {}
