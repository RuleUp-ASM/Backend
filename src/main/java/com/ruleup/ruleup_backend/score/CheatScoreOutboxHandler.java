package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.score.domain.IncidentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 부정행위 검출 확정 → <b>−50 감점</b> 집행 (공통 5-7 · 점수 정책 §4.8). <b>아웃박스로 받는다.</b>
 *
 * <p>사건성 감점이라 사이클 순변동 ±20 한도를 거치지 않고 즉시 전액 반영한다 — 한도는 「한 주에
 * 얼마나 움직일 수 있나」를 다루는 장치인데 부정행위는 주간 성과가 아니기 때문이다.
 *
 * <p>검출 id 를 멱등 키로 넘긴다. 같은 검출이 두 번 전달돼도 −50 은 한 번만 반영되므로
 * 아웃박스의 at-least-once 재시도와 맞물려도 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheatScoreOutboxHandler implements OutboxHandler {

    /** 아웃박스 라우팅 키. */
    public static final String OUTBOX_TYPE = "CHEAT_DETECTION_SCORE";

    private final ScoreService scoreService;

    public record Payload(String userId, String challengeId, String detectionId, java.time.Instant effectiveAt, String authType) {
        public Payload(String userId,String challengeId,String detectionId) { this(userId,challengeId,detectionId,null,null); }
    }

    @Override
    public String type() {
        return OUTBOX_TYPE;
    }

    @Override
    public void handle(String payload) {
        Payload event = OutboxService.parse(payload, Payload.class);
        if(event.effectiveAt()==null || event.authType()==null)throw new IllegalStateException("SCORE_INCIDENT_SNAPSHOT_MISSING");
        scoreService.submitIncident(UUID.fromString(event.userId()),new ScoreInput(ScoreInput.Kind.INCIDENT,"CHEAT_DETECTED:"+event.detectionId(),1,event.effectiveAt(),event.authType(),null,null,null,
                IncidentType.CHEAT_DETECTED,UUID.fromString(event.challengeId()),-50,0,false));
        log.info("부정행위 감점 집행 userId={} detectionId={}", event.userId(), event.detectionId());
    }
}
