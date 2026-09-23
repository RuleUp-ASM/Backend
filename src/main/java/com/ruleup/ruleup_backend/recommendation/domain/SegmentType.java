package com.ruleup.ruleup_backend.recommendation.domain;

/**
 * 추천 세그먼트 종류 (콜드스타트 warm-up).
 *  - GLOBAL   : 전체 공통. segmentValue = "ALL". 모든 유저가 공유 → 인구통계가 없어도 '전체 인기도'로 추천.
 *  - COUNTRY  : 국가(콜드스타트 base). segmentValue = ISO alpha-2 (예: "KR").
 *  - GENDER   : 성별. segmentValue = "MALE"/"FEMALE".
 *  - AGE_BAND : 연령대. segmentValue = "10s"/"20s"/... (birthDate에서 파생).
 *  - PLATFORM : 클라 플랫폼. segmentValue = "ANDROID"/"IOS" (가입·로그인 시 수집한 기기 정보).
 */
public enum SegmentType {
    GLOBAL, COUNTRY, GENDER, AGE_BAND, PLATFORM
}