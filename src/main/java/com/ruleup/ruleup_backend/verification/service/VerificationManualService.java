package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationResponse;
import com.ruleup.ruleup_backend.verification.dto.StreakChange;
import com.ruleup.ruleup_backend.verification.dto.VerificationCancelResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 수동 인증(자체 체크) 제출·취소.
 *
 * <p><b>수동 방에서만</b> 쓴다 — 자동 방의 수동 폴백은 폐기됐고, 자동 방의 실패 구제는 이의 제기가 담당한다.
 * 자동 방에서 부르면 NOT_MANUAL_CHALLENGE.
 *
 * <p>별도 부정 방지 장치는 두지 않는다 — 제출 즉시 인정(치팅 가능성은 정책적으로 수용, AI 호출 비용도 안 쓴다).
 * 대신 <b>당일(KST) 마감</b>이라 날짜가 지나면 체크도 취소도 불가하다.
 * 점수 패널티는 수동 방 고정 OFF라 점수 변동이 없지만(scoreNote=MANUAL_NO_SCORE),
 * 성공률·랭킹·통계에는 포함된다.
 */
@Service
@RequiredArgsConstructor
public class VerificationManualService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 메모 상한. 앱 입력칸과 같은 값이며, 여기가 실제 계약이다. */
    private static final int MAX_NOTE_LENGTH = 200;

    private final ChallengeQueryService challengeQuery;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationConfigFactory configFactory;
    private final VerificationMemberSetup memberSetup;
    private final VerificationProgressService progressService;
    private final StreakService streakService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;

    // ===== POST /api/v1/challenges/{challengeId}/verifications =====
    @Transactional
    public ManualVerificationResponse submit(UUID userId, UUID challengeId, ManualVerificationRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);

        VerificationConfig config = configFactory.build(ch);
        if (!config.isManual()) {
            throw new BusinessException(ErrorCode.NOT_MANUAL_CHALLENGE);
        }
        // 메모 길이는 <b>서버가</b> 막는다. 앱 입력칸의 200자 제한은 그 앱에서만 유효해서,
        // 다른 클라이언트가 임의 길이 텍스트를 근거 JSON 에 쌓을 수 있었다.
        String note = (req != null) ? req.note() : null;
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(ErrorCode.NOTE_TOO_LONG);
        }

        // 당일 마감 — targetDate 는 오늘만 허용한다(생략하면 오늘).
        LocalDate today = LocalDate.now(KST);
        LocalDate targetDate = parseTargetDate(req != null ? req.targetDate() : null, today);
        if (!targetDate.equals(today)
                || targetDate.isBefore(ch.getStartDate()) || (ch.getEndDate() != null && targetDate.isAfter(ch.getEndDate()))) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_DATE);
        }

        if (member.getTargetDays() == 0) memberSetup.apply(member, ch, config);

        // 오늘이 인증하는 날인지는 sync·확정 배치와 같은 판단을 쓴다. 여기에만 이 검사가 없어서
        // 비대상일에 제출해도 DONE 이 찍혔고, 그 결과 방 상세(NOT_TARGET)와 답이 갈렸다(QA MAN-13).
        if (VerificationTargetDays.of(config, ch, member, targetDate) != VerificationTargetDays.Disposition.EVALUATE) {
            throw new BusinessException(ErrorCode.NOT_TARGET_DATE);
        }

        VerificationDaily daily = dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), targetDate)
                .orElseGet(() -> dailyRepo.save(
                        VerificationDaily.open(member.getId(), ch.getId(), member.getUserId(), targetDate)));
        if (daily.getStatus() == VerificationStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.ALREADY_VERIFIED);       // 하루 1회
        }

        int streakBefore = streakService.around(member.getId(), targetDate).before();

        String method = VerificationMethod.SELF_CHECK.name();
        Instant now = Instant.now();
        VerificationMethodResult mr = methodResultRepo
                .findByVerificationDailyIdAndMethod(daily.getId(), method)
                .orElseGet(() -> VerificationMethodResult.create(daily.getId(), method, null, true));
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("selfCheck", true);
        if (note != null && !note.isBlank()) evidence.put("note", note);
        mr.evaluate(VerificationStatus.SUCCESS, evidence, now);
        methodResultRepo.save(mr);

        daily.recordManual(method, now);
        daily.acknowledge(now);   // 본인이 직접 체크한 결과라 확인할 모달이 없다
        // 빈도형 주기 카운터는 sync 경로에만 있었다. 수동 방은 sync 를 타지 않으므로 이 값이
        // 영영 0 에 머물렀고, 그래서 주 몫을 다 채운 뒤에도 today 가 계속 「할 차례」라고 답했다.
        if (config.isFrequency()) member.incrementPeriodCompleted();
        progressService.updateAfterSync(member, VerificationStatus.SUCCESS, now);
        eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "MANUAL_SUCCESS"));

        // 수동 체크 성공도 판정 결과다. 확정 배치는 미확정 건만 집어가고 즉시 확정된 건은
        // finalizeOne 초입의 isTerminal() 에서 걸러지므로, 여기서 고지하지 않으면 성공한 날은
        // 알림이 영영 없다. verification_id 가 멱등 키라 취소 후 다시 체크해도 한 번만 적재된다.
        notificationPublisher.publish(NotificationEvent.forChallenge(userId,
                NotificationType.VERIFICATION_RESULT,
                challengeId,
                Map.of(NotificationParams.VARIANT, "MANUAL_SUCCESS",
                        NotificationParams.VERIFICATION_ID, daily.getId().toString(),
                        NotificationParams.CHALLENGE_ID, challengeId.toString())));

        return new ManualVerificationResponse(
                daily.getId().toString(), targetDate.toString(), "DONE",
                new StreakChange(streakBefore, streakBefore + 1),
                ManualVerificationResponse.MANUAL_NO_SCORE);
    }

    // ===== DELETE /api/v1/verifications/{verificationId} =====
    /**
     * 수동 체크 취소 — "당일 마감" 정책의 취소 경로. 해당 날짜(KST)가 지나면 불가하고,
     * 자동 판정 건은 대상이 아니다. 취소하면 그 날짜는 다시 IN_PROGRESS 로 돌아간다.
     */
    @Transactional
    public VerificationCancelResponse cancel(UUID userId, UUID verificationId) {
        VerificationDaily daily = dailyRepo.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));
        if (!daily.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);   // 본인 건이 아님 — 존재를 알리지 않는다
        }
        if (!daily.isManualVerification()) {
            throw new BusinessException(ErrorCode.NOT_MANUAL_VERIFICATION);
        }
        if (!daily.getTargetDate().equals(LocalDate.now(KST))) {
            throw new BusinessException(ErrorCode.CANCEL_WINDOW_CLOSED);
        }

        methodResultRepo.findByVerificationDailyIdAndMethod(
                        daily.getId(), VerificationMethod.SELF_CHECK.name())
                .ifPresent(methodResultRepo::delete);
        daily.cancelManual();

        ChallengeMember member = challengeQuery.findMembership(daily.getChallengeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER));
        // 체크를 되돌렸으면 주기 몫도 되돌린다. 안 그러면 취소한 뒤에도 그 주는 계속
        // 「다 했다」로 남아 다시 체크할 길이 막힌다.
        if (member.getScheduleType() == ScheduleType.FREQUENCY) member.decrementPeriodCompleted();
        progressService.recountAndSetToday(member, VerificationStatus.PENDING);
        eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(daily.getChallengeId(), "MANUAL_CANCELED"));

        return new VerificationCancelResponse(true);
    }

    private ChallengeMember activeMember(UUID challengeId, UUID userId) {
        ChallengeMember member = challengeQuery.findMembership(challengeId, userId).orElse(null);
        if (member == null || !member.isActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }
        return member;
    }

    private LocalDate parseTargetDate(String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) return today;
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_DATE);
        }
    }
}
