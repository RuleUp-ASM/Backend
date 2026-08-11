package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.dto.LeaveResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberListResponse;
import com.ruleup.ruleup_backend.challenge.dto.RoleChangeResponse;
import com.ruleup.ruleup_backend.challenge.counter.ConcurrentChallengeLimitPolicy;
import com.ruleup.ruleup_backend.challenge.counter.UserJoinCounterService;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.report.BlacklistService;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 챌린지 멤버십 (생성 및 라이프사이클 스펙 §5·§6·§11): 가입 / 탈퇴 / 역할 변경 / 멤버 목록.
 *
 *  - 가입: 승인 절차 없이 <b>종료 상태 검사 1개 + 자격 게이트 5개</b>만 통과하면 즉시 ACTIVE.
 *    거절은 전부 409 {@code JOIN_BLOCKED} + reason 단일 형식이다.
 *    락 순서는 <b>사용자 행 → 챌린지 행</b>으로 전 경로 고정한다(데드락 방지 + 동시 3개 제한 보장).
 *    기기 권한은 서버 게이트가 아니다 — 클라가 공개 상세의 requiredPermissions 로 가입 전에 확보한다.
 *  - 탈퇴: 방장도 자유롭게 나갈 수 있고, 넘기지 않고 나가면 즉시 봇방장 체제가 된다(§11.2).
 *  - 목록: 현재 멤버(ACTIVE)만. 익명 챌린지는 닉네임 마스킹.
 */
