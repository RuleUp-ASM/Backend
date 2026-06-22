package com.ruleup.ruleup_backend.recommendation.domain;

/**
 * 추천 세그먼트 종류 (콜드스타트 warm-up).
 *  - COUNTRY  : 국가(콜드스타트 base). segmentValue = ISO alpha-2 (예: "KR").
 *  - GENDER   : 성별. segmentValue = "MALE"/"FEMALE".
 *  - AGE_BAND : 연령대. segmentValue = "10s"/"20s"/... (birthDate에서 파생).
 */
public enum SegmentType {
    COUNTRY, GENDER, AGE_BAND
}