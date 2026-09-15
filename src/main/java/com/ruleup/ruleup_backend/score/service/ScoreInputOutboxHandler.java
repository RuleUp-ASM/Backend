package com.ruleup.ruleup_backend.score.service;

import com.ruleup.ruleup_backend.score.ScoreInput;
import com.ruleup.ruleup_backend.score.ScoreKeys;
import com.ruleup.ruleup_backend.common.outbox.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
@Component @RequiredArgsConstructor
public class ScoreInputOutboxHandler implements OutboxHandler {
    public static final String TYPE="SCORE_INPUT";
    public record Payload(UUID userId,ScoreInput input) {}
    private final ScoreProcessor processor;
    @Override public String type(){return TYPE;}
    @Override public void handle(String raw) {
        var e=OutboxService.parse(raw,Payload.class);var inputs=List.of(e.input());
        processor.process(e.userId(),"source:"+ScoreKeys.source(e.userId(),e.input())+":"+e.input().sourceVersion(),processor.inputHash(inputs),inputs,false);
    }
}
