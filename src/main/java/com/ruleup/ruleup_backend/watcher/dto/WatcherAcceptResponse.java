package com.ruleup.ruleup_backend.watcher.dto;

/** 유저 수락 응답(채널 IN_APP). */
public record WatcherAcceptResponse(String watcherId, String status, String channel) {}
