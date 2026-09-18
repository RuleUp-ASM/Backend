package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.score.service.AppealCorrectionOutboxHandler;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.verification.domain.Appeal;
import com.ruleup.ruleup_backend.verification.domain.Polarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationPolarity;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.AppealResponse;
import com.ruleup.ruleup_backend.verification.dto.AppealSubmitRequest;
import com.ruleup.ruleup_backend.verification.repository.AppealRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 인증 이의 — <b>판정하지 않는다</b> (인증 정책 §5, 승인된 제안 "판정 대신 자동 인용 구제권").
 *
 * <p>이의의 대다수는 "실제로 했는데 측정이 틀렸다"이고, 그 진위는 봇도 사람도 검증할 수 없다.
 * 그래서 결정적인 형식 요건만 검사하고 통과하면 즉시 인용한다.
 * <ol>
 *   <li>본인의 인증인가</li>
 *   <li><b>실패로 확정됐거나 이대로면 실패인가</b> (완료된 건과 아직 채울 기회가 남은 건은 거절)</li>
 *   <li>기한 안인가 — 확정 시각과 같은 귀속일 이틀 뒤 00:00 KST</li>
 *   <li>사유가 10자 이상인가 (사진은 선택)</li>
 * </ol>
 *
 * <p>이의는 확정 <b>전에</b> 받는다. 확정이 귀속일 이틀 뒤이고 기한도 같은 시각이라, 실제 신청 창은
 * 귀속일이 끝난 뒤의 유예 하루다 — 유저는 "이대로면 실패"를 보고 신청한다.
 * 확정을 기다렸다가 받으면 기한이 이미 지나 구제 경로가 없어진다.
 * 이 네 가지 말고는 아무것도 보지 않는다 — LLM·방장·MANAGER 는 인용 여부를 판단하지 않고,
 * 횟수 한도도 없다. 남용은 인용과 분리된 이상탐지·운영 제재가 맡는다.
 *
 * <p>인용하면 실패를 완료로 정정하고 정상 성공과 같은 기준으로 진행률·통계·연속 기록을 다시 계산한다.
 */
