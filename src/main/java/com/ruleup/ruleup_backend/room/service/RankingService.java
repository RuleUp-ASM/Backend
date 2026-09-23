package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.report.BlockService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RankingService {
    public static final int MIN_PARTICIPATIONS = 10;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final BlockService blockService;

    public record Ranked(Integer rank, boolean ranked, UUID userId, String nickname,
                         String profileImageUrl, boolean blocked, BigDecimal successRate,
                         int successCount, int participations) {}

    public List<Ranked> rank(Challenge challenge, UUID viewerId) {
        List<ChallengeMember> members = new ArrayList<>(memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(challenge.getId(), MemberStatus.ACTIVE));
        members.sort(Comparator
                .comparing((ChallengeMember m) -> m.getSuccessDays() + m.getFailDays() >= MIN_PARTICIPATIONS)
                .reversed()
                .thenComparing(this::rate, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingInt(ChallengeMember::getSuccessDays).reversed())
                .thenComparing(m -> m.getJoinedAt() == null ? Instant.EPOCH : m.getJoinedAt()));
        Map<UUID, User> users = userRepository.findAllById(members.stream().map(ChallengeMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        // 차단은 조회자 한정 효과라 순위 계산에는 개입하지 않는다 — 표시만 가린다.
        // 순위에서 빼버리면 남은 사람들의 등수가 조회자마다 달라져 같은 방에서 다른 랭킹을 보게 된다.
        Set<UUID> blocked = blockService.blockedUsers(viewerId);

        List<Ranked> result = new ArrayList<>();
        Integer rank = null;
        BigDecimal previousRate = null;
        Integer previousSuccess = null;
        for (int i = 0; i < members.size(); i++) {
            ChallengeMember member = members.get(i);
            int participations = member.getSuccessDays() + member.getFailDays();
            boolean ranked = participations >= MIN_PARTICIPATIONS;
            BigDecimal rate = ranked ? rate(member) : null;
            if (ranked && (previousRate == null || rate.compareTo(previousRate) != 0
                    || member.getSuccessDays() != previousSuccess)) rank = i + 1;
            if (!ranked) rank = null;
            User user = users.get(member.getUserId());
            boolean masked = blocked.contains(member.getUserId());
            String nickname = user == null ? null
                    : masked ? user.deriveTempNickname()
                    : challenge.getAnonymity().maskNickname(user.visibleNicknameTo(viewerId));
            String profileImage = (user == null || masked || challenge.getAnonymity().isAnonymous())
                    ? null : user.visibleProfileImageTo(viewerId);
            result.add(new Ranked(rank, ranked, member.getUserId(), nickname, profileImage, masked,
                    rate, member.getSuccessDays(), participations));
            if (ranked) {
                previousRate = rate;
                previousSuccess = member.getSuccessDays();
            }
        }
        return result;
    }

    private BigDecimal rate(ChallengeMember member) {
        int total = member.getSuccessDays() + member.getFailDays();
        if (total == 0) return BigDecimal.ZERO.setScale(4);
        return BigDecimal.valueOf(member.getSuccessDays())
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}
