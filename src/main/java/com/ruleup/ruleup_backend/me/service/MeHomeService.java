package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.me.CompletionPolicy;
import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import com.ruleup.ruleup_backend.sanction.SanctionService;
import com.ruleup.ruleup_backend.sanction.domain.Sanction;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 마이 탭 메인(GET /me/home) — 프로필 요약 + 티어 + 카운트 + 계정 상태를 1회 호출로 조립한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeHomeService {

    private static final String ACTIVE = "ACTIVE";
    private static final String LOCKED = "LOCKED";

    private final UserRepository userRepository;
    private final UserScoreSummaryRepository scoreRepository;
    private final ChallengeMemberRepository memberRepository;
    private final SanctionService sanctionService;

    public MeHomeResponse home(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        UserScoreSummary score = scoreRepository.findById(userId)
                .orElseGet(() -> UserScoreSummary.initialize(userId));

        Optional<Sanction> active = sanctionService.activeSanction(userId);

        return new MeHomeResponse(
                user.getNickname(), user.getNicknameStatus().name(),
                user.getProfileImageUrl(), user.getProfileImageStatus().name(),
                score.getActualTier().name(), score.getTotalScore(), score.getDisplayTier().name(),
                counts(userId),
                active.isPresent() ? LOCKED : ACTIVE,
                active.map(this::lockInfo).orElse(null));
    }

    /** 잠금 사유와 해제일. 사유를 볼 수 없으면 사용자는 왜 막혔는지 알 방법이 없다. */
    private MeHomeResponse.LockInfo lockInfo(Sanction sanction) {
        return new MeHomeResponse.LockInfo(
                sanction.getReasonCode() != null ? sanction.getReasonCode().name() : null,
                sanction.getEndsAt() != null ? sanction.getEndsAt().toString() : null);
    }

    /**
     * 진행 중 / 완주 / 이탈. 마이페이지 탭 세 개가 그대로 이 셋이다.
     *
     * <p>완주와 이탈은 겹치지 않는다 — 완주 커트라인을 넘긴 뒤 방을 나간 사람은 완주로 센다.
     * 이탈 탭은 "못 채우고 나온 방"을 보여주는 자리이기 때문이다.
     */
    private MeHomeResponse.Counts counts(UUID userId) {
        int inProgress = 0, completed = 0, left = 0;
        List<ChallengeMember> memberships = memberRepository.findByUserId(userId);
        for (ChallengeMember m : memberships) {
            boolean done = CompletionPolicy.isCompleted(m.getProgressRate());
            if (done) { completed++; continue; }
            if (m.getStatus() == MemberStatus.LEFT || m.getStatus() == MemberStatus.REMOVED) left++;
            else if (m.isActive()) inProgress++;
        }
        return new MeHomeResponse.Counts(inProgress, completed, left);
    }
}
