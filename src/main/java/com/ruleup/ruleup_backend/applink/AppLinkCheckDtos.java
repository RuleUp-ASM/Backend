package com.ruleup.ruleup_backend.applink;

import io.swagger.v3.oas.annotations.media.Schema;

/** 앱링크 유효성 검사 요청·응답. */
public final class AppLinkCheckDtos {

    private AppLinkCheckDtos() {}

    @Schema(name = "AppLinkCheckRequest")
    public record Request(
            @Schema(description = "검사할 앱링크 전체 URL",
                    example = "https://android.ruleup.co.kr/c/Xk3n...") String url) {}

    /**
     * 검사 결과. <b>유효하지 않아도 200 이다</b> — 링크가 나쁜 것이지 요청이 잘못된 게 아니다.
     * 유일한 4xx 는 url 자체가 없을 때뿐이다.
     */
    @Schema(name = "AppLinkCheckResponse", description = """
            형식·존재·만료 세 가지를 검사한 결과. 여기서 통과해도 실제 진입 가능 여부(정원·가입 자격 등)는
            각 링크 타입의 조회 API 가 따로 판단한다 — 이 API 는 링크 자체의 유효성만 본다.""")
    public record Response(
            @Schema(description = "형식·존재·만료를 모두 통과했는지", example = "true") boolean valid,
            @Schema(description = "CHALLENGE_INVITATION / WATCHER_INVITATION. 형식 불통과면 null")
            String linkType,
            @Schema(description = """
                    링크에서 추출한 토큰. 유효하면 각 타입의 조회 API 로 이어서 호출한다.
                    형식 불통과면 null""") String token,
            @Schema(description = "MALFORMED / UNSUPPORTED / NOT_FOUND / EXPIRED. 유효하면 null")
            String reason,
            @Schema(description = "EXPIRED 일 때 만료된 시각(ISO-8601)") String expiredAt) {

        public static Response valid(AppLinkType type, String token) {
            return new Response(true, type.name(), token, null, null);
        }

        /** 타입·토큰까지는 알아낸 실패 — 클라가 "만료된 초대" 같은 구체적 안내를 그릴 수 있다. */
        public static Response invalid(AppLinkType type, String token,
                                       AppLinkCheckReason reason, String expiredAt) {
            return new Response(false, type != null ? type.name() : null, token, reason.name(), expiredAt);
        }
    }
}
