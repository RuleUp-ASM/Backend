package com.ruleup.ruleup_backend.watcher.dto;

import java.util.List;

/**
 * 감시자 목록(생성자). limit=무료 3(구독 시 null=무제한, 후속). 비유저 연락처는 마스킹만(§5.9).
 */
public record WatcherListResponse(
        String challengeId,
        Integer limit,
        List<Item> watchers
) {
    public record Item(
            String watcherId,
            String type,
            String channel,
            String status,
            String displayName,
            String contactMasked,
            String invitedAt,
            String expiresAt
    ) {}
}
