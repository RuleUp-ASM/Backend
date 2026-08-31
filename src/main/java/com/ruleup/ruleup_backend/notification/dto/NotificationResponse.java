package com.ruleup.ruleup_backend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 알림함 목록 — 커서 페이징. 보관 6개월을 넘긴 건은 응답에 없다. */
@Schema(name = "NotificationListResponse")
public record NotificationResponse(

        @Schema(description = "알림 목록 — 최신순", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Item> items,

        @Schema(description = "읽지 않은 건수", example = "3") long unreadCount,

        @Schema(description = "보관 기간(일). 이 기간을 넘긴 알림은 정리 배치가 지운다.", example = "180")
        int retentionDays,

        @Schema(description = "다음 페이지 커서. null 이면 마지막 페이지다.")
        String nextCursor) {

    @Schema(name = "NotificationItem")
    public record Item(
            String id,
            @Schema(description = "알림 분류 — A 필수 / B 기능 / C 마케팅", example = "A") String category,
            @Schema(description = "알림 타입", example = "ACCOUNT_SANCTION") String type,
            String title,
            @Schema(description = "본문 — 민감정보를 담지 않는다") String body,
            @Schema(description = "탭 시 진입 경로. 없으면 null.") String deeplink,
            boolean read,
            @Schema(description = "**고지 성립 시각** — 불변이다") String createdAt) {}

    @Schema(name = "NotificationReadResponse")
    public record ReadResponse(boolean read, long unreadCount) {}

    @Schema(name = "NotificationReadAllResponse")
    public record ReadAllResponse(long changed, long unreadCount) {}

    @Schema(name = "NotificationDeleteResponse")
    public record DeleteResponse(boolean deleted) {}
}
