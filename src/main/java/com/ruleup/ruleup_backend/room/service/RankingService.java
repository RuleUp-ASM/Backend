package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 그룹 랭킹(방 내부기능 §7.3). ACTIVE 멤버를 비정규화 진행률로 정렬만 한다(집계 쿼리 신설 없음).
 * 정렬: progressRate desc → successDays desc → joinedAt asc. 닉네임은 visibleNicknameTo + 익명 마스킹.
 */
@Service
@RequiredArgsConstructor
public class RankingService {

    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;

    /** 정렬된 랭킹 1줄. */
    public record Ranked(int rank, UUID userId, String nickname, BigDecimal progressRate, int successDays) {}

    /** 챌린지 ACTIVE 멤버 랭킹(viewer 기준 닉네임 마스킹 적용). */
    public List<Ranked> rank(Challenge challenge, UUID viewerId) {
        List<ChallengeMember> members = new ArrayList<>(
                memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challenge.getId(), MemberStatus.ACTIVE));
        members.sort(Comparator
                .comparing(ChallengeMember::getProgressRate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingInt(ChallengeMember::getSuccessDays).reversed())
                .thenComparing(m -> m.getJoinedAt() != null ? m.getJoinedAt() : Instant.EPOCH));

        Map<UUID, User> users = userRepository.findAllById(
                        members.stream().map(ChallengeMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<Ranked> out = new ArrayList<>();
        int rank = 1;
        for (ChallengeMember m : members) {
            User u = users.get(m.getUserId());
            String visible = (u != null) ? u.visibleNicknameTo(viewerId) : null;
            String nickname = challenge.getAnonymity().maskNickname(visible);
            out.add(new Ranked(rank++, m.getUserId(), nickname, m.getProgressRate(), m.getSuccessDays()));
        }
        return out;
    }
}
