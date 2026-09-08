package com.ruleup.ruleup_backend.notification.reminder;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationMuteRepository;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.VerificationConfigFactory;
import com.ruleup.ruleup_backend.verification.service.VerificationTargetDays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 루틴 리마인더 — 08:00 · 12:00 · 19:00 KST.
 *
 * <h4>유저당 슬롯당 한 건</h4>
 * {@code dedup_key = ROUTINE_REMINDER:{user_id}:{date}:{slot}} 에 챌린지가 없다. 스펙의 규모
 * 산정(2만 명 × 3슬롯 = <b>일 최대 6만 건</b>)이 이 모양을 전제한다 — 방마다 보내면 18만이 된다.
 * 그래서 여러 방에 미인증 루틴이 있으면 한 건으로 묶고, 딥링크는 그중 한 방을 가리킨다
 * (공통 8절이 「홈 오늘 탭이 아니라 방으로」를 요구하므로 방을 가리켜야 한다).
 *
 * <h4>음소거한 방은 집계에서 빠진다</h4>
 * 참여 챌린지를 전부 음소거하면 <b>리마인더 자체가 발송되지 않는다</b>. 발송 단계의 음소거
 * 판정과 달리 여기는 <b>적재 자체를 하지 않는</b> 것이라, 알림 센터에도 남지 않는다 —
 * 절대 규칙 1의 예외가 아니라 「보낼 알림이 애초에 없는」 상태다.
 *
 * <h4>판정 대상일은 하나의 근거만 쓴다</h4>
 * {@link VerificationTargetDays} 를 그대로 부른다. 여기서 요일·빈도 계산을 다시 구현하면
 * 00시 판정 배치와 답이 갈려 「알림은 왔는데 판정 대상이 아니었다」가 생긴다.
 */
@Slf4j
@Component
public class RoutineReminderBatch {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 한 번에 훑을 멤버십 수. 2만 명 × 3방 = 6만이라 넉넉히 잡는다. */
    private static final int SCAN_LIMIT = 200_000;

    /** 한 트랜잭션에 담는 적재 수. */
    private static final int CHUNK = 500;

    private final ChallengeQueryService challengeQueryService;
    private final ChallengeRepository challengeRepository;
    private final VerificationDailyRepository dailyRepository;
    private final NotificationMuteRepository muteRepository;
    private final NotificationPublisher publisher;
    private final VerificationConfigFactory configFactory;
    private final RoutineReminderBatch self;

    public RoutineReminderBatch(ChallengeQueryService challengeQueryService,
                                ChallengeRepository challengeRepository,
                                VerificationDailyRepository dailyRepository,
                                NotificationMuteRepository muteRepository,
                                NotificationPublisher publisher,
                                VerificationConfigFactory configFactory,
                                @org.springframework.context.annotation.Lazy RoutineReminderBatch self) {
        this.challengeQueryService = challengeQueryService;
        this.challengeRepository = challengeRepository;
        this.dailyRepository = dailyRepository;
        this.muteRepository = muteRepository;
        this.publisher = publisher;
        this.configFactory = configFactory;
        this.self = self;
    }

    @Scheduled(cron = "0 0 8,12,19 * * *", zone = "Asia/Seoul")
    public int sendScheduled() {
        return send(Instant.now());
    }

    /**
     * 한 슬롯을 처리한다.
     *
     * @return 실제로 적재된 리마인더 수. 재실행이면 {@code dedup_key} 에 막혀 0 이다.
     */
    public int send(Instant now) {
        LocalDate today = LocalDate.now(KST);
        ReminderSlot slot = ReminderSlot.at(now);

        Map<UUID, UUID> targets = pendingTargets(today);
        if (targets.isEmpty()) {
            log.info("루틴 리마인더 {} — 대상 없음", slot);
            return 0;
        }

        int stored = 0;
        List<Map.Entry<UUID, UUID>> entries = new ArrayList<>(targets.entrySet());
        for (int from = 0; from < entries.size(); from += CHUNK) {
            stored += self.storeChunk(
                    entries.subList(from, Math.min(from + CHUNK, entries.size())), today, slot);
        }
        log.info("루틴 리마인더 {} — 대상 {}명, 적재 {}건", slot, targets.size(), stored);
        return stored;
    }

