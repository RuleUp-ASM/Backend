package com.ruleup.ruleup_backend.challenge.domain;

/** 방장 주체. USER가 없는 방은 BOT이 임시 운영하며 정책상 선착순 claim 대상이다. */
public enum OwnerType {
    USER, BOT
}
