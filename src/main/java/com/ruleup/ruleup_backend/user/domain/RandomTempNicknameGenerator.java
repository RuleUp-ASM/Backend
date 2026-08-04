package com.ruleup.ruleup_backend.user.domain;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** 기본 구현 — 난수 UUID 뒤 8자리 hex. */
@Component
public class RandomTempNicknameGenerator implements TempNicknameGenerator {

    @Override
    public String next() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(hex.length() - 8);
    }
}
