package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "내가 이미 들어가 있는 방" 판별 — 목록(인기·둘러보기) 응답의 {@code joined} 표시용.
 *
 * <p>목록은 후보 조건이 PUBLIC + GROUP + UPCOMING/ACTIVE 라서 <b>내가 만든 방·이미 가입한 방도
 * 그대로 섞여 나온다</b>(의도된 동작 — 내 방이 인기 목록에서 사라지면 그게 더 이상하다).
 * 문제는 목록에 "이미 참여 중"이라는 신호가 없으면 클라가 그 방에도 참여 버튼을 그리고,
 * 누르면 서버가 {@code ALREADY_JOINED} 로 막는다는 것이다. 그 신호를 여기서 만든다.
 *
 * <p>동시 참여 한도가 3이라 사용자당 행이 몇 개뿐이고(종료된 방의 멤버십이 남아도 소수),
 * 목록 한 페이지마다 조회 한 번이면 끝나 N+1 이 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MyMembershipReader {

    private final ChallengeMemberRepository memberRepository;

    /** viewer 가 현재 멤버(ACTIVE)인 챌린지 id 집합. */
    public Set<UUID> activeChallengeIds(UUID userId) {
        if (userId == null) return Set.of();
        return memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE).stream()
                .map(ChallengeMember::getChallengeId)
                .collect(Collectors.toSet());
    }
}
