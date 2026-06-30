package com.ruleup.ruleup_backend.watcher.dto;

/** 비유저 동의 응답(채널 SMS, 생성자에겐 마스킹 번호). */
public record WatcherConsentResponse(String watcherId, String status, String channel, String phoneMasked) {}
