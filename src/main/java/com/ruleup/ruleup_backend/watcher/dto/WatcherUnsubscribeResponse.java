package com.ruleup.ruleup_backend.watcher.dto;

/** 수신거부 응답. reblockUntil = 동일 생성자 재초대 차단 해제 시각(+30일). */
public record WatcherUnsubscribeResponse(String status, String reblockUntil) {}
