package com.ruleup.ruleup_backend.recommendation.dto;

/**
 * 추천용 인구통계 입력 (가입 후 최초 접속 시 수집, 선택). 모든 필드 선택값.
 * 원치 않으면 해당 필드를 null/미전송으로 두면 된다(건너뛰기). 채워진 값만 저장·추천에 반영.
 *
 * <pre>
 * { "countryCode": "KR", "birthDate": "1998-03-21", "gender": "MALE" }
 * </pre>
 */
public record DemographicsRequest(String countryCode, String birthDate, String gender) {}
