package com.ruleup.ruleup_backend.challenge.draft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 초안 응답 스키마(경로 A·B 공통) — API 명세 "draft" 객체.
 * 클라이언트는 이 스키마 하나로 확인 화면 폼을 채우고, 수정 후 생성 API 로 보낸다.
 * challenge_drafts.payload 에도 이 형태 그대로 보관해 생성 시 원본 대조에 쓴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftView(
        String title,                 // AI 생성 제목(경로 B) / 템플릿명(경로 A) — 임시 제목으로 영구 보관
        String description,           // AI 교정 설명 / 템플릿 설명
        String category,              // 12종 enum — 확인 화면에서도 수정 불가
        String mode,                  // SOLO / GROUP
        String visibility,            // 그룹: PUBLIC/PRIVATE, 솔로: null
        Boolean rankingVisible,       // 솔로: 기본 true, 그룹: null
        Integer capacity,             // 기본 50
        String minTier,               // 기본 = 생성자 표시 티어(상한 동일)
        Period period,                // 시작=생성일+1일, 종료=시작+2주
        List<DraftParam> params,      // 목표값 스펙 + 값
        Verification verification,    // 방 단위 인증 스냅샷
        Penalties penalties           // score·groupShare 서버 고정, watcher 만 선택
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(String start, String end) {}

    /** 목표값 1건 — 값·기본값은 문자열(숫자도 "3", 시각은 "06:00"). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftParam(String key, String value, String defaultValue,
                             String kind, String unit, BigDecimal min, BigDecimal max) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verification(String type, String method, List<String> requiredPermissions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Penalties(boolean score, boolean groupShare, boolean watcher) {}
}
