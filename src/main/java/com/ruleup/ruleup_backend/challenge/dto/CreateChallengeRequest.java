package com.ruleup.ruleup_backend.challenge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST /api/v1/challenges 요청 — 확인 화면에서 수정을 마친 초안의 확정값.
 *  - draftId 필수: 서버가 원본 대조(심사 대상 판정)·출처·AI 임시 제목 확보에 사용.
 *  - 수정 여부 자가 신고 필드(titleEdited 등)·aiTitle·온도·anonymity·cycleDays 는 폐기된 구 계약.
 *  - penalties 는 watcher 만 의미 있음(score·groupShare 는 서버 고정 — 클라 값 무시).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateChallengeRequest(
        String draftId,
        String title,
        String description,
        String category,
        String mode,                  // SOLO / GROUP
        String visibility,            // 그룹: PUBLIC(기본)/PRIVATE — 솔로 null
        Boolean rankingVisible,       // 솔로: 기본 true — 그룹 null
        Integer capacity,             // 그룹 전용 1~10,000
        String minTier,               // ≤ 생성자 표시 티어
        Period period,
        Integer weeklyCount,          // 1~7, 미전송 시 draft 원본 유지
        List<Param> params,
        Verification verification,    // AUTO→MANUAL 만 허용
        Penalties penalties,
        String imageUrl               // 업로드 API 발급 본인 소유 URL 만(null=기본 이미지)
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(String start, String end) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Param(String key, String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verification(String type, String method) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Penalties(Boolean watcher) {}
}
