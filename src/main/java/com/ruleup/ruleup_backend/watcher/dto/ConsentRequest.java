package com.ruleup.ruleup_backend.watcher.dto;

/** 비유저 동의 요청(OTP 검증 + 수신동의). consent=true 필수. */
public record ConsentRequest(String otpId, String otpCode, boolean consent) {}
