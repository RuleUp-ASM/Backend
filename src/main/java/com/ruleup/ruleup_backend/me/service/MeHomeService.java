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
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
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
    private final MeChallengeCounts challengeCounts;
    private final SanctionService sanctionService;
    private final MePermissionWarnings permissionWarnings;

    public MeHomeResponse home(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        UserScoreSummary score = scoreRepository.findById(userId)
                .orElseGet(() -> UserScoreSummary.initialize(userId));

        Optional<Sanction> active = sanctionService.activeSanction(userId);

        Optional<Sanction> lock=active.filter(s->s.getType()==com.ruleup.ruleup_backend.sanction.domain.SanctionType.LOCK
                || s.getType()==com.ruleup.ruleup_backend.sanction.domain.SanctionType.BAN);
        return new MeHomeResponse(
                user.getNickname(), user.getNicknameStatus().name(),
                user.visibleProfileImageTo(userId), user.getProfileImageStatus().name(),
                score.getActualTier().name(), score.getTotalScore(), score.getDisplayTier().name(),
                challengeCounts.counts(userId),
                lock.isPresent()?LOCKED:ACTIVE,
                lock.map(this::lockInfo).orElse(null),permissionWarnings.of(userId));
    }

    /** 잠금 사유와 해제일. 사유를 볼 수 없으면 사용자는 왜 막혔는지 알 방법이 없다. */
    private MeHomeResponse.LockInfo lockInfo(Sanction sanction) {
        return new MeHomeResponse.LockInfo(
                sanction.getReasonCode() != null ? sanction.getReasonCode().name() : null,
                sanction.getEndsAt() != null ? sanction.getEndsAt().toString() : null);
    }

}
