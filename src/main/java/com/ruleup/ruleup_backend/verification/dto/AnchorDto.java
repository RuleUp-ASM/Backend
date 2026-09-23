package com.ruleup.ruleup_backend.verification.dto;

/**
 * 인증 장소(앵커) 한 개. 셋업 제출·조회·수정이 공유하는 계약.
 *
 * <p><b>반경은 들어있지 않다.</b> 유저가 정하는 값이 아니라 서버 설정 단일값(잠정 500m)이라
 * 응답의 {@code serverRadiusM}으로 따로 내려간다. 클라는 그 값으로 지도에 원을 그린다.
 *
 * @param lat   위도(-90~90). 벗어나면 INVALID_ANCHOR
 * @param lng   경도(-180~180). 벗어나면 INVALID_ANCHOR
 * @param label 장소 라벨(선택). 심사 대상이 아니라 모더레이션을 타지 않는다
 */
public record AnchorDto(double lat, double lng, String label) {}