@Service
@RequiredArgsConstructor
public class ChallengeMemberService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeMemberService.class);

    /** 자진 탈퇴 후 재입장 대기(정책 §10.1 — 고정 1주). 강퇴 배수는 {@link RejoinBackoff}. */
    private static final Duration LEAVE_REJOIN_COOLDOWN = Duration.ofDays(7);
    /** "1년 이상 성공을 이어왔다" 면제 기준(정책 §10.1). */
    private static final Duration LONG_SUCCESS_THRESHOLD = Duration.ofDays(365);
    /** 중도 탈퇴 감점 — ⚠️ 수치 미확정. 실제 반영은 티어 모듈 소관이고 여기서는 계약값·트리거만. */
    private static final int LEAVE_SCORE_PENALTY = -15;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserChallengeCounterRepository counterRepository;
    private final UserJoinCounterService joinCounterService;
    private final ConcurrentChallengeLimitPolicy limitPolicy;
    private final UserRepository userRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final VerificationDailyRepository verificationDailyRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final RoomAuthority roomAuthority;
    private final BlacklistService blacklistService;

    // ===== 가입 =====
    /**
     * 챌린지 가입. 판정 순서:
     * ① 종료 → ② 비공개(초대로만) → ③ 재입장 대기 → ④ 동시 3개(사용자 행 락)
     * → ⑤ 정원(챌린지 행 락) → ⑥ 최소 티어(표시 티어 기준).
     */
    @Transactional
    public JoinResponse join(UUID userId, UUID challengeId) {
        Instant now = Instant.now();

        // 락 순서 고정: 사용자 행을 먼저 잡는다. 챌린지 행만 잠그면 서로 다른 두 방에 동시 가입할 때
        // 각자 다른 행을 잡아 둘 다 "현재 2개"로 읽어 동시 3개 제한이 뚫린다(P0).
        counterRepository.ensureRow(userId);
        counterRepository.lockCount(userId);   // 락만 잡는다 — 판정은 ④에서 원천을 세서 한다

        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        // 솔로 방은 본인만 — 타인에겐 존재를 숨긴다(상세 조회 404 규칙과 동일).
        if (!c.isGroup() && !c.isOwner(userId))
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);

        // ① 종료된 챌린지는 가입 개념 없음
        if (c.getStatus() == ChallengeStatus.COMPLETED) throw blocked(JoinBlockReason.CHALLENGE_COMPLETED);

        ChallengeMember existing = memberRepository.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
        if (existing != null && existing.isActive()) throw blocked(JoinBlockReason.ALREADY_JOINED);

        // ② 비공개 방은 초대 링크로만 입장 — 직접 가입 불가
        if (c.isGroup() && "PRIVATE".equals(c.getVisibility()))
            throw blocked(JoinBlockReason.PRIVATE_INVITE_ONLY);

        // ③ 재입장 대기 — 자진 탈퇴 1주 / 강퇴 배수. 사유별 예외(영구 차단)는 없다.
        if (existing != null) {
            Instant availableAt = existing.getRejoinAvailableAt();
            if (availableAt != null && now.isBefore(availableAt))
                throw new BusinessException(ErrorCode.JOIN_BLOCKED,
                        JoinBlockReason.REJOIN_COOLDOWN.name(), availableAt.toString());
        }

        // ④ 동시 참여 3개(사용자 행 락 하에서 판정)
        // 저장 카운터가 아니라 원천(멤버십)을 센다. 저장값으로 판정하면 어딘가에서 해제가 한 번 누락된
        // 사용자가 "실제로는 0개 참여 중인데 가입 불가"에 갇히고, 종료된 방은 탈퇴도 못 해 스스로 풀 수 없다.
        //
        // ⚠️ 이 COUNT 를 챌린지 행 락보다 <b>앞</b>으로 옮기면 안 된다. MySQL REPEATABLE READ 에서
        // 트랜잭션의 읽기 스냅샷은 "첫 일반 SELECT" 시점에 고정되는데, 락을 기다리기 전에 스냅샷이
        // 잡히면 ⑤의 정원 카운트가 그 사이 커밋된 다른 가입을 못 본다 → 마지막 한 자리에 여러 명이 들어간다.
        int activeJoinCount = joinCounterService.countActiveSlots(userId);
        if (limitPolicy.exceeded(activeJoinCount)) throw blocked(JoinBlockReason.FREE_LIMIT);

        // ⑤ 정원(챌린지 행 락 하에서 판정 — "마지막 1자리 동시 가입" 차단)
        Integer cap = c.getMaxParticipants();
        long activeCount = memberRepository.countByChallengeIdAndStatus(challengeId, MemberStatus.ACTIVE);
        if (cap != null && activeCount >= cap) throw blocked(JoinBlockReason.FULL);

        // ⑥ 최소 티어 — 표시 티어 기준
        if (c.getMinTier() != null && displayTier(userId).ordinal() < c.getMinTier().ordinal())
            throw blocked(JoinBlockReason.TIER_GATE);

        // 즉시 ACTIVE 등록. uq_member 로 동시 INSERT는 1건만 성공, 나머지는 중복으로 변환.
        if (existing != null) {
            existing.rejoin();
        } else {
            try {
                memberRepository.saveAndFlush(ChallengeMember.join(challengeId, userId, MemberStatus.ACTIVE));
            } catch (DataIntegrityViolationException dup) {
                throw blocked(JoinBlockReason.ALREADY_JOINED);
            }
        }
        challengeRepository.incrementParticipantCount(challengeId);
        counterRepository.increment(userId);
        c.bumpVersion();   // 참여 인원 변화 = 수정 가능 범위가 바뀔 수 있음 → 설정 수정과 충돌 감지
        // 통계는 커밋 뒤에 다시 센다 — 실패해도 가입을 되돌리지 않는다
        eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "JOIN"));

        // 사이클은 1주 고정 — 주 중간에 들어오면 판정은 다음 사이클 경계부터.
        LocalDate countFrom = ChallengeCycle.countFrom(c.getStartDate(), LocalDate.now(KST));
        log.info("challenge_join_result success=true challengeId={} userId={}", challengeId, userId);
        return JoinResponse.of(countFrom.toString(), c.getVerificationConfig());
    }

    // ===== 탈퇴 =====
    /**
     * 챌린지 탈퇴(본인). 언제든 나갈 수 있다 — 구 "재참여 영구 불가"·"방장 탈퇴 불가"는 둘 다 폐기.
     *  - 방장이 권한을 넘기지 않고 나가면 <b>즉시 봇방장 체제</b>로 전환하고 잔류 멤버에게 승계 알림을 보낸다(§11.2).
     *  - 감점 면제: 1년 이상 성공(LONG_SUCCESS) / 승계 3일 면책(SUCCESSION_GRACE — 모든 멤버 기준, §11.3).
     *  - 재입장은 1주 대기.
     */
    @Transactional
    public LeaveResponse leave(UUID userId, UUID challengeId) {
        Instant now = Instant.now();

        // 가입과 같은 락 순서(사용자 → 챌린지)를 유지한다.
        counterRepository.ensureRow(userId);
        counterRepository.lockCount(userId);

        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        if (c.getStatus() == ChallengeStatus.COMPLETED)
            throw new BusinessException(ErrorCode.CHALLENGE_COMPLETED);

        ChallengeMember me = memberRepository.findByChallengeIdAndUserId(challengeId, userId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // 면제 판정은 상태를 바꾸기 전에 — 나가면서 스스로 만든 봇방장 전환으로 자기 감점을 면제받으면 안 된다.
        String exemptReason = resolveExemptReason(c, me, now);

        // 방장이 넘기지 않고 나감 → 즉시 봇방장 체제 + 잔류 멤버에게 승계 알림
        boolean botOwnerActivated = false;
        if (me.isOwner()) {
            c.convertToBotOwner(now);
            botOwnerActivated = true;
            notifyBotOwnerActivated(c, userId);
        }

        Instant rejoinAt = now.plus(LEAVE_REJOIN_COOLDOWN);
        me.leave(now, rejoinAt);
        // 참여 인원 변화 → version 증가. decrementParticipantCount 는 clearAutomatically 라
        // 그 뒤에서 부르면 c 가 준영속이 되어 증가가 조용히 사라진다(반드시 앞에서).
        c.bumpVersion();
        challengeRepository.decrementParticipantCount(challengeId);
        counterRepository.decrement(userId);
        eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "LEAVE"));

        int scoreDelta = (exemptReason != null) ? 0 : LEAVE_SCORE_PENALTY;
        log.info("challenge_leave challengeId={} userId={} penalty_applied={} exempt={} botOwner={}",
                challengeId, userId, scoreDelta != 0, exemptReason, botOwnerActivated);
        return new LeaveResponse(true, scoreDelta, exemptReason, rejoinAt.toString(), botOwnerActivated);
    }

    // ===== 회원 탈퇴에 따른 일괄 정리 =====
    /**
     * 탈퇴하는 회원을 참여 중인 <b>모든</b> 방에서 내보낸다. 정리한 방 수를 반환.
     *
     * <p>멤버십을 그대로 두는 선택지도 있었지만, 인증하지 않는 유령 멤버가 남의 방 정원을 먹고
     * 그 방은 "ACTIVE 멤버 0명" 유령방 삭제 대상에서도 빠져 영영 남는다 — 남은 참여자가 피해를 본다.
     * 그래서 계정 복구(1년 내 재로그인) 시에도 방은 되돌아오지 않는다.
     *
     * <p>{@link #leave} 와 다른 점 셋:
     * <ul>
     *   <li>종료(COMPLETED)된 방도 정리한다 — 이건 사용자 행동이 아니라 계정 정리라 탈퇴 거부 규칙 밖이다.</li>
     *   <li>중도 이탈 감점을 매기지 않는다 — 사라지는 계정에 점수를 남길 이유가 없고, 복구하면 억울해진다.</li>
     *   <li>재입장 대기를 걸지 않는다 — 계정이 돌아와도 그 방으로는 안 돌아간다.</li>
     * </ul>
     * 방장이었으면 자진 탈퇴와 동일하게 봇방장으로 전환하고 잔류 멤버에게 알린다.
     */
    @Transactional
    public int leaveAllForWithdrawal(UUID userId) {
        Instant now = Instant.now();
        // 락 순서 고정(사용자 → 챌린지). 탈퇴도 예외가 아니다.
        counterRepository.ensureRow(userId);
        counterRepository.lockCount(userId);

        // 엔티티가 아니라 id 만 먼저 모은다. decrementParticipantCount 가 clearAutomatically 라
        // 방 하나를 처리할 때마다 영속성 컨텍스트가 비워져, 미리 들고 있던 엔티티는 준영속이 되고
        // 그 뒤의 변경(leave·봇방장 전환)이 조용히 사라진다. 반복마다 새로 읽는다.
        List<UUID> challengeIds = memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE)
                .stream().map(ChallengeMember::getChallengeId).toList();

        int left = 0;
        for (UUID challengeId : challengeIds) {
            ChallengeMember me = memberRepository.findByChallengeIdAndUserId(challengeId, userId)
                    .filter(ChallengeMember::isActive).orElse(null);
            if (me == null) continue;
            Challenge c = challengeRepository.findByIdForUpdate(challengeId).orElse(null);
            if (c == null) continue;   // 이미 삭제된 방 — 멤버 행도 곧 사라진다

            if (me.isOwner()) {
                c.convertToBotOwner(now);
                notifyBotOwnerActivated(c, userId);
            }
            me.leave(now, null);       // rejoinAt=null — 재입장 대기 없음
            c.bumpVersion();
            // flushAutomatically 라 위 변경들이 먼저 반영된 뒤 실행된다(그 다음 컨텍스트가 비워진다).
            challengeRepository.decrementParticipantCount(challengeId);
            eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "WITHDRAW"));
            left++;
        }

        counterRepository.setCount(userId, joinCounterService.countActiveSlots(userId));
        if (left > 0) log.info("회원 탈퇴 정리 userId={} 나간 방 {}건", userId, left);
        return left;
    }

    /**
     * 감점 면제 사유. 우선순위는 1년 이상 성공 → 승계 3일 면책.
     * 승계 면책은 <b>모든 멤버 기준</b>이다 — 봇방장 전환·선착순 클레임 시점부터 3일간은 잔류 멤버 누구나 면책이고,
     * 전 방장이 직접 넘겨준 경우(GRANT_TRANSFER)만 대상이 아니다(정책 §11.3).
     */
    private String resolveExemptReason(Challenge c, ChallengeMember me, Instant now) {
        LocalDate firstSuccess = verificationDailyRepository
                .findEarliestDate(me.getId(), VerificationStatus.SUCCESS);
        if (firstSuccess != null
                && !firstSuccess.isAfter(LocalDate.now(KST).minusDays(LONG_SUCCESS_THRESHOLD.toDays()))) {
            return LeaveResponse.EXEMPT_LONG_SUCCESS;
        }
        if (c.isWithinSuccessionGrace(now)) return LeaveResponse.EXEMPT_SUCCESSION_GRACE;
        return null;
    }

    /** 봇방장 전환 사실을 잔류 ACTIVE 멤버 전원에게 알린다(선착순 클레임 유도). */
    private void notifyBotOwnerActivated(Challenge c, UUID leavingUserId) {
        for (ChallengeMember m : memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(c.getId(), MemberStatus.ACTIVE)) {
            if (m.getUserId().equals(leavingUserId)) continue;
            notificationService.notify(m.getUserId(), NotificationType.BOT_OWNER_ACTIVATED,
                    "방장 자리가 비었어요", c.getTitle());
        }
    }

    private BusinessException blocked(JoinBlockReason reason) {
        return new BusinessException(ErrorCode.JOIN_BLOCKED, reason.name());
    }


    // ===== §7 멤버 목록 =====
    @Transactional(readOnly = true)
    public MemberListResponse listMembers(UUID viewerId, UUID challengeId) {
        Challenge c = roomAuthority.requireMember(challengeId, viewerId);

        // 승인제 폐기 — 현재 멤버(ACTIVE)만 반환. 탈퇴/제거(LEFT/REMOVED)는 목록에서 제외.
        List<ChallengeMember> members =
                memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE);

        // 사용자/표시 티어 정보 일괄 조회 (N+1 방지)
        List<UUID> userIds = members.stream().map(ChallengeMember::getUserId).toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, com.ruleup.ruleup_backend.score.domain.Tier> tierMap = scoreSummaryRepository.findAllById(userIds)
                .stream().collect(Collectors.toMap(s -> s.getUserId(), s -> s.getDisplayTier()));
        Set<UUID> blockedUsers = blacklistService.blockedUsers(viewerId);

        List<MemberListResponse.Member> dto = members.stream().map(m -> {
            User u = userMap.get(m.getUserId());
            // 익명 챌린지(§11.2)면 닉네임 마스킹 + 프로필 사진 숨김. 그 외엔 본인 가시성 규칙 적용.
            String visibleNick = (u != null) ? u.visibleNicknameTo(viewerId) : null;
            String nickname = c.getAnonymity().maskNickname(visibleNick);
            String profile = (u != null && !c.getAnonymity().isAnonymous())
                    ? u.visibleProfileImageTo(viewerId) : null;
            return new MemberListResponse.Member(
                    m.getUserId().toString(), nickname, profile,
                    m.isOwner() ? "OWNER" : "MEMBER",
                    tierMap.getOrDefault(m.getUserId(), com.ruleup.ruleup_backend.score.domain.Tier.UNRANKED).name(),
                    m.getJoinedAt() != null ? m.getJoinedAt().toString() : null,
                    blockedUsers.contains(m.getUserId()));
        }).toList();

        return new MemberListResponse(c.getOwnerType().name(), dto);
    }

    // ===== §7-1 공동 관리자 임명/해제 =====
    /**
     * 역할 변경(PROMOTE: MEMBER→MANAGER / DEMOTE: MANAGER→MEMBER).
     *  - 임명·해제는 OWNER만. 단 MANAGER 본인의 DEMOTE(내려놓기)는 허용.
     *  - OWNER 역할은 이 API로 변경 불가(위임 API 사용).
     */
    @Transactional
    public RoleChangeResponse changeRole(UUID actorId, UUID challengeId, UUID targetUserId, String action) {
        Challenge c = loadActive(challengeId);
        ChallengeMember target = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // OWNER 는 이 API로 못 바꾼다(위임 API 전용).
        if (target.isOwner()) throw new BusinessException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);

        MemberRole desired = switch (action == null ? "" : action) {
            case "PROMOTE" -> MemberRole.MANAGER;
            case "DEMOTE"  -> MemberRole.MEMBER;
            default -> throw new BusinessException(ErrorCode.INVALID_MEMBER_ACTION);
        };

        // 권한: OWNER는 임명·해제 모두 / 그 외엔 "본인 DEMOTE(내려놓기)"만.
        boolean selfDemote = "DEMOTE".equals(action) && actorId.equals(targetUserId);
        if (!c.isOwner(actorId) && !selfDemote)
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);

        if (target.getRole() == desired) throw new BusinessException(ErrorCode.ALREADY_IN_ROLE);

        target.changeRole(desired);   // 영속 상태 → dirty checking 으로 flush
        return new RoleChangeResponse(targetUserId.toString(), desired.name());
    }

    // ===== 헬퍼 =====
    private Challenge loadActive(UUID challengeId) {
        return challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    /** 표시 티어. 요약 행이 없거나 UNRANKED 면 BRONZE(가입 초기 티어) — 생성 경로와 동일 규칙. */
    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId)
                .map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }

}