    /**
     * 오늘 아직 인증하지 않은 루틴을 가진 유저 → 대표 챌린지.
     *
     * <p>대표는 <b>가장 작은 챌린지 id</b>다. 오늘 마감은 전부 23:59 KST 로 같아 「가장 급한 방」
     * 이라는 기준이 없고, 임의로 고르면 실행마다 딥링크가 바뀌어 재시도 판정이 흔들린다.
     */
    private Map<UUID, UUID> pendingTargets(LocalDate today) {
        List<ChallengeMember> members = challengeQueryService.findActiveOnDate(today, SCAN_LIMIT);
        if (members.isEmpty()) return Map.of();

        Map<UUID, Challenge> challenges = new HashMap<>();
        challengeRepository.findAllById(members.stream()
                        .map(ChallengeMember::getChallengeId).distinct().toList())
                .forEach(c -> challenges.put(c.getId(), c));

        List<UUID> userIds = members.stream().map(ChallengeMember::getUserId).distinct().toList();
        Map<UUID, Set<UUID>> muted = new HashMap<>();
        muteRepository.findByUserIdIn(userIds).forEach(m -> muted
                .computeIfAbsent(m.getUserId(), k -> new HashSet<>()).add(m.getChallengeId()));

        Set<UUID> settled = settledMemberIds(userIds, today);

        // LinkedHashMap 이라 삽입 순서가 유지된다. 정렬은 아래에서 챌린지 id 로 한 번 더 한다.
        Map<UUID, UUID> byUser = new LinkedHashMap<>();
        for (ChallengeMember member : members) {
            if (settled.contains(member.getId())) continue;   // 이미 인증했거나 대상이 아니다

            Challenge challenge = challenges.get(member.getChallengeId());
            if (challenge == null || challenge.getDeletedAt() != null) continue;
            if (muted.getOrDefault(member.getUserId(), Set.of()).contains(challenge.getId()))
                continue;   // 음소거한 방의 루틴은 집계에서 빠진다

            if (VerificationTargetDays.of(configFactory.build(challenge), challenge, member, today)
                    != VerificationTargetDays.Disposition.EVALUATE) continue;

            byUser.merge(member.getUserId(), challenge.getId(),
                    (kept, candidate) -> kept.compareTo(candidate) <= 0 ? kept : candidate);
        }
        return byUser;
    }

    /**
     * 오늘 이미 결론이 난 멤버십 — 인증했거나 대상이 아닌 건이다. 유저 단위로 <b>한 번에</b> 읽는다.
     * 건당 조회하면 6만 멤버십에서 N+1 이 난다.
     */
    private Set<UUID> settledMemberIds(List<UUID> userIds, LocalDate today) {
        Set<UUID> settled = new HashSet<>();
        for (VerificationDaily daily : dailyRepository.findByUserIdInAndTargetDate(userIds, today)) {
            if (daily.getStatus() != VerificationStatus.PENDING)
                settled.add(daily.getChallengeMemberId());
        }
        return settled;
    }

    /** 청크 하나를 자기 트랜잭션에서 적재한다. 중단돼도 이미 커밋된 청크는 남는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int storeChunk(List<Map.Entry<UUID, UUID>> targets, LocalDate date, ReminderSlot slot) {
        return publisher.publishAll(targets.stream()
                .map(entry -> NotificationEvent.forChallenge(entry.getKey(),
                        NotificationType.ROUTINE_REMINDER,
                        "오늘 인증할 루틴이 남아 있어요",
                        "아직 인증하지 않은 루틴이 있어요. 오늘이 지나기 전에 확인해주세요.",
                        entry.getValue(),
                        Map.of(NotificationParams.CHALLENGE_ID, entry.getValue().toString(),
                                NotificationParams.DATE, date.toString(),
                                NotificationParams.SLOT, slot.name())))
                .toList()).size();
    }
}
