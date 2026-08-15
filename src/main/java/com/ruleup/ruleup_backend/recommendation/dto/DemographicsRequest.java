package com.ruleup.ruleup_backend.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 추천용 인구통계 입력 (가입 후 최초 접속 시 수집, 선택). 모든 필드 선택값.
 * 원치 않으면 해당 필드를 null/미전송으로 두면 된다(건너뛰기). 채워진 값만 저장·추천에 반영.
 *
 * <p>국가 코드는 받지 않는다 — 사용자 입력이 아니라 서버가 요청(Accept-Language/지오 헤더)에서 해석한다.
 *
 * <pre>
 * { "birthDate": "1998-03-21", "gender": "MALE" }
 * </pre>
 */
@Schema(name = "DemographicsRequest", description = """
        온보딩 인구통계 입력. 모든 필드가 선택이며 보낸 값만 반영된다(부분 갱신).
        건너뛰려면 필드를 빼거나 null 로 둔다.""")
public record DemographicsRequest(

        @Schema(description = """
                생년월일(YYYY-MM-DD). 이미 저장된 생일이 있으면 무시된다 — 가입 후 수정 불가 계약.
                미래 날짜나 형식 오류는 400 INVALID_REQUEST.""",
                example = "1998-03-21")
        String birthDate,

        @Schema(description = "성별. 보내면 갱신된다. 정의되지 않은 값은 400 INVALID_REQUEST.",
                example = "MALE", allowableValues = {"MALE", "FEMALE", "NON_BINARY", "PREFER_NOT_TO_SAY"})
        String gender) {}
