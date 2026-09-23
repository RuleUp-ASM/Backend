package com.ruleup.ruleup_backend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 알림 센터 목록 — 커서 페이징, <b>페이지 크기 서버 고정 50</b>.
 *
 * <p>미읽음 건수를 내리지 않는다. 클라이언트가 {@code lastReadNotificationId} 를 기준선 삼아
 * <b>목록에서의 위치</b>로 계산한다 — 카운터 상한이 {@code 99+} 라 최대 2페이지면 끝나고,
 * 그래서 별도 카운트 API 도 필요 없다.
 */
@Schema(name = "NotificationListResponse")
public record NotificationResponse(

        @Schema(description = "알림 목록 — 최신순", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Item> items,

        @Schema(description = "다음 페이지 커서(base64 불투명 문자열). null 이면 마지막 페이지다.")
        String nextCursor,

        @Schema(description = "보관 기간(일). 이 기간을 넘긴 알림은 파기 배치가 지운다.", example = "180")
        int retentionDays,

        @Schema(description = """
                **요청한 탭의** 읽음 지점. 이 항목보다 위에 있는 것이 전부 미읽음이다.
                null 이면 그 페이지가 전부 미읽음이므로 다음 페이지를 이어 읽는다.""")
        String lastReadNotificationId) {

    @Schema(name = "NotificationItem")
    public record Item(
            String id,
            @Schema(description = "알림 타입", example = "ACCOUNT_SANCTION") String type,
            String title,
            @Schema(description = "본문 — 민감정보를 담지 않는다") String body,
            @Schema(description = "탭 시 진입 경로. 없으면 null.") String deeplink,
            @Schema(description = """
                    챌린지별 미읽음 카운터의 귀속 방. **감시자 통지는 null** 이다 —
                    수신자의 「내 챌린지」 목록에 그 방이 없어 카운터가 뜰 자리가 없다.""")
            String challengeId,
            @Schema(description = "**고지 성립 시각** — 불변이다") String createdAt) {}
}
