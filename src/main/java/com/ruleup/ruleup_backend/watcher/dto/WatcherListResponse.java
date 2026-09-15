package com.ruleup.ruleup_backend.watcher.dto;
import java.util.List;
public record WatcherListResponse(List<Item> watchers) {
    public record Item(String watcherId, String invitationId, String type, String channel,
                       String status, String displayName, String invitedAt, String acceptedAt, String expiresAt) {}
}
