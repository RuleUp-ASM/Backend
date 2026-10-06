package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft;
import com.ruleup.ruleup_backend.challenge.draft.ChallengeDraftRepository;
import com.ruleup.ruleup_backend.challenge.draft.DraftView;
import com.ruleup.ruleup_backend.challenge.dto.CloneResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 템플릿 복제 (탐색 백엔드 테크스펙 §11-5).
 *
 * <p>탐색 모듈은 <b>원본 조회와 권한 검증까지만</b> 책임지고, 초안 저장 형식·생성 규칙은 생성 모듈의
 * 것을 그대로 쓴다. 그래서 응답의 draft 는 경로 A·B 와 같은 스키마이고 확인 화면·생성 API 를 재사용한다.
 *
 * <p>프리필 규칙: 루틴·인증 방식·목표값·기간 <b>길이</b>는 원본 그대로 두되,
 * 시작일은 생성일+1 로 다시 잡고 mode·공개 범위·정원은 <b>그룹·공개·30</b>, 최소 티어는 내 표시 티어로 리셋한다.
 * 남의 방 설정을 그대로 물려받으면 내 티어로는 못 들어가는 방을 만들게 되기 때문이다.
 * 이미지는 복사하지 않는다 — 원본 방장이 올린 이미지의 소유·심사 이력을 승계할 수 없다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeCloneService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 초안 생성과 같은 기본값을 쓴다 — 여기만 다르면 복제 초안을 그대로 확정할 때 400 이 된다. */
    private static final int DEFAULT_CAPACITY =
            com.ruleup.ruleup_backend.challenge.domain.ChallengeCapacity.DEFAULT;
    private static final int START_OFFSET_DAYS = 1;

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeDraftRepository draftRepository;
    private final RoutineCatalog catalog;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final com.ruleup.ruleup_backend.observability.BusinessMetrics businessMetrics;

    @Transactional
    public CloneResponse clone(UUID userId, UUID challengeId) {
        Challenge origin = challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        boolean inside = origin.isOwner(userId) || memberRepository.findByChallengeIdAndUserId(challengeId, userId)
                .filter(m -> m.isActive()).isPresent();
        if (!cloneable(origin, inside)) {
            // 볼 수 없는 방은 403 NOT_CLONEABLE 이다. 존재 은닉은 <b>상세 조회</b>의 규칙이고, 복제는
            // id 를 이미 아는 사람만 부를 수 있는 경로다 — 여기서 404 를 섞으면 클라가 「없는 방」과
            // 「복제만 안 되는 방」을 구분하지 못해, 사전 비활성 + 토스트라는 명세의 처리 방식을 그릴 수 없다.
            throw new BusinessException(ErrorCode.NOT_CLONEABLE);
        }

        LocalDate start = LocalDate.now(KST).plusDays(START_OFFSET_DAYS);
        LocalDate end = origin.getEndDate() == null ? null : start.plusDays(ChronoUnit.DAYS.between(origin.getStartDate(), origin.getEndDate()));

        RoutineTemplate template = (origin.getTemplateId() != null)
                ? catalog.findById(origin.getTemplateId()).orElse(null) : null;
        boolean auto = origin.getVerificationConfig() != null
                && origin.getVerificationConfig().selectedMethod() == SelectedMethod.AUTO;
        int weeklyCount = origin.getWeeklyCount() != null
                ? origin.getWeeklyCount() : Math.max(1, Math.min(7, origin.getRepeatDays().size()));

        DraftView view = new DraftView(
                // 타인에게 공개 가능한 승인·면제 문구만 복제한다. 심사 중·거부 문구는
                // 공개 상세와 같은 대체 규칙을 적용해 draft 심사 면제 경로로 새지 않게 한다.
                origin.publicTitle(),
                origin.publicDescription(),
                origin.getCategory(),
                ParticipationType.GROUP.name(),                // 복제 기본값 — 그룹·공개·정원 30(09-28 결정)
                "PUBLIC",
                null,                                          // 랭킹 노출은 솔로 전용 설정
                DEFAULT_CAPACITY,
                displayTier(userId).name(),                    // 내 표시 티어로 리셋
                new DraftView.Period(start.toString(), end == null ? null : end.toString()),
                weeklyCount,
                (origin.getParamSpecs() != null) ? origin.getParamSpecs() : List.of(),
                new DraftView.Verification(
                        auto ? "AUTO" : "MANUAL",
                        auto && template != null ? template.getVerificationMethod() : "SELF_CHECK",
                        (origin.getVerificationConfig() != null
                                && origin.getVerificationConfig().requiredPermissions() != null)
                                ? origin.getVerificationConfig().requiredPermissions() : List.of()),
                // 그룹이므로 그룹 공유 ON 고정. 점수 패널티는 인증 방식을 따라간다(생성 규칙과 같다)
                new DraftView.Penalties(auto, true, false));

        ChallengeDraft saved = draftRepository.save(ChallengeDraft.of(
                userId, ChallengeDraft.Origin.CLONE, origin.getTemplateId(), challengeId,
                view, weeklyCount, Instant.now()));
        businessMetrics.draftCreated(ChallengeDraft.Origin.CLONE);

        return new CloneResponse(saved.getId().toString(), challengeId.toString(), view);
    }

    /**
     * 복제할 수 있는가 — 그 방을 볼 수 있으면 된다. 공개 그룹은 누구나, 비공개·솔로 방은 방장과
     * 참여 중인 멤버만. 비공개 방도 방 정보는 복제할 수 있다(09-28 결정, QA CRE-06).
     * 상세의 {@code cloneable} 도 이 규칙을 쓴다.
     */
    public static boolean cloneable(Challenge c, boolean ownerOrActiveMember) {
        return ownerOrActiveMember
                || (c.getParticipationType() == ParticipationType.GROUP && "PUBLIC".equals(c.getVisibility()));
    }

    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId).map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }
}
