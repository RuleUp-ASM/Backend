package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.FailExpectation;
import com.ruleup.ruleup_backend.verification.domain.Polarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.domain.VerificationPolarity;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 귀속일이 끝나 「실패 예정」이 된 건에 「이의제기가 필요해요」를 보낸다(QA NOTI-16).
 *
 * <h4>왜 배치인가</h4>
 * 실패 예정은 저장 상태가 아니라 계산 상태다({@link FailExpectation}). 위반이 잡혀 실패 예정이 되는 건
 * sync 가 그 자리에서 알리지만, <b>목표 달성형의 미달</b>은 신호가 아니라 시간(자정)이 만든다 —
 * 아무 사건도 일어나지 않으므로 발행할 자리가 없다. 그래서 자정이 지나면 어제 건을 한 번 훑는다.
 *
 * <h4>한 번만, 그러나 놓치지 않게</h4>
 * 1분마다 깨어 「오늘 아직 안 돌았으면」 돈다. 크론 한 번이면 그 시각에 배포 중이던 날은 통째로
 * 빠진다. 배치 이후 sync 로 새로 열리거나 실패 예정이 된 어제 건은 sync 가 즉시 알린다.
 * 인스턴스가 여럿이거나 재기동으로 다시 돌아도 {@code verification_id} 멱등 키가 두 번째
 * 적재를 막는다. 한밤중 발행이지만 야간 보류는 발송 단계가 처리한다 — 여기서 시각을 고르지 않는다.
 *
 * <p>대상은 어제 하루다. 실패 예정 창은 귀속일 다음 날 하루뿐이고(D+2 00:00 확정), 그보다 늦은
 * 건은 이미 확정 배치가 실패로 고지한다. 행이 아예 없는 날(그날 신호가 한 번도 안 온 멤버)은
 * 이의를 걸 건이 없으므로 대상이 아니다.
 */
@Service
@RequiredArgsConstructor
public class FailExpectedNoticeJob {

    private static final Logger log = LoggerFactory.getLogger(FailExpectedNoticeJob.class);
    private static final int PAGE = 500;

    private final VerificationDailyRepository dailyRepo;
    private final ChallengeQueryService challengeQuery;
    private final VerificationConfigFactory configFactory;
    private final NotificationPublisher notificationPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 이 인스턴스가 마지막으로 끝낸 기준일(=오늘). 재기동하면 비어 한 번 더 돌지만 멱등이다. */
    private final AtomicReference<LocalDate> doneFor = new AtomicReference<>();

    /**
     * 발행 이벤트 — sync 의 위반 경로와 이 배치가 같은 모양을 쓴다.
     *
     * <p>{@code date} 는 딥링크가 쓴다(캘린더 일자 상세). 멱등 키에는 들어가지 않는다.
     */
    public static NotificationEvent event(VerificationDaily daily) {
        return NotificationEvent.forChallenge(daily.getUserId(),
                NotificationType.VERIFICATION_FAIL_EXPECTED,
                daily.getChallengeId(),
                Map.of(NotificationParams.VERIFICATION_ID, daily.getId().toString(),
                        NotificationParams.CHALLENGE_ID, daily.getChallengeId().toString(),
                        NotificationParams.DATE, daily.getTargetDate().toString()));
    }

    @Scheduled(fixedDelay = 60_000)
    public void runIfNeeded() {
        LocalDate today = LocalDate.now(clock.withZone(VerificationDeadlines.KST));
        if (today.equals(doneFor.get())) return;
        try {
            notifyFor(today.minusDays(1));
            doneFor.set(today);
        } catch (RuntimeException e) {
            // 다음 tick 에 다시 돈다 — 이미 적재한 건은 멱등 키가 걸러 준다.
            log.warn("실패 예정 알림 배치 실패 — 다음 주기에 재시도한다. err={}", e.toString());
        }
    }

    /** @return 발행을 시도한 건 수(이미 적재된 건 포함) */
    public int notifyFor(LocalDate targetDate) {
        Instant now = clock.instant();
        Instant finalizeAfter = VerificationDeadlines.finalizeAfter(targetDate);
        Map<UUID, Optional<Polarity>> polarityByChallenge = new HashMap<>();
        UUID after = new UUID(0, 0);
        int total = 0;
        while (true) {
            List<VerificationDaily> page = dailyRepo.findPendingByFinalizeAfterPage(finalizeAfter, after, PAGE);
            if (page.isEmpty()) break;
            List<NotificationEvent> events = new ArrayList<>();
            for (VerificationDaily daily : page) {
                // 수동 인증 방은 빠진다 — 미체크가 곧 미수행이라 이의로 다툴 판정이 없다.
                Optional<Polarity> polarity = polarityByChallenge.computeIfAbsent(daily.getChallengeId(), this::autoPolarity);
                if (polarity.isEmpty()) continue;
                if (!FailExpectation.isExpected(daily.getStatus(), daily.getTargetDate(),
                        daily.getFailureReason(), polarity.get(), now)) continue;
                // 그 사이 방을 나갔으면 이의를 낼 자리가 없다.
                if (challengeQuery.findMember(daily.getChallengeMemberId())
                        .filter(com.ruleup.ruleup_backend.challenge.domain.ChallengeMember::isActive).isEmpty()) continue;
                events.add(event(daily));
            }
            if (!events.isEmpty()) transactionTemplate.executeWithoutResult(tx -> notificationPublisher.publishAll(events));
            total += events.size();
            after = page.getLast().getId();
        }
        if (total > 0) log.info("실패 예정 알림: 귀속일 {} 대상 {}건", targetDate, total);
        return total;
    }

    private Optional<Polarity> autoPolarity(UUID challengeId) {
        Challenge challenge = challengeQuery.findChallenge(challengeId).orElse(null);
        if (challenge == null) return Optional.empty();
        VerificationConfig config = configFactory.build(challenge);
        if (config.isManual()) return Optional.empty();
        return Optional.of(VerificationPolarity.of(config));
    }
}
