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
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
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
import java.time.Instant;
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
    private final UserScoreSummaryRepository scoreSummaryRepository;
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
     * 항목마다 내 역할(myRole: OWNER/MEMBER)을 함께 내려준다.
     */
    @Transactional(readOnly = true)
    public ChallengeListResponse myChallenges(UUID userId) {
        List<ChallengeMember> memberships = memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE);

        List<ChallengeListResponse.Item> items = new ArrayList<>();
        for (ChallengeMember m : memberships) {
            Challenge ch = challengeRepository.findByIdAndDeletedAtIsNull(m.getChallengeId()).orElse(null);
            if (ch == null) continue;   // 삭제/정합성 깨진 멤버십은 건너뜀
            items.add(ChallengeListResponse.Item.of(ch, m.isOwner() ? "OWNER" : "MEMBER"));
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

    // ====================== 내부 헬퍼 ======================

    /**
     * 가입 게이트 미리보기. 판정 순서는 가입 API 와 동일하게
     * 종료 → 이미 참여 → 비공개 → 재입장 대기 → 정원 → 티어 순이다.
     * 동시 참여 3개(FREE_LIMIT)는 사용자 행 락이 필요해 미리보기에서 제외한다 — 가입 시점에 확정된다.
     *
     * @return 막히는 사유, 들어갈 수 있으면 null
     */

    /** 표시 티어. 요약 행이 없거나 UNRANKED 면 BRONZE(가입 초기 티어). */

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
