package com.ruleup.ruleup_backend.score.service;

import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.common.outbox.*;
import com.ruleup.ruleup_backend.notification.*;
import com.ruleup.ruleup_backend.notification.service.*;
import com.ruleup.ruleup_backend.notification.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
@Component @RequiredArgsConstructor
public class ScoreTierNoticeHandler implements OutboxHandler {
    public static final String TYPE="SCORE_TIER_NOTICE";
    public record Payload(UUID eventId,UUID userId,String kind,String direction) {}
    private final NotificationPublisher notifications;
    @Override public String type(){return TYPE;}
    @Override public void handle(String raw) {
        var e=OutboxService.parse(raw,Payload.class);
        notifications.publish(NotificationEvent.of(e.userId(),"CHANGED".equals(e.kind())?NotificationType.TIER_CHANGED:NotificationType.TIER_BOUNDARY_NEAR,
                Map.of(NotificationParams.EVENT_KEY,e.eventId().toString(),NotificationParams.DIRECTION,e.direction())));
    }
}
