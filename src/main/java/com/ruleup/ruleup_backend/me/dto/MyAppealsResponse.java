package com.ruleup.ruleup_backend.me.dto;

import java.util.List;

/** Accepted submissions only; there is no pending or rejected appeal state. */
public record MyAppealsResponse(List<Item> history) {
    public record Item(String appealId,String acceptedAt,String targetDate,String challengeId,String routineTitle,String reason) {}
}
