package com.ruleup.ruleup_backend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 알림 설정 조회·변경 — 알림 및 알림함 공통 5-2 #3·#4. */
public final class NotificationSettingDtos {

    private NotificationSettingDtos() {}

    @Schema(name = "NotificationSettingResponse", description = """
            **필수(A) 타입은 응답에 포함하지 않는다** — 설정 화면에 토글 컴포넌트 자체를 렌더링하지
            않기 위함이며, 존재를 인지시키지 않는 것이 정책이다.""")
    public record Response(

            @Schema(description = "기능(B) 유형별 토글. 저장된 값이 없으면 기본 ON 으로 내려간다.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<TypeToggle> types,

            @Schema(description = "챌린지별 음소거 목록. 유형별 토글과 AND 로 결합한다.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> mutedChallengeIds,

            @Schema(description = "마케팅(C) 수신 동의 상태 — 약관 동의와 연동된다", example = "false")
            boolean marketing) {}

    @Schema(name = "NotificationTypeToggle")
    public record TypeToggle(
            @Schema(description = "알림 타입", example = "ROUTINE_REMINDER") String type,
            @Schema(description = "OFF 여도 **푸시만 생략**되고 알림함 적재는 그대로다") boolean enabled) {}

    @Schema(name = "NotificationSettingPatchRequest", description = """
            보낸 항목만 바꾼다. 필수(A) 타입을 보내면 400 NOTIFICATION_TYPE_NOT_TOGGLABLE 이다.""")
    public record PatchRequest(

            @Schema(description = "유형별 토글 변경분")
            List<TypeToggle> types,

            @Schema(description = "챌린지별 음소거 목록 — 전체 교체다")
            List<String> mutedChallengeIds,

            @Schema(description = """
                    마케팅 수신 동의. 변경하면 **약관 동의 상태까지 같은 트랜잭션에서 갱신**한다 —
                    알림 설정과 동의 이력이 어긋나면 어느 쪽이 진짜인지 알 수 없게 된다.""")
            Boolean marketing) {}
}
