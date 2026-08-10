package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.*;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationRequested;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.routine.service.ResolvedRoutine;
import com.ruleup.ruleup_backend.routine.service.RoutineSelectionService;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 챌린지 생성/조회/수정/삭제 (스펙 3.2 ~ 3.5).
 *  - 생성: 사용자 확정값 검증·보정 → RECRUITING 저장 + 생성자 OWNER 등록.
 *  - 수정/삭제: 시작 전(RECRUITING)만, OWNER만. 그룹에 다른 멤버가 있으면 불가(스펙 3.4/3.5).
 *  - 통계/참여자격은 현재 상태로 계산 (완주율은 항상 null).
 */
@Service
@RequiredArgsConstructor
public class ChallengeService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final RoutineSelectionService routineSelectionService;
    private final VerificationDailyRepository verificationDailyRepository;
    private final ChallengeHardDeleter hardDeleter;
    private final ApplicationEventPublisher eventPublisher;

    /** 하루 경계 계산의 사용자 로컬 = MVP는 KST 고정. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // ===== 내 챌린지 목록(내가 참여 중인 챌린지) =====

    /**
     * 내가 참여 중인 챌린지 목록. 멤버십 기준이라 페이지네이션 없이 전량 반환한다.
     * 승인제 폐기로 멤버십은 항상 확정(ACTIVE) — scope/memberStatus 개념 없음. 탈퇴(LEFT)는 제외.
     * 항목마다 내 역할(myRole: OWNER/MANAGER/MEMBER)을 함께 내려준다.
     */
    @Transactional(readOnly = true)
    public ChallengeListResponse myChallenges(UUID userId) {
        List<ChallengeMember> memberships = memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE);

        List<ChallengeListResponse.Item> items = new ArrayList<>();
        for (ChallengeMember m : memberships) {
            Challenge ch = challengeRepository.findByIdAndDeletedAtIsNull(m.getChallengeId()).orElse(null);
            if (ch == null) continue;   // 삭제/정합성 깨진 멤버십은 건너뜀
            items.add(ChallengeListResponse.Item.of(ch, m.getRole().name()));
        }
        return new ChallengeListResponse(items);
    }

    /** 그룹 기준 매너 온도가 생성자 본인 온도보다 높으면 거부 (생성/수정 공용, §3). */
    private void checkMinMannerNotAboveOwner(UUID ownerId, BigDecimal minManner) {
        if (minManner == null) return;
        if (mannerTemp(ownerId).compareTo(minManner) < 0) {
            throw new BusinessException(ErrorCode.MIN_TEMP_EXCEEDS_OWNER);
        }
    }

    /** 정원 검증(§3·§4): GROUP은 최대 참여 인원 필수(≥1). SOLO는 1 고정이라 입력 무시. */
    private void validateMaxParticipants(ParticipationType participationType, Integer maxParticipants) {
        if (participationType != ParticipationType.GROUP) return;
        if (maxParticipants == null || maxParticipants < 1)
            throw new BusinessException(ErrorCode.MAX_PARTICIPANTS_REQUIRED);
    }

    // ===== §3 상세 + 참여 자격 =====
    @Transactional(readOnly = true)
    public ChallengeDetailResponse getDetail(UUID userId, UUID challengeId) {
        Challenge c = loadActive(challengeId);
        // 심사 중·거부여도 기능 제한 없음(구 비노출 게이트 폐기) — 타인 화면만 대체 표시로 가린다.
        boolean ownerView = c.isOwner(userId);

        // 생성자 닉네임: 검수 전/거절이면 임시 닉네임(visibleNicknameTo) + 익명이면 추가 마스킹(§11.2).
        String ownerNickname = userRepository.findById(c.getCreatorId())
                .map(u -> c.getAnonymity().maskNickname(u.visibleNicknameTo(userId)))
                .orElse(null);

        // 통계
        BigDecimal avgManner = averageActiveMannerTemperature(challengeId);
        var stats = new ChallengeDetailResponse.Stats(
                c.getParticipantCount(), avgManner, /* completionRate */ null);

        // 참여 자격: 정원·기준온도·재참여·모더레이션·상태 종합.
        ChallengeMember myMembership = memberRepository.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        boolean isActiveMember = myMembership != null && myMembership.isActive();
        boolean rejoinBlocked = myMembership != null && !myMembership.isActive();   // LEFT/REMOVED 이력
        BigDecimal myManner = mannerTemp(userId);
        boolean meetsMinManner = !c.isGroup() || c.getMinMannerTemperature() == null
                || myManner.compareTo(c.getMinMannerTemperature()) >= 0;
        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        boolean hasRoom = c.getMaxParticipants() == null || activeCount < c.getMaxParticipants();
        boolean canJoin = !isActiveMember && !rejoinBlocked && !c.isOwner(userId)
                && meetsMinManner && hasRoom
                && c.getStatus() != ChallengeStatus.COMPLETED
                && c.isModerationCleared();

        var eligibility = new ChallengeDetailResponse.Eligibility(
                canJoin, myManner, c.getMinMannerTemperature(), rejoinBlocked);

        // 구 1시간 수정창(fixDeadline) 폐기 — 계약 필드는 호환을 위해 null 고정.
        String fixDeadline = null;

        // 요청자의 역할: OWNER / MANAGER / MEMBER / NONE.
        String myRole = c.isOwner(userId) ? MemberRole.OWNER.name()
                : (isActiveMember ? myMembership.getRole().name() : "NONE");

        return new ChallengeDetailResponse(
                c.getId().toString(),
                ownerView ? c.getTitle() : c.publicTitle(),
                ownerView ? c.getDescription() : c.publicDescription(),
                ownerView ? c.getImageUrl() : c.publicImageUrl(),
                c.getCategory(), c.getParticipationType().name(), c.getStatus().name(),
                c.getModerationStatus().name(), fixDeadline, c.getAnonymity().name(),
                new ChallengeDetailResponse.Owner(ownerNickname),
                c.getRepeatDays(), c.getDurationDays(),
                c.getStartDate().toString(), c.getEndDate().toString(),
                c.getTemplateId(), c.getVerificationConfig(), c.getParams(),
                c.getPenalty(), c.getReward(),
                c.getMaxParticipants(), stats, eligibility, myRole);
    }

    // ===== §4 수정 (OWNER만) =====
    /**
     * 챌린지 수정. UPCOMING + 멤버(본인 제외) 0명이면 전 항목, 그 외(멤버 존재 또는 진행 중)면 maxParticipants만.
     * 인원 상한 외 필드가 포함됐는데 전 항목 수정 불가 상태면 CHALLENGE_NOT_EDITABLE.
     */
    @Transactional
    public ChallengeResponse update(UUID userId, UUID challengeId, UpdateChallengeRequest req) {
        // 인원 상한 축소 하한(현재 인원) 판정을 위해 행 잠금으로 로드.
        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ensureOwner(c, userId);

        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        // "전 항목 수정 가능" = 시작 전(UPCOMING) + 멤버(본인 제외) 0명.
        boolean fullEditable = c.isUpcoming() && activeCount <= 1;

        boolean touchesOtherFields = req.title() != null || req.description() != null || req.imageUrl() != null
                || req.category() != null || req.repeatDays() != null || req.durationDays() != null
                || req.startDate() != null || req.penalty() != null || req.reward() != null
                || req.minMannerTemperature() != null || req.params() != null;
        if (touchesOtherFields && !fullEditable)
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_EDITABLE);

        // 최대 참여 인원: 유일하게 언제든(시작 전·진행 중) 수정 가능. 현재 인원 미만으로 축소 불가.
        if (req.maxParticipants() != null && c.isGroup()) {
            if (req.maxParticipants() < activeCount)
                throw new BusinessException(ErrorCode.MAX_PARTICIPANTS_BELOW_CURRENT);
            c.changeMaxParticipants(req.maxParticipants());
        }

        if (touchesOtherFields) {
            if (req.title() != null) validateTitle(req.title());   // 이름 모더레이션 없음(§Non-Goals)
            if (req.description() != null && req.description().length() > 200)
                throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);

            // imageUrl 이 실제로 바뀌면 재검수(§3-3) — 바꾸기 "전" 값과 비교.
            boolean imageChanged = req.imageUrl() != null && !req.imageUrl().equals(c.getImageUrl());

            c.changeTitle(req.title());
            c.changeImageUrl(req.imageUrl());
            c.changeDescription(req.description());
            if (req.category() != null)   c.changeCategory(validateCategory(req.category()));
            if (req.repeatDays() != null) c.changeRepeatDays(validateRepeatDays(req.repeatDays()));
            if (req.params() != null)
                c.changeParams(routineSelectionService.revalidateParams(c.getTemplateId(), req.params()));
            c.changePenalty(req.penalty());
            c.changeReward(req.reward());
            if (c.isGroup())
                checkMinMannerNotAboveOwner(c.getCreatorId(), req.minMannerTemperature());
            c.changeMinMannerTemperature(req.minMannerTemperature());

            Integer duration = (req.durationDays() != null) ? validateDuration(req.durationDays()) : null;
            LocalDate start = (req.startDate() != null) ? validateStartDate(req.startDate()) : null;
            c.changeSchedule(duration, start);

            if (imageChanged) {
                c.markImageInReview();    // 고칠 때마다 재심사(항목별 상태) — 기능 제한 없음
                eventPublisher.publishEvent(new ChallengeModerationRequested(c.getId()));
            }
        }

        return ChallengeResponse.from(c);
    }

    // ===== §8 삭제 (하드 삭제 + 감사 로깅, OWNER만) — 판정 순서 고정 =====
    /**
     * 챌린지 삭제. 참여자(OWNER 제외) 0명일 때만. 7일 잠금·차등차감 폐기.
     * 판정 순서: ① OWNER → ② 참여자 ≥1명(CHALLENGE_HAS_MEMBERS) → ③ COMPLETED(CHALLENGE_COMPLETED)
     *            → ④ 시작 전이면 즉시 삭제(무패널티) / 진행 중이면 챌린지 내 success 이력 판정 후 삭제 + 패널티 트리거.
     * 챌린지 행 락 → 참여자 수 재확인 → 하드 삭제(신규 가입 경합 차단).
     */
    @Transactional
    public DeleteChallengeResponse delete(UUID userId, UUID challengeId) {
        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        // ① OWNER
        ensureOwner(c, userId);
        // ② 나 외 ACTIVE 멤버가 있으면 불가(이탈 경로: 임명→위임→탈퇴).
        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        if (activeCount > 1)
            throw new BusinessException(ErrorCode.CHALLENGE_HAS_MEMBERS);
        // ③ 종료된 챌린지는 기록 보존 — 삭제 불가.
        if (c.getStatus() == ChallengeStatus.COMPLETED)
            throw new BusinessException(ErrorCode.CHALLENGE_COMPLETED);
        // ④ 진행 중이면 챌린지 내 success 이력으로 탈퇴 패널티 트리거(시작 전은 무패널티).
        boolean penaltyApplied = c.getStatus() == ChallengeStatus.ACTIVE
                && verificationDailyRepository.existsByChallengeIdAndStatus(challengeId, VerificationStatus.SUCCESS);

        // 하드 삭제 + 감사 로깅(§8-4). 대표 이미지(S3)는 DB와 무관하게 남으므로 imageUrl 을 함께 남긴다.
        log.warn("[AUDIT] 챌린지 하드 삭제 challengeId={} ownerId={} status={} penaltyApplied={} imageUrl={}",
                challengeId, userId, c.getStatus(), penaltyApplied, c.getImageUrl());
        hardDeleter.hardDelete(challengeId);
        return new DeleteChallengeResponse(penaltyApplied);
    }

    // ====================== 내부 헬퍼 ======================

    private Challenge loadActive(UUID challengeId) {
        return challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    private void ensureOwner(Challenge c, UUID userId) {
        if (!c.isOwner(userId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
    }

    /** ACTIVE 멤버들의 매너 온도 평균(소수 첫째 자리). 멤버 없으면 null. */
    private BigDecimal averageActiveMannerTemperature(UUID challengeId) {
        List<ChallengeMember> actives =
                memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE);
        if (actives.isEmpty()) return null;

        List<UUID> userIds = actives.stream().map(ChallengeMember::getUserId).toList();
        Map<UUID, BigDecimal> tempByUser = reputationScoreRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(ReputationScore::getUserId, ReputationScore::getMannerTemperature));

        BigDecimal sum = BigDecimal.ZERO;
        for (UUID id : userIds) {
            sum = sum.add(tempByUser.getOrDefault(id, ReputationScore.INITIAL_TEMPERATURE));
        }
        return sum.divide(BigDecimal.valueOf(userIds.size()), 1, RoundingMode.HALF_UP);
    }

    private BigDecimal mannerTemp(UUID userId) {
        return reputationScoreRepository.findById(userId)
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);
    }

    // ===== 입력 검증 =====
    private void validateTitle(String title) {
        if (title == null || title.isBlank()) throw new BusinessException(ErrorCode.TITLE_REQUIRED);
        if (title.length() > 30) throw new BusinessException(ErrorCode.TITLE_TOO_LONG);
    }

    private String validateCategory(String category) {
        if (category == null || !InterestCategory.allValid(List.of(category)))
            throw new BusinessException(ErrorCode.INVALID_CATEGORY);
        return category;
    }

    private ParticipationType validateParticipationType(String v) {
        try {
            return ParticipationType.valueOf(v);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_PARTICIPATION_TYPE);
        }
    }

    /** 익명/실명 파싱. 미전송이면 기본 REAL, 허용값 외엔 INVALID_ANONYMITY. */
    private Anonymity validateAnonymity(String v) {
        if (v == null || v.isBlank()) return Anonymity.REAL;
        try {
            return Anonymity.valueOf(v);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_ANONYMITY);
        }
    }


    private List<String> validateRepeatDays(List<String> days) {
        if (days == null || days.isEmpty() || !RepeatDay.allValid(days))
            throw new BusinessException(ErrorCode.INVALID_REPEAT_DAY);
        return days;
    }

    private int validateDuration(Integer durationDays) {
        if (durationDays == null || durationDays < 1)
            throw new BusinessException(ErrorCode.INVALID_DURATION);
        return durationDays;
    }

    private LocalDate validateStartDate(String startDate) {
        if (startDate == null || startDate.isBlank())
            throw new BusinessException(ErrorCode.START_DATE_REQUIRED);
        try {
            return LocalDate.parse(startDate);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.START_DATE_REQUIRED);
        }
    }

    private void validatePenalty(PenaltyConfig penalty) {
        if (penalty == null || penalty.mannerDeduction() == null
                || penalty.mannerDeduction().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessException(ErrorCode.INVALID_PENALTY);
    }

    private void validateReward(RewardConfig reward) {
        if (reward == null || reward.mannerGain() == null
                || reward.mannerGain().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessException(ErrorCode.INVALID_REWARD);
    }
}