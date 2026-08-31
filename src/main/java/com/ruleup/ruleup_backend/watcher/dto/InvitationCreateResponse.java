package com.ruleup.ruleup_backend.watcher.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 초대 발급 응답.
 *
 * <p><b>룰업은 이 초대를 전송하지 않는다.</b> 클라이언트가 {@code kakaoShare} 를 써서 사용자
 * 본인 명의로 공유한다 — 사업자가 동의하지 않은 외부인에게 먼저 닿지 않게 하는 장치다.
 */
@Schema(name = "WatcherInvitationCreateResponse")
public record InvitationCreateResponse(

        String invitationId,

        @Schema(description = "공유용 원본 토큰. **서버는 해시만 보관**하므로 이 응답이 원본을 보는 유일한 지점이다.")
        String token,

        @Schema(description = "공유 링크. URL 에 개인정보를 담지 않는다.")
        String inviteUrl,

        @Schema(description = "만료 시각 — 발급 + 7일") String expiresAt,

        @Schema(description = "카카오톡 공유 카드 메타") KakaoShare kakaoShare) {

    @Schema(name = "WatcherKakaoShare")
    public record KakaoShare(String title, String description, String buttonLabel) {}
}
