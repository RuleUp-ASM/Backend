package com.ruleup.ruleup_backend.watcher.domain;

import java.util.Arrays;
import java.util.Optional;

/** 응원·놀림 <b>2종뿐</b>. 한 통지에 <b>둘 다 보낼 수 없다</b>. */
public enum ReactionType {
    CHEER, TEASE;

    public static Optional<ReactionType> find(String raw) {
        return Arrays.stream(values()).filter(r -> r.name().equals(raw)).findFirst();
    }
}
