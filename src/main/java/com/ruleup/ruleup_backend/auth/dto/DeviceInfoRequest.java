package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.user.domain.Platform;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 공통 deviceInfo 객체 (계약: 로그인·가입 양쪽 동반). 필드명은 intro 요청바디와 동일하게 맞춘다(§11.1).
 * 로그인·가입마다 최신 1건으로 갱신한다. 누락/형식오류는 INVALID_DEVICE_INFO 로 거부한다.
 *
 * <p>전체 스펙({@code platform / versionCode / versionName / osVersion / sdkInt / deviceModel /
 * manufacturer / lowRam})을 저장한다(로그인 응답에 그대로 되돌려줌). platform 은 추천 PLATFORM 세그먼트로도 사용.
 *
 * <pre>
 * { "platform":"ANDROID", "osVersion":"14", "sdkInt":34, "deviceModel":"SM-S921N",
 *   "manufacturer":"samsung", "lowRam":false, "versionName":"1.0.0", "versionCode":100 }
 * </pre>
 */
@Schema(name = "DeviceInfoRequest", description = """
        기기 스펙. 로그인·가입마다 최신 1건으로 갱신한다.
        최소 platform·versionCode 는 유효해야 하며, 누락/형식오류는 INVALID_DEVICE_INFO 로 거절한다.
        인증 sync 주기(flushIntervalSec)를 기기 성능에 맞춰 산정하는 데도 쓴다.""")
public record DeviceInfoRequest(

        @Schema(description = "클라이언트 플랫폼. ANDROID 또는 IOS.", example = "ANDROID",
                allowableValues = {"ANDROID", "IOS"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String platform,

        @Schema(description = "OS 버전명.", example = "14")
        String osVersion,

        @Schema(description = "안드로이드 SDK 레벨. iOS 는 보내지 않아도 된다.", example = "34")
        Integer sdkInt,

        @Schema(description = "기기 모델명.", example = "SM-S921N")
        String deviceModel,

        @Schema(description = "제조사.", example = "samsung")
        String manufacturer,

        @Schema(description = "저사양(low RAM) 기기 여부. sync 주기를 늘리는 판단에 쓴다.", example = "false")
        Boolean lowRam,

        @Schema(description = "사용자에게 보이는 앱 버전명.", example = "1.0.0")
        String versionName,

        @Schema(description = "앱 버전 코드(안드로이드 versionCode, iOS 빌드 넘버). 강제 업데이트 판정 기준값.",
                example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer versionCode,

        @Schema(description = """
                기기 지역(선택). ISO alpha-2 또는 "ko-KR" 형태를 허용한다.
                CDN 지오 헤더가 없을 때 국가 코드 판정의 폴백 소스로 쓴다.""",
                example = "KR")
        String country,   // 기기 지역(ISO alpha-2 또는 "ko-KR" 허용, 선택). CDN 지오 헤더가 없을 때 국가 코드 소스로 사용.

        @Schema(description = """
                기기 타임존(선택, IANA ID). `TimeZone.getDefault().getID()` 를 그대로 보내면 된다.
                지오 헤더도 기기 지역도 없을 때 국가 코드를 정하는 마지막 근거다 —
                이게 비면 국가는 서비스 기본값으로 채워진다.""",
                example = "Asia/Seoul")
        String timeZone) {

    /** 문자열 platform 을 enum 으로(대소문자 무시, 알 수 없거나 비면 null). */
    public Platform toPlatform() {
        if (platform == null || platform.isBlank()) return null;
        try {
            return Platform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 계약상 deviceInfo는 로그인·가입에 필수. 최소 필드(platform·versionCode)가 유효해야 한다.
     * 형식 위반 시 호출부에서 INVALID_DEVICE_INFO 로 거부.
     */
    public boolean isValid() {
        return toPlatform() != null && versionCode != null;
    }
}
