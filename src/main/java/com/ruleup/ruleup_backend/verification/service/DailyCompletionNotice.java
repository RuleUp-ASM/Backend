package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.verification.domain.Polarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationPolarity;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「오늘 루틴을 모두 완료했어요」 — 오늘 몫이 성공한 직후(sync 즉시 성공 · 수동 체크) 부른다.
 *
 * <h4>무엇을 세는가</h4>
 * 그날 판정 대상({@link VerificationTargetDays} EVALUATE)이고 셋업이 끝난 ACTIVE 멤버십 중 <b>목표 달성형</b>만.
 * 규칙 지키기형은 당일에 성공이 되지 않는다(위반 없이 하루가 끝나야, 확정은 이틀 뒤) — 셈에 넣으면 그 방이 있는
 * 사람은 영영 「모두 완료」를 못 받는다. 그래서 「해야 할 일」에서 뺀다.
 *
 * <h4>하루 한 번</h4>
 * 멱등 키가 {@code (user, date)} 다. 수동 체크를 취소했다가 다시 해도 두 번 쌓이지 않는다. 취소로 「모두 완료」가
 * 거짓이 되더라도 이미 적재된 알림은 그대로 둔다 — 알림은 그 순간의 사실이다.
 *
 * <h4>대상이 하나뿐이면 개별 성공 알림을 대신한다</h4>
 * 같은 사건을 두 번 알리지 않으려는 것이다(2026-10-13 결정). 부른 쪽이 {@link Result#replacesIndividual()} 를 보고
 * 개별 성공 알림을 건너뛴다.
 */
@Component
@RequiredArgsConstructor
public class DailyCompletionNotice {

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationConfigFactory configFactory;
    private final NotificationPublisher publisher;

    public record Result(boolean allDone, int required) {
        static final Result NONE = new Result(false, 0);

        /** 오늘 해야 할 루틴이 이것 하나뿐이라 「모두 완료」가 개별 성공을 대신한다. */
        public boolean replacesIndividual() {
            return allDone && required == 1;
        }
    }

    /** 같은 트랜잭션에서 오늘 행을 성공으로 바꾼 <b>뒤에</b> 부른다 — 조회가 그 변경을 먼저 flush 한다. */
    public Result afterSuccess(UUID userId, LocalDate date) {
        Map<UUID, VerificationStatus> statusByMember = new HashMap<>();
        for (VerificationDaily daily : dailyRepo.findByUserIdAndTargetDate(userId, date)) {
            statusByMember.put(daily.getChallengeMemberId(), daily.getStatus());
        }

        int required = 0;
        for (ChallengeMember member : challengeQuery.findActiveMemberships(userId)) {
            VerificationStatus status = statusByMember.get(member.getId());
            // 성공한 방은 판정 대상 여부를 다시 묻지 않는다 — 빈도형은 주 몫을 채우는 순간 오늘이
            // NOT_REQUIRED 로 바뀌어, 방금 성공한 방이 셈에서 빠진다.
            if (status == VerificationStatus.SUCCESS) {
                required++;
                continue;
            }
            if (status == VerificationStatus.NOT_TARGET || status == VerificationStatus.NOT_REQUIRED) continue;

            Challenge challenge = challengeQuery.findChallenge(member.getChallengeId()).orElse(null);
            if (challenge == null || challenge.getDeletedAt() != null) continue;
            VerificationConfig config = configFactory.build(challenge);
            // 자동 방은 셋업 전이면 평가 자체를 건너뛴다. 수동 방은 셋업 없이도 체크할 수 있어 센다.
            if (!config.isManual() && !member.isSetupReady()) continue;
            if (VerificationTargetDays.of(config, challenge, member, date) != VerificationTargetDays.Disposition.EVALUATE)
                continue;
            if (!config.isManual() && VerificationPolarity.of(config) == Polarity.CONSTRAINT) continue;
            return Result.NONE;   // 아직 남은 루틴이 있다
        }
        if (required == 0) return Result.NONE;

        publisher.publish(NotificationEvent.of(userId, NotificationType.DAILY_ROUTINES_COMPLETED,
                Map.of(NotificationParams.DATE, date.toString(),
                        NotificationParams.ROUTINE_COUNT, String.valueOf(required))));
        return new Result(true, required);
    }
}
