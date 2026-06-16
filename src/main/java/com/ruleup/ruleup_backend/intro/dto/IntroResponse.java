package com.ruleup.ruleup_backend.intro.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /intro 응답 본문 (앱 버전 게이트).
 *
 * <p>안드로이드 {@code IntroDTO} 와 1:1로 매칭되는 "납작한(flat)" 모델이다.
 * 클라가 에러 바디를 {@code devTestMsg / minAppVersion / recommendAppVersion}
 * 최상위 필드로 바로 역직렬화하기 때문에, 이 엔드포인트는 의도적으로
 * 공통 봉투({@code {success, data, error}})를 쓰지 않고 이 형태를 그대로 내려준다.
 * (성공 200 / 강제 업데이트 400 모두 동일한 본문 형태)
 *
 * <p>필드 값이 비어 있으면 null로 내려, 클라의 {@code ?: UNKNOWN} 폴백이 동작하도록 한다.
 */
@Schema(description = "앱 인트로/버전 안내 응답 (성공 200·강제 업데이트 400 공통 본문)")
public record IntroResponse(

        @Schema(description = "개발/점검용 안내 메시지 (없으면 null)", example = "점검 중입니다. 잠시 후 다시 시도해주세요.")
        String devTestMsg,

        @Schema(description = "지원하는 최소 앱 버전명 (이 미만이면 강제 업데이트)", example = "1.0.0")
        String minAppVersion,

        @Schema(description = "권장 앱 버전명 (소프트 업데이트 유도용)", example = "1.2.0")
        String recommendAppVersion
) {

    /** 빈 문자열·공백은 null로 정규화해서 만든다. (클라의 UNKNOWN 폴백과 맞물리도록) */
    public static IntroResponse of(String devTestMsg, String minAppVersion, String recommendAppVersion) {
        return new IntroResponse(
                blankToNull(devTestMsg),
                blankToNull(minAppVersion),
                blankToNull(recommendAppVersion)
        );
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}