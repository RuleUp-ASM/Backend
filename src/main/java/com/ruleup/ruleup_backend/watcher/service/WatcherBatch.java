package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.watcher.domain.WatcherInvitation;
import com.ruleup.ruleup_backend.watcher.domain.WatcherRelation;
import com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 감시자 배치 — 관계 자동 제거와 초대 만료 처리.
 *
 * <p><b>자동 제거가 안전장치다.</b> 유저가 관계를 끊는 기능이 없으므로, 루틴이 끝났는데도
 * 관계가 살아 있으면 통지가 계속 나간다. 이 배치의 정확도가 곧 수신거부권이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatcherBatch {

    private static final int BATCH_SIZE = 500;

    private final WatcherRelationRepository relationRepository;
    private final WatcherInvitationRepository invitationRepository;
    private final ChallengeRepository challengeRepository;
    private final NotificationPublisher notificationPublisher;

    /**
     * 종료된 챌린지의 관계를 제거한다. 멱등하며 이미 제거된 행은 건드리지 않는다.
     *
     * <p>02:00~03:00 점검 창과 00시 판정 배치를 피해 04:10 에 돈다.
     */
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public int removeFinishedRelations() {
        List<UUID> finished = challengeRepository.findAll().stream()
                .filter(c -> isFinished(c))
                .map(Challenge::getId)
                .toList();
        if (finished.isEmpty()) return 0;

        Instant now = Instant.now();
        List<WatcherRelation> live = relationRepository.findLiveByChallengeIds(finished);
        live.forEach(r -> r.remove(now));
        if (!live.isEmpty()) log.info("감시자 관계 자동 제거 — {}건", live.size());
        return live.size();
    }

    private boolean isFinished(Challenge c) {
        return c.getStatus() == ChallengeStatus.COMPLETED || c.getDeletedAt() != null;
    }

    /**
     * 만료된 초대를 <b>생성자에게만</b> 알린다.
     *
     * <p>감시자 후보에게는 어떤 알림도 보내지 않는다 — 아직 동의하지 않은 외부인이고,
     * "당신을 초대한 링크가 만료됐다"는 연락 자체가 무동의 접촉이다.
     */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public int notifyExpiredInvitations() {
        Instant now = Instant.now();
        List<WatcherInvitation> expired =
                invitationRepository.findExpiredUnnotified(now, Limit.of(BATCH_SIZE));
        if (expired.isEmpty()) return 0;

        for (WatcherInvitation invitation : expired) {
            notificationPublisher.publish(NotificationEvent.forChallenge(
                    invitation.getInviterUserId(),
                    NotificationType.WATCHER_INVITATION_EXPIRED,
                    "감시자 초대가 만료됐어요",
                    "보내신 감시자 초대 링크가 7일이 지나 만료됐어요. 필요하면 다시 초대해주세요.",
                    invitation.getChallengeId()));
            invitation.markExpiryNotified(now);   // 중복 발송 방지
        }
        log.info("감시자 초대 만료 알림 — {}건", expired.size());
        return expired.size();
    }
}
