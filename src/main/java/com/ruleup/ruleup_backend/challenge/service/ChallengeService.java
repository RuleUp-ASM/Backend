package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.*;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationRequested;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeNameBlocklist;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.service.ResolvedRoutine;
import com.ruleup.ruleup_backend.routine.service.RoutineSelectionService;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
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
    private final RoutineSelectionService routineSelectionService;
    private final ChallengeNameBlocklist nameBlocklist;
    private final ApplicationEventPublisher eventPublisher;

    /** 하루 경계·삭제 차감 계산의 사용자 로컬 = MVP는 KST 고정(§4.4). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 삭제 잠금 해제 시점 = 생성 후 7일(§5.8). */
    private static final int DELETE_UNLOCK_DAYS = 7;

    // ===== 내 챌린지 목록(내가 참여 중인 챌린지) =====

    /**
     * 내가 참여 중인 챌린지 목록. 멤버십 기준이라 페이지네이션 없이 전량 반환한다.
     *  - scope=ACTIVE(기본) : ACTIVE 멤버십(실제 참여 중)만.
     *  - scope=ALL          : ACTIVE + PENDING(승인 대기) 멤버십. 탈퇴/거절(LEFT/REMOVED)은 제외.
     * 소프트 삭제된 챌린지는 제외. 항목마다 내 멤버십 상태(memberStatus: ACTIVE/PENDING)를 함께 내려준다.
     */
    @Transactional(readOnly = true)
    public ChallengeListResponse myChallenges(UUID userId, String scope) {
        List<ChallengeMember> memberships = "ALL".equalsIgnoreCase(scope)
                ? memberRepository.findByUserIdAndStatusIn(userId, List.of(MemberStatus.ACTIVE, MemberStatus.PENDING))
                : memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE);

        List<ChallengeListResponse.Item> items = new ArrayList<>();
        for (ChallengeMember m : memberships) {
            Challenge ch = challengeRepository.findByIdAndDeletedAtIsNull(m.getChallengeId()).orElse(null);
            if (ch == null) continue;   // 소프트 삭제/정합성 깨진 멤버십은 건너뜀
            items.add(ChallengeListResponse.Item.of(ch, m.getStatus().name()));
        }
        return new ChallengeListResponse(items);
    }

    // ===== 3.2 생성 =====
    @Transactional
    public ChallengeResponse create(UUID userId, CreateChallengeRequest req) {
        validateTitle(req.title());
        nameBlocklist.validate(req.title());     // 명백 비속어는 동기 차단(§5.1, 선택)
        if (req.description() != null && req.description().length() > 200)
            throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);

        String category = validateCategory(req.category());
        ParticipationType participationType = validateParticipationType(req.participationType());
        // 익명/실명은 입력값 그대로 저장(§11.2 — silently-drop 금지). 익명이면 응답에서 닉네임 마스킹.
        Anonymity anonymity = validateAnonymity(req.anonymity());
        List<String> repeatDays = validateRepeatDays(req.repeatDays());
        int durationDays = validateDuration(req.durationDays());
        LocalDate startDate = validateStartDate(req.startDate());
        validatePenalty(req.penalty());
        validateReward(req.reward());
        if (participationType == ParticipationType.GROUP)
            checkMinMannerNotAboveOwner(userId, req.minMannerTemperature());

        // 루틴(인증) 검증 → 스냅샷 산출.
        ResolvedRoutine routine = routineSelectionService.resolve(
                req.templateId(), req.selectedMethod(), req.paramsOrEmpty());

        // AUTO면 필요한 권한이 모두 grant됐는지 생성 시점 1회 검증(§11.2). 보유 상태는 저장하지 않는다(§5.6).
        validateGrantedPermissions(routine.verification(), req.grantedPermissionsOrEmpty());

        Challenge challenge = Challenge.create(
                userId, req.title(), req.description(), req.imageUrl(),
                category, participationType, req.minMannerTemperature(), repeatDays,
                durationDays, startDate,
                routine.templateId(), routine.verification(), routine.params(),
                req.penalty(), req.reward(),
                anonymity, /* aiAssisted */ true);

        challengeRepository.save(challenge);

        // 생성자 = OWNER, 즉시 ACTIVE. participant_count는 ACTIVE 멤버 수이므로 1로 시작.
        memberRepository.save(ChallengeMember.owner(challenge.getId(), userId));
        challenge.increaseParticipantCount();

        // 이미지 검수만 비동기(§5.1) — 이미지가 있어 PENDING_REVIEW 인 경우에만 커밋 후 리스너가 검수.
        // 이미지가 없으면 create()에서 이미 APPROVED(즉시 공개)라 검수 이벤트를 내지 않는다.
        if (challenge.getModerationStatus() == ChallengeModerationStatus.PENDING_REVIEW) {
            eventPublisher.publishEvent(new ChallengeModerationRequested(challenge.getId()));
        }

        // 추천 세그먼트 점수는 여기서 즉시 갱신하지 않는다(매 생성마다 바뀌면 추천 캐시 효율↓).
        // SegmentScoreService 가 주기적으로 누적 챌린지를 재집계한다.
        return ChallengeResponse.from(challenge);
    }

    /**
     * AUTO 인증이면 스냅샷의 "즉시형" 권한이 모두 grantedPermissions 에 포함돼야 한다(§5.1/§11.2).
     *  - 즉시형(알림·FINE위치·활동인식·HC·카메라 등)은 생성 버튼 시점 팝업으로 받으므로 여기서 강제.
     *  - 설정형(백그라운드 위치·사용정보 접근)은 셋업(§11.4 /setup)으로 이연 → 생성 시 검증 제외.
     * 미충족이면 ROUTINE_PERMISSION_REQUIRED. (보유 상태는 저장하지 않음 — 생성 시점 검증만, §5.6)
     */
    private void validateGrantedPermissions(com.ruleup.ruleup_backend.routine.domain.VerificationConfig v,
                                            List<String> granted) {
        if (v.selectedMethod() != com.ruleup.ruleup_backend.routine.domain.SelectedMethod.AUTO) return;
        List<String> required = v.immediateRequiredPermissions();
        if (required.isEmpty()) return;
        if (granted == null || !granted.containsAll(required))
            throw new BusinessException(ErrorCode.ROUTINE_PERMISSION_REQUIRED);
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
        // 가시성 게이트(§5.1): 비-OWNER는 APPROVED만 조회 가능. 그 외는 존재 자체를 숨겨 404.
        if (!c.isVisibleTo(userId)) throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);

        // 생성자 닉네임: 검수 전/거절이면 임시 닉네임(visibleNicknameTo) + 익명이면 추가 마스킹(§11.2).
        String ownerNickname = userRepository.findById(c.getCreatorId())
                .map(u -> c.getAnonymity().maskNickname(u.visibleNicknameTo(userId)))
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

        // fixDeadline 은 OWNER에게만 의미(REJECTED 1시간 수정창). 타인에겐 항상 null.
        String fixDeadline = (c.isOwner(userId) && c.getFixDeadline() != null)
                ? c.getFixDeadline().toString() : null;

        return new ChallengeDetailResponse(
                c.getId().toString(), c.getTitle(), c.getDescription(), c.getImageUrl(),
                c.getCategory(), c.getParticipationType().name(), c.getStatus().name(),
                c.getModerationStatus().name(), fixDeadline, c.getAnonymity().name(),
                new ChallengeDetailResponse.Owner(ownerNickname),
                c.getRepeatDays(), c.getDurationDays(),
                c.getStartDate().toString(), c.getEndDate().toString(),
                c.getTemplateId(), c.getVerificationConfig(), c.getParams(),
                c.getPenalty(), c.getReward(),
                stats, eligibility);
    }

    // ===== 3.4 수정 (시작 전, OWNER만) =====
    @Transactional
    public ChallengeResponse update(UUID userId, UUID challengeId, UpdateChallengeRequest req) {
        Challenge c = loadActive(challengeId);
        ensureOwner(c, userId);
        ensureEditable(c, challengeId);

        if (req.title() != null) {
            validateTitle(req.title());
            nameBlocklist.validate(req.title());     // 명백 비속어 동기 차단(§5.1)
        }
        if (req.description() != null && req.description().length() > 200)
            throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);

        // imageUrl 이 실제로 바뀌면 재검수(§5.1) — 바꾸기 "전" 값과 비교. 이름 변경은 검수 트리거가 아니다
        // (이름은 blocklist 동기 차단 + 추천 draft 게이트로만 거른다).
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

        // 일정 변경 → endDate 재파생
        Integer duration = (req.durationDays() != null) ? validateDuration(req.durationDays()) : null;
        LocalDate start = (req.startDate() != null) ? validateStartDate(req.startDate()) : null;
        c.changeSchedule(duration, start);

        // 이미지가 바뀌었으면 PENDING_REVIEW 로 되돌리고 재검수(REJECTED 1h 수정 경로 포함).
        if (imageChanged) {
            c.resubmitModeration();
            eventPublisher.publishEvent(new ChallengeModerationRequested(c.getId()));
        }

        return ChallengeResponse.from(c);
    }

    // ===== 3.5 삭제 (소프트, OWNER만) — §5.8 판정 순서 고정 =====
    @Transactional
    public DeleteChallengeResponse delete(UUID userId, UUID challengeId) {
        Challenge c = loadActive(challengeId);
        // ① OWNER 확인
        ensureOwner(c, userId);
        // ② 나 외 ACTIVE 멤버가 있으면 불가(PENDING 신청자는 제외 — ACTIVE만 카운트).
        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        if (activeCount > 1)   // 생성자 본인(ACTIVE) 1명 제외하고 더 있으면
            throw new BusinessException(ErrorCode.CHALLENGE_HAS_MEMBERS);
        // ③ 잠금: 생성 후 7일 이내 또는 계획 기간 7일 미만
        Instant now = Instant.now();
        Instant unlock = c.getCreatedAt().plus(DELETE_UNLOCK_DAYS, java.time.temporal.ChronoUnit.DAYS);
        if (now.isBefore(unlock) || c.getDurationDays() < DELETE_UNLOCK_DAYS)
            throw new BusinessException(ErrorCode.DELETE_LOCKED);
        // ④ 차감 계산 후 소프트 삭제. 실제 매너 가감은 평판 스펙 소관 — 여기선 트리거 + 값 반환만.
        BigDecimal mannerPenalty = computeMannerPenalty(c, now, unlock);
        c.softDelete();
        return new DeleteChallengeResponse(mannerPenalty);
    }

    /**
     * §5.8 차감량: mannerPenalty = round(basePenalty × max(0,(endDate−now)/(endDate−unlock)), 1).
     *  - basePenalty = 챌린지 패널티 설정의 mannerDeduction.
     *  - 진행할수록(now가 endDate에 가까울수록) 차감이 작아지고, 계획 종료 시 0.
     *  - 하루 경계는 KST(§4.4). endDate(로컬 날짜)는 그 날 끝(다음날 자정)으로 환산해 비교.
     */
    private BigDecimal computeMannerPenalty(Challenge c, Instant now, Instant unlock) {
        BigDecimal base = (c.getPenalty() != null && c.getPenalty().mannerDeduction() != null)
                ? c.getPenalty().mannerDeduction() : BigDecimal.ZERO;
        Instant end = c.getEndDate().plusDays(1).atStartOfDay(KST).toInstant();
        double span = (double) (end.getEpochSecond() - unlock.getEpochSecond());
        double remain = (double) (end.getEpochSecond() - now.getEpochSecond());
        double ratio = (span <= 0) ? 0.0 : Math.max(0.0, Math.min(1.0, remain / span));
        return base.multiply(BigDecimal.valueOf(ratio)).setScale(1, RoundingMode.HALF_UP);
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