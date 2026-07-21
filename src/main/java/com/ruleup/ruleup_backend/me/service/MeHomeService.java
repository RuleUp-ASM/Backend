package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.me.CompletionPolicy;
import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 마이 홈(GET /me/home): 프로필 요약 + 온도 + 카운트(완주·진행·그룹)를 읽기 전용으로 조립. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeHomeService {

    private final UserRepository userRepository;
    private final ReputationScoreRepository reputationScoreRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeRepository challengeRepository;

    public MeHomeResponse home(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        BigDecimal temp = reputationScoreRepository.findById(userId)
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);

        MeHomeResponse.Counts counts = counts(userId);
        return new MeHomeResponse(
                user.getNickname(), user.getNicknameStatus().name(), user.getProfileImageUrl(),
                temp, counts);
    }

    private MeHomeResponse.Counts counts(UUID userId) {
        List<ChallengeMember> memberships = memberRepository.findByUserId(userId);
        Map<UUID, Challenge> byId = challengeRepository
                .findAllById(memberships.stream().map(ChallengeMember::getChallengeId).toList())
                .stream().collect(Collectors.toMap(Challenge::getId, Function.identity()));

        int completed = 0, inProgress = 0, groups = 0;
        for (ChallengeMember m : memberships) {
            Challenge c = byId.get(m.getChallengeId());
            if (c == null) continue;
            boolean done = CompletionPolicy.isCompleted(m.getProgressRate());
            if (done) completed++;
            // 진행 중 = 현재 참여(ACTIVE 멤버) + 미완주 + 종료 전 챌린지.
            if (m.isActive() && !done && c.getStatus() != ChallengeStatus.COMPLETED) inProgress++;
            // 그룹 = 현재 참여 중인 그룹 챌린지.
            if (m.isActive() && c.isGroup()) groups++;
        }
        return new MeHomeResponse.Counts(completed, inProgress, groups);
    }
}
