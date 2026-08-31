package com.ruleup.ruleup_backend.sanction;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.sanction.domain.Sanction;
import com.ruleup.ruleup_backend.sanction.domain.SanctionTrack;
import com.ruleup.ruleup_backend.sanction.dto.MySanctionsResponse;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 내 제재 이력 조립 — 조회 전용.
 *
 * <p>두 트랙의 원천이 다르다. <b>직권 제재는 {@code sanctions}</b>, <b>자동 제재는 방 단위
 * 강퇴 기록</b>({@code challenge_members} 의 REMOVED 행)이다. 계정 제재와 챌린지 강퇴는
 * 성격이 달라 합산하지 않으므로 배열도 나눠 내린다.
 */
@Service
@RequiredArgsConstructor
public class MySanctionService {

    private final UserRepository userRepository;
    private final SanctionRepository sanctionRepository;
    private final SanctionService sanctionService;
    private final ChallengeMemberRepository challengeMemberRepository;
    private final ChallengeRepository challengeRepository;

    @Transactional(readOnly = true)
    public MySanctionsResponse of(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));

        Sanction active = sanctionService.activeSanction(userId).orElse(null);

        return new MySanctionsResponse(
                user.getStatus().name(),
                active == null ? null : toActive(active),
                adminTrack(userId),
                autoTrack(userId));
    }

    private MySanctionsResponse.Active toActive(Sanction s) {
        return new MySanctionsResponse.Active(
                s.getId().toString(),
                s.getTrack().apiValue(),
                s.getType().name(),
                s.getFeatureCode() == null ? null : s.getFeatureCode().name(),
                s.getReasonCode().name(),
                s.getReasonText(),
                s.getStartsAt().toString(),
                s.getEndsAt() == null ? null : s.getEndsAt().toString(),   // BAN·동결은 null
                !s.isAppealUsed());
    }

    private List<MySanctionsResponse.AdminItem> adminTrack(UUID userId) {
        return sanctionRepository
                .findByUserIdAndTrackOrderByStartsAtDesc(userId, SanctionTrack.DISCRETIONARY).stream()
                .map(s -> new MySanctionsResponse.AdminItem(
                        s.getId().toString(),
                        s.getType().name(),
                        s.getFeatureCode() == null ? null : s.getFeatureCode().name(),
                        s.getReasonCode().name(),
                        s.getStartsAt().toString(),
                        s.getEndsAt() == null ? null : s.getEndsAt().toString(),
                        s.isAppealUsed() ? "USED" : "NONE"))
                .toList();
    }

    /**
     * 자동 제재 = 챌린지 강퇴. {@code rejoin_available_at} 이 null 이면 <b>영구 차단</b>이다 —
     * 부정행위 검출은 해당 챌린지 영구 차단이라 재입장 시각이 없다.
     */
    private List<MySanctionsResponse.AutoItem> autoTrack(UUID userId) {
        List<ChallengeMember> kicked = challengeMemberRepository
                .findByUserIdAndStatus(userId, MemberStatus.REMOVED).stream()
                .filter(m -> m.getKickReason() != null)
                .toList();
        if (kicked.isEmpty()) return List.of();

        Map<UUID, String> titles = challengeRepository
                .findAllById(kicked.stream().map(ChallengeMember::getChallengeId).toList()).stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> c.getTitle(), (a, b) -> a));

        return kicked.stream()
                .sorted((a, b) -> compareNullable(b.getLeftAt(), a.getLeftAt()))
                .map(m -> new MySanctionsResponse.AutoItem(
                        m.getChallengeId().toString(),
                        titles.getOrDefault(m.getChallengeId(), null),
                        m.getKickReason(),
                        m.getRejoinAvailableAt() == null,
                        m.getRejoinAvailableAt() == null ? null : m.getRejoinAvailableAt().toString(),
                        m.getLeftAt() == null ? null : m.getLeftAt().toString()))
                .toList();
    }

    private int compareNullable(Instant a, Instant b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private static final Function<UUID, String> ID = UUID::toString;
}
