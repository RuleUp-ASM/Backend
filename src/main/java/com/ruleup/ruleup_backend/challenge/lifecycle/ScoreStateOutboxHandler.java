package com.ruleup.ruleup_backend.challenge.lifecycle;
import com.ruleup.ruleup_backend.common.outbox.*;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;
@Component @RequiredArgsConstructor
public class ScoreStateOutboxHandler implements OutboxHandler {
    public static final String TYPE="ACCOUNT_TIER_STATE_CHANGED";
    public record Payload(UUID eventId,UUID userId,long stateVersion,int score,Tier actualTier,Tier displayTier,boolean correction) {}
    private final ChallengeMemberService memberships;
    @Override public String type(){return TYPE;}
    @Override public void handle(String raw) {
        var e=OutboxService.parse(raw,Payload.class);
        memberships.leaveAllExternally(e.userId(),"TIER_GATE");
    }
}