@Service
@RequiredArgsConstructor
public class AppealService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final VerificationDailyRepository dailyRepo;
    private final AppealRepository appealRepo;
    private final OutboxService outbox;
    private final OutboxDispatcher outboxDispatcher;
    private final VerificationMetrics metrics;
    private final ChallengeQueryService challengeQuery;
    private final VerificationConfigFactory configFactory;
    private final VerificationProgressService progressService;
    private final StreakService streakService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public AppealResponse submit(UUID userId, UUID verificationId, AppealSubmitRequest request) {
        String reason = (request != null) ? request.reason() : null;

        // 남의 인증은 존재 자체를 알리지 않는다 — 본인 것이 아니면 없는 것과 같이 다룬다.
        VerificationDaily daily = dailyRepo.findById(verificationId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));

        // 인정률의 <b>분모</b>다. 형식 요건에서 걸린 건까지 세야 「인정률 급변」을 물을 수 있다.
        // 다만 <b>소유권 확인 뒤에</b> 센다 — 남의 판정이나 없는 판정을 찍어 보는 요청까지
        // 분모에 들어가면, 밖에서 분모를 부풀려 인정률을 낮춰 보이게 만들 수 있다.
        metrics.appealSubmitted();

        // 형식 요건 — 사유부터 본다. 미달이면 접수하지 않으므로 이력도 남지 않는다.
        if (!Appeal.isValidReason(reason)) throw new BusinessException(ErrorCode.INVALID_REASON);

        Instant now = Instant.now();
        Polarity polarity = polarityOf(daily.getChallengeId());
        // 실패로 확정됐거나 이대로면 실패인 건에만. 이미 인용돼 완료로 바뀐 건도 여기서 걸린다
        // (실패 결과 기준 멱등 — 두 번째 요청은 완료 상태라 대상이 아니다).
        if (!failing(daily, polarity, now)) throw new BusinessException(ErrorCode.NOT_FAILED);
        if (!daily.isAppealable(polarity, now)) throw new BusinessException(ErrorCode.APPEAL_WINDOW_CLOSED);

        Appeal appeal = saveAppeal(daily, userId, reason.trim(),
                (request != null) ? request.imageUrl() : null, now);

        // 인용 — 정상 성공과 동일하게 정정한다.
        daily.correctByAppeal(now);
        eventPublisher.publishEvent(new VerificationScoreEvents.Confirmed(daily));
        // 인정률의 <b>분자</b>는 커밋 이후에 센다. 여기서 올리면 뒤이은 진행률 갱신·아웃박스
        // 적재·알림 적재나 최종 커밋이 실패했을 때 DB 는 롤백되는데 지표에만 인용이 남아,
        // 인정률이 실제보다 높게 보인다 — 분모는 그대로이므로 그 차이가 그대로 왜곡이 된다.
        afterCommit(metrics::appealAccepted);
        ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId()).orElse(null);
        refreshProgress(member, daily);
        eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(daily.getChallengeId(), "APPEAL_ACCEPTED"));
        // 이상탐지는 인용 이후에 돈다 — 개별 인용을 지연하거나 뒤집지 않는다. 아웃박스에 실어
        // 프로세스가 내려가도 유실되지 않고, 지연·적체가 기존 게이지로 그대로 보이게 한다.
        outbox.enqueue(AppealAbuseOutboxHandler.OUTBOX_TYPE,
                new AppealAbuseOutboxHandler.Payload(
                        appeal.getId().toString(), userId.toString(), daily.getChallengeId().toString(),
                        daily.getId().toString(), daily.getTargetDate().toString(), now.toString()),
                AppealAbuseOutboxHandler.OUTBOX_TYPE + ":" + appeal.getId());
        // 점수 정정은 다르다. 사용자에게 "인용됐다"고 응답해 놓고 점수가 끝내 안 돌아오면
        // 되돌릴 방법이 없다 — 인용과 같은 커밋에 적어 두어야 재처리가 가능하다(공통 5-7).
        outbox.enqueue(AppealCorrectionOutboxHandler.OUTBOX_TYPE,
                new AppealCorrectionOutboxHandler.Payload(
                        userId.toString(), daily.getChallengeId().toString(),
                        daily.getId().toString(), daily.getTargetDate().toString()),
                AppealCorrectionOutboxHandler.OUTBOX_TYPE + ":" + daily.getId());
        outboxDispatcher.requestFlush();

        // 결과 고지. 응답으로도 알려 주지만 그것만으로는 부족하다 — 신청 화면을 떠난 뒤에
        // 정정 사실을 확인할 자리가 알림함뿐이다. 이의 하나에 결과는 하나라 appeal_id 가 곧 멱등 키다.
        notificationPublisher.publish(NotificationEvent.of(userId,
                NotificationType.APPEAL_RESULT,
                Map.of(NotificationParams.APPEAL_ID, appeal.getId().toString())));

        return new AppealResponse(
                appeal.getId().toString(),
                AppealResponse.ACCEPTED,
                new AppealResponse.Restored(
                        TodayStatusView.DONE,
                        streakService.around(daily.getChallengeMemberId(), daily.getTargetDate()).after(),
                        0)); // 점수는 커밋 이후 아웃박스에서 반영한다. 이 응답의 동기 지급분은 없다.
    }

    /** 실패로 확정됐거나 이대로면 실패인지 — 기한과 무관하게 "이의 대상인 판정"인지만 본다. */
    private boolean failing(VerificationDaily daily, Polarity polarity, Instant now) {
        return daily.getStatus() == VerificationStatus.FAILED || daily.isFailExpected(polarity, now);
    }

    /** 판정 방향은 챌린지 설정에서 온다 — 목표 달성형인지 규칙 지키기형인지로 실패 예정 여부가 갈린다. */
    private Polarity polarityOf(UUID challengeId) {
        return challengeQuery.findChallenge(challengeId)
                .map(configFactory::build)
                .map(VerificationPolarity::of)
                .orElse(Polarity.ACHIEVEMENT);
    }

    /**
     * 접수 저장. uq(verificationDailyId) 가 동시 요청에서도 한 건만 남긴다 —
     * 경합에서 진 요청은 이미 인용된 것과 같으므로 NOT_FAILED 로 돌려준다.
     */
    /**
     * 커밋이 실제로 끝난 뒤에만 실행한다. 롤백되면 아무 일도 일어나지 않는다 —
     * 트랜잭션 밖에서 불리면 그 자리에서 바로 실행한다.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private Appeal saveAppeal(VerificationDaily daily, UUID userId, String reason, String imageUrl, Instant now) {
        try {
            return appealRepo.saveAndFlush(Appeal.accept(
                    daily.getId(), daily.getChallengeId(), daily.getChallengeMemberId(),
                    userId, daily.getTargetDate(), reason, imageUrl, now));
        } catch (DataIntegrityViolationException e) {
            // 같은 판정에 두 번째 이의. 유일 제약이 막았다 — 중복 정정·중복 지급의 방어선이
            // 실제로 일하고 있는지는 이 값으로만 알 수 있다.
            metrics.appealDuplicateBlocked();
            throw new BusinessException(ErrorCode.NOT_FAILED);
        }
    }

    private void refreshProgress(ChallengeMember member, VerificationDaily daily) {
        if (member == null) return;
        if (daily.getTargetDate().equals(LocalDate.now(KST))) {
            progressService.recountAndSetToday(member, daily.getStatus());
        } else {
            progressService.recount(member);
        }
    }

}
