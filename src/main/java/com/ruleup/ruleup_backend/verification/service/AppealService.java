package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.Appeal;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 인증 이의 — <b>판정하지 않는다</b> (인증 정책 §5, 승인된 제안 "판정 대신 자동 인용 구제권").
 *
 * <p>이의의 대다수는 "실제로 했는데 측정이 틀렸다"이고, 그 진위는 봇도 사람도 검증할 수 없다.
 * 그래서 결정적인 형식 요건만 검사하고 통과하면 즉시 인용한다.
 * <ol>
 *   <li>본인의 인증인가</li>
 *   <li>실패로 <b>확정</b>됐는가 (이미 인용된 건 포함해 그 외는 전부 거절)</li>
 *   <li>기한 안인가 — 실패 확정일의 다음 날 00:00 KST</li>
 *   <li>사유가 10자 이상인가 (사진은 선택)</li>
 * </ol>
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
    private final ChallengeQueryService challengeQuery;
    private final VerificationProgressService progressService;
    private final StreakService streakService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public AppealResponse submit(UUID userId, UUID verificationId, AppealSubmitRequest request) {
        String reason = (request != null) ? request.reason() : null;

        // 남의 인증은 존재 자체를 알리지 않는다 — 본인 것이 아니면 없는 것과 같이 다룬다.
        VerificationDaily daily = dailyRepo.findById(verificationId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND));

        // 형식 요건 — 사유부터 본다. 미달이면 접수하지 않으므로 이력도 남지 않는다.
        if (!Appeal.isValidReason(reason)) throw new BusinessException(ErrorCode.INVALID_REASON);
        // 실패 확정 건에만. 이미 인용돼 완료로 바뀐 건도 여기서 걸린다(실패 결과 기준 멱등).
        if (daily.getStatus() != VerificationStatus.FAILED) throw new BusinessException(ErrorCode.NOT_FAILED);
        Instant now = Instant.now();
        if (!daily.isAppealable(now)) throw new BusinessException(ErrorCode.APPEAL_WINDOW_CLOSED);

        Appeal appeal = saveAppeal(daily, userId, reason.trim(),
                (request != null) ? request.imageUrl() : null, now);

        // 인용 — 정상 성공과 동일하게 정정한다.
        daily.correctByAppeal(now);
        ChallengeMember member = challengeQuery.findMember(daily.getChallengeMemberId()).orElse(null);
        refreshProgress(member, daily);
        eventPublisher.publishEvent(
                ChallengeStatsRefreshRequested.of(daily.getChallengeId(), "APPEAL_ACCEPTED"));
        // 이상탐지는 인용 이후 비동기로 돈다 — 개별 인용을 지연하거나 뒤집지 않는다.
        eventPublisher.publishEvent(new AppealAccepted(
                appeal.getId(), userId, daily.getChallengeId(), daily.getTargetDate(), now));

        return new AppealResponse(
                appeal.getId().toString(),
                AppealResponse.ACCEPTED,
                new AppealResponse.Restored(
                        TodayStatusView.DONE,
                        streakService.around(daily.getChallengeMemberId(), daily.getTargetDate()).after(),
                        scoreDeltaOf(daily)));
    }

    /**
     * 접수 저장. uq(verificationDailyId) 가 동시 요청에서도 한 건만 남긴다 —
     * 경합에서 진 요청은 이미 인용된 것과 같으므로 NOT_FAILED 로 돌려준다.
     */
    private Appeal saveAppeal(VerificationDaily daily, UUID userId, String reason, String imageUrl, Instant now) {
        try {
            return appealRepo.saveAndFlush(Appeal.accept(
                    daily.getId(), daily.getChallengeId(), daily.getChallengeMemberId(),
                    userId, daily.getTargetDate(), reason, imageUrl, now));
        } catch (DataIntegrityViolationException e) {
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

    /**
     * 인용으로 되돌아온 점수. 정상 성공과 같은 값이어야 한다.
     *
     * <p>지금은 인증 1건 단위 점수 지급이 점수 도메인에 없어 0 이다(테크스펙 Non-Goals: 티어 점수 계산 없음).
     * 사이클 결과에 따른 점수는 확정 인증 결과를 다시 읽어 계산되므로, 정정된 결과가 그대로 반영된다.
     */
    private int scoreDeltaOf(VerificationDaily daily) {
        return 0;
    }

    /** 인용 사실. 이상탐지 기록 등 인용 이후 처리를 트리거한다. */
    public record AppealAccepted(UUID appealId, UUID userId, UUID challengeId,
                                 LocalDate targetDate, Instant acceptedAt) {}
}
