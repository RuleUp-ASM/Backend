package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.watcher.domain.Watcher;
import com.ruleup.ruleup_backend.watcher.domain.WatcherNotification;
import com.ruleup.ruleup_backend.watcher.domain.WatcherStatus;
import com.ruleup.ruleup_backend.watcher.repository.WatcherNotificationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 감시자 실패 통지 적재(§9/§8.2). 인증 실패 확정 시 ACTIVE 감시자별로 큐 1건씩 적재한다.
 *  - 발송은 별도 스윕({@link WatcherNotificationDispatcher})이 수행(트랜잭션 안에서 외부 호출 금지 §3).
 *  - 야간 디퍼(§8.2): 22:00~08:00(KST) 도달분은 그날(또는 다음날) 08:00 로 미뤄 묶어 발송.
 *  - 멱등(§9): 감시자×챌린지×날짜 유니크로 중복 적재 차단.
 */
@Service
@RequiredArgsConstructor
public class WatcherNotificationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime DEFER_SEND = LocalTime.of(8, 0);   // 야간 디퍼 발송 시각 08:00
    private static final int NIGHT_START = 22;                         // 22:00~
    private static final int NIGHT_END = 8;                            // ~08:00

    private final WatcherRepository watcherRepository;
    private final WatcherNotificationRepository notificationRepository;

    /**
     * 실패 확정 1건 → 해당 감시 대상(failedUserId)을 보는 ACTIVE 감시자들에게 통지 적재.
     * 호출자(이벤트 리스너)의 트랜잭션 안에서 DB만 건드린다(외부 호출 없음).
     */
    public void enqueueForFailure(UUID challengeId, UUID failedUserId, LocalDate targetDate, Instant confirmedAt) {
        List<Watcher> watchers = watcherRepository.findByChallengeIdAndInviterUserIdAndStatus(
                challengeId, failedUserId, WatcherStatus.ACTIVE);
        if (watchers.isEmpty()) return;

        Instant scheduledAt = nightDeferred(confirmedAt);
        for (Watcher w : watchers) {
            // 멱등: 같은 감시자×챌린지×날짜가 이미 적재됐으면 건너뛴다(실패 이벤트당 1회).
            if (notificationRepository.existsByWatcherIdAndChallengeIdAndTargetDate(w.getId(), challengeId, targetDate))
                continue;
            notificationRepository.save(WatcherNotification.enqueue(
                    w.getId(), challengeId, failedUserId, targetDate, w.getChannel(), scheduledAt));
        }
    }

    /** 22:00~08:00(KST) 도달분은 가장 가까운 08:00 으로 미룬다. 그 외는 즉시(confirmedAt). */
    private Instant nightDeferred(Instant confirmedAt) {
        ZonedDateTime kst = confirmedAt.atZone(KST);
        int hour = kst.getHour();
        boolean night = (hour >= NIGHT_START) || (hour < NIGHT_END);
        if (!night) return confirmedAt;
        // 22~24시면 다음날 08:00, 00~08시면 같은 날 08:00.
        ZonedDateTime send = (hour >= NIGHT_START)
                ? kst.toLocalDate().plusDays(1).atTime(DEFER_SEND).atZone(KST)
                : kst.toLocalDate().atTime(DEFER_SEND).atZone(KST);
        return send.toInstant();
    }
}
