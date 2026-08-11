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
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
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
 * 시작일은 생성일+1 로 다시 잡고 mode·정원·최소 티어는 <b>생성 기본값으로 리셋</b>한다.
 * 남의 방 설정을 그대로 물려받으면 내 티어로는 못 들어가는 방을 만들게 되기 때문이다.
 * 이미지는 복사하지 않는다 — 원본 방장이 올린 이미지의 소유·심사 이력을 승계할 수 없다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeCloneService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_CAPACITY = 50;
    private static final int START_OFFSET_DAYS = 1;

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeDraftRepository draftRepository;
    private final RoutineCatalog catalog;
    private final UserScoreSummaryRepository scoreSummaryRepository;

    @Transactional
    public CloneResponse clone(UUID userId, UUID challengeId) {
        Challenge origin = challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        boolean group = origin.getParticipationType() == ParticipationType.GROUP;
        boolean isPublic = "PUBLIC".equals(origin.getVisibility());
        if (!group || !isPublic) {
            // 볼 수 없는 사람에게는 존재 자체를 숨기고, 볼 수 있는 사람에게만 "복제 불가"라고 답한다.
            boolean canSee = origin.isOwner(userId)
                    || memberRepository.findByChallengeIdAndUserId(challengeId, userId)
                    .filter(m -> m.isActive()).isPresent();
            throw new BusinessException(canSee ? ErrorCode.NOT_CLONEABLE : ErrorCode.CHALLENGE_NOT_FOUND);
        }

        LocalDate start = LocalDate.now(KST).plusDays(START_OFFSET_DAYS);
        LocalDate end = start.plusDays(ChronoUnit.DAYS.between(origin.getStartDate(), origin.getEndDate()));

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
                ParticipationType.SOLO.name(),                 // 생성 기본값
                null,                                          // 솔로는 공개 범위 없음
                Boolean.TRUE,                                  // 솔로 랭킹 노출 기본 true
                DEFAULT_CAPACITY,
                displayTier(userId).name(),                    // 내 표시 티어로 리셋
                new DraftView.Period(start.toString(), end.toString()),
                weeklyCount,
                (origin.getParamSpecs() != null) ? origin.getParamSpecs() : List.of(),
                new DraftView.Verification(
                        auto ? "AUTO" : "MANUAL",
                        auto && template != null ? template.getVerificationMethod() : "SELF_CHECK",
                        (origin.getVerificationConfig() != null
                                && origin.getVerificationConfig().requiredPermissions() != null)
                                ? origin.getVerificationConfig().requiredPermissions() : List.of()),
                // 솔로로 리셋되므로 그룹 공유는 off. 점수 패널티는 인증 방식을 따라간다
                new DraftView.Penalties(auto, false, false));

        ChallengeDraft saved = draftRepository.save(ChallengeDraft.of(
                userId, ChallengeDraft.Origin.CLONE, origin.getTemplateId(), challengeId,
                view, weeklyCount, Instant.now()));

        return new CloneResponse(saved.getId().toString(), challengeId.toString(), view);
    }

    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId).map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }
}
