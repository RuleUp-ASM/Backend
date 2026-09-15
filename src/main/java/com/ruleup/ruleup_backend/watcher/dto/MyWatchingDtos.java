package com.ruleup.ruleup_backend.watcher.dto;
import java.util.List;
/** Read-only relations. Push preferences belong to user notification settings. */
public final class MyWatchingDtos {
    private MyWatchingDtos() {}
    public record ListResponse(List<Item> items) {}
    public record Item(String watcherId, String challengeTitle, String targetNickname, String status, String acceptedAt) {}
}
