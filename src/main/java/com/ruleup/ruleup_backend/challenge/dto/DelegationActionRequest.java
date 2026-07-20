package com.ruleup.ruleup_backend.challenge.dto;

/** 위임 요청 응답 body (§7-2). action = ACCEPT / REJECT / CANCEL. */
public record DelegationActionRequest(String action) {}
