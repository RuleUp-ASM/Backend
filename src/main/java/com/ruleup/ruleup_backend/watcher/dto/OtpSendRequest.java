package com.ruleup.ruleup_backend.watcher.dto;

/** 비유저 OTP 발송 요청 — 감시자 본인이 웹에서 입력한 번호(§5.9, 생성자가 넘긴 값 사용 금지). */
public record OtpSendRequest(String phone) {}
