package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.reputation.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.user.InterestCategory;
import com.ruleup.ruleup_backend.user.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 챌린지 생성/조회/수정/삭제 (스펙 3.2 ~ 3.5).
 *  - 생성: 사용자 확정값 검증·보정 → RECRUITING 저장 + 생성자 OWNER 등록.
 *  - 수정/삭제: 시작 전(RECRUITING)만, OWNER만. 그룹에 다른 멤버가 있으면 불가(스펙 3.4/3.5).
 *  - 통계/참여자격은 현재 상태로 계산 (완주율은 항상 null).
 */
@Service
@RequiredArgsConstructor
public class ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;

    // ===== 3.2 생성 =====
    @Transactional
    public ChallengeResponse create(UUID userId, CreateChallengeRequest req) {
        validateTitle(req.title());
        if (req.description() != null && req.description().length() > 200)
            throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);

        String category = validateCategory(req.category());
        ParticipationType participationType = validateParticipationType(req.participationType());
        Anonymity anonymity = validateAnonymity(req.anonymity());
        List<String> repeatDays = validateRepeatDays(req.repeatDays());
        List<String> verifications = validateVerifications(req.verificationMethods());
        int durationDays = validateDuration(req.durationDays());
        LocalDate startDate = validateStartDate(req.startDate());
        validatePenalty(req.penalty());
        validateReward(req.reward());
        if (participationType == ParticipationType.GROUP)
            checkMinMannerNotAboveOwner(userId, req.minMannerTemperature());

        Challenge challenge = Challenge.create(
                userId, req.title(), req.description(), req.imageUrl(),
                category, participationType, req.minMannerTemperature(), repeatDays,
                durationDays, startDate, verifications, req.penalty(), req.reward(),
                anonymity, /* aiAssisted */ true);

        challengeRepository.save(challenge);

        // 생성자 = OWNER, 즉시 ACTIVE. participant_count는 ACTIVE 멤버 수이므로 1로 시작.
        memberRepository.save(ChallengeMember.owner(challenge.getId(), userId));
        challenge.increaseParticipantCount();

        return ChallengeResponse.from(challenge);
    }

    /** 그룹 기준 매너 온도가 생성자 본인 온도보다 높으면 거부 (생성/수정 공용). */
    private void checkMinMannerNotAboveOwner(UUID ownerId, BigDecimal minManner) {
        if (minManner == null) return;
        if (mannerTemp(ownerId).compareTo(minManner) < 0) {
            throw new BusinessException(ErrorCode.INVALID_MIN_MANNER_TEMPERATURE);
        }
    }

    // ===== 3.3 상세 + 참여 자격 =====
    @Transactional(readOnly = true)
    public ChallengeDetailResponse getDetail(UUID userId, UUID challengeId) {
        Challenge c = loadActive(challengeId);

        // 생성자 닉네임 (익명이면 마스킹)
        String ownerNickname = userRepository.findById(c.getCreatorId())
                .map(u -> c.getAnonymity().maskNickname(u.getNickname()))
                .orElse(null);

        // 통계
        BigDecimal avgManner = averageActiveMannerTemperature(challengeId);
        var stats = new ChallengeDetailResponse.Stats(
                c.getParticipantCount(), avgManner, /* completionRate */ null);

        // 참여 자격
        BigDecimal myManner = mannerTemp(userId);
        boolean alreadyMember = isActiveOrPending(challengeId, userId);
        boolean meetsMinManner = !c.isGroup() || c.getMinMannerTemperature() == null
                || myManner.compareTo(c.getMinMannerTemperature()) >= 0;
        boolean canJoin = !alreadyMember && meetsMinManner && !c.isOwner(userId);

        var eligibility = new ChallengeDetailResponse.Eligibility(
                canJoin, myManner, c.getMinMannerTemperature());

        return new ChallengeDetailResponse(
                c.getId().toString(), c.getTitle(), c.getDescription(), c.getImageUrl(),
                c.getCategory(), c.getParticipationType().name(), c.getStatus().name(),
                new ChallengeDetailResponse.Owner(ownerNickname),
                c.getRepeatDays(), c.getDurationDays(),
                c.getStartDate().toString(), c.getEndDate().toString(),
                c.getVerificationMethods(), c.getPenalty(), c.getReward(),
                stats, eligibility);
    }

    // ===== 3.4 수정 (시작 전, OWNER만) =====
    @Transactional
    public ChallengeResponse update(UUID userId, UUID challengeId, UpdateChallengeRequest req) {
        Challenge c = loadActive(challengeId);
        ensureOwner(c, userId);
        ensureEditable(c, challengeId);

        if (req.title() != null) validateTitle(req.title());
        if (req.description() != null && req.description().length() > 200)
            throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);

        c.changeTitle(req.title());
        c.changeDescription(req.description());
        if (req.category() != null)   c.changeCategory(validateCategory(req.category()));
        if (req.repeatDays() != null) c.changeRepeatDays(validateRepeatDays(req.repeatDays()));
        if (req.verificationMethods() != null)
            c.changeVerificationMethods(validateVerifications(req.verificationMethods()));
        c.changePenalty(req.penalty());
        c.changeReward(req.reward());
        if (c.isGroup())
            checkMinMannerNotAboveOwner(c.getCreatorId(), req.minMannerTemperature());
        c.changeMinMannerTemperature(req.minMannerTemperature());

        // 일정 변경 → endDate 재파생
        Integer duration = (req.durationDays() != null) ? validateDuration(req.durationDays()) : null;
        LocalDate start = (req.startDate() != null) ? validateStartDate(req.startDate()) : null;
        c.changeSchedule(duration, start);

        return ChallengeResponse.from(c);
    }

    // ===== 3.5 삭제 (소프트, OWNER만) =====
    @Transactional
    public void delete(UUID userId, UUID challengeId) {
        Challenge c = loadActive(challengeId);
        ensureOwner(c, userId);
        ensureEditable(c, challengeId);
        c.softDelete();
    }

    // ====================== 내부 헬퍼 ======================

    private Challenge loadActive(UUID challengeId) {
        return challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    private void ensureOwner(Challenge c, UUID userId) {
        if (!c.isOwner(userId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
    }

    /**
     * 수정/삭제 가능 여부.
     *  - 시작된(ACTIVE 이후) 챌린지는 불가.
     *  - 그룹은 생성자 외 다른 멤버(ACTIVE/PENDING)가 있으면 불가 (스펙 3.4/3.5).
     *    솔로는 본인뿐이라 자유.
     *  ※ 솔로 "n일 후에만 수정 가능 / 한 번 수정하면 n일 잠금" 규칙(스펙 3.4 메모)은
     *    인증·운영 스펙과 함께 별도 구현 예정 (TODO).
     */
    private void ensureEditable(Challenge c, UUID challengeId) {
        if (!c.isEditable()) throw new BusinessException(ErrorCode.CHALLENGE_NOT_EDITABLE);
        if (c.isGroup()) {
            long others = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE)
                    + memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.PENDING);
            // 생성자 본인(ACTIVE) 1명만 있으면 others == 1 → 허용. 그 이상이면 불가.
            if (others > 1) throw new BusinessException(ErrorCode.CHALLENGE_NOT_EDITABLE);
        }
    }

    private boolean isActiveOrPending(UUID challengeId, UUID userId) {
        return memberRepository.findByChallengeIdAndUserId(challengeId, userId)
                .map(m -> m.isActive() || m.isPending())
                .orElse(false);
    }

    /** ACTIVE 멤버들의 매너 온도 평균(소수 첫째 자리). 멤버 없으면 null. */
    private BigDecimal averageActiveMannerTemperature(UUID challengeId) {
        List<ChallengeMember> actives =
                memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE);
        if (actives.isEmpty()) return null;

        List<UUID> userIds = actives.stream().map(ChallengeMember::getUserId).toList();
        List<ReputationScore> scores = reputationScoreRepository.findAllById(userIds);

        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (UUID id : userIds) {
            BigDecimal t = scores.stream()
                    .filter(s -> s.getUserId().equals(id))
                    .map(ReputationScore::getMannerTemperature)
                    .findFirst()
                    .orElse(ReputationScore.INITIAL_TEMPERATURE);
            sum = sum.add(t);
            n++;
        }
        return sum.divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);
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

    private Anonymity validateAnonymity(String v) {
        if (v == null) return Anonymity.REAL;        // 미지정 시 실명 기본
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

    private List<String> validateVerifications(List<String> methods) {
        if (methods == null || methods.isEmpty())
            throw new BusinessException(ErrorCode.VERIFICATION_METHOD_REQUIRED);
        if (!VerificationMethod.allValid(methods))
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION_METHOD);
        return methods;
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

    private void validatePenalty(com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig penalty) {
        if (penalty == null || penalty.mannerDeduction() == null
                || penalty.mannerDeduction().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessException(ErrorCode.INVALID_PENALTY);
    }

    private void validateReward(com.ruleup.ruleup_backend.challenge.domain.RewardConfig reward) {
        if (reward == null || reward.mannerGain() == null
                || reward.mannerGain().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessException(ErrorCode.INVALID_REWARD);
    }
}