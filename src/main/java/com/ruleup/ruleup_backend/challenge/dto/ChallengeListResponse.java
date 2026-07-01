package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;

import java.math.BigDecimal;
import java.util.List;

/**
 * 내 챌린지 목록 응답. "내가 참여 중인 챌린지"를 카드 리스트로 돌려준다.
 *
 * <p>탐색/공개 목록이 아니라 로그인 사용자의 멤버십 기준 목록이므로 페이지네이션을 두지 않는다
 * (한 사람이 참여하는 챌린지 수는 제한적 → 전량 반환). 항목마다 내 멤버십 상태(memberStatus)를 함께 실어
 * "참여 중(ACTIVE)"과 "승인 대기(PENDING)"를 구분할 수 있게 한다.
 */
public record ChallengeListResponse(
        List<Item> challenges
) {
    public record Item(
            String challengeId,
            String title,
            String description,
            String imageUrl,
            String category,
            String participationType,
            String status,              // 챌린지 lifecycle: RECRUITING / ACTIVE / COMPLETED
            String anonymity,
            Integer participantCount,
            BigDecimal minMannerTemperature,
            List<String> repeatDays,
            Integer durationDays,
            String startDate,
            String endDate,
            String memberStatus         // 내 멤버십 상태: ACTIVE / PENDING
    ) {
        public static Item of(Challenge c, String memberStatus) {
            return new Item(
                    c.getId().toString(),
                    c.getTitle(),
                    c.getDescription(),
                    c.getImageUrl(),
                    c.getCategory(),
                    c.getParticipationType().name(),
                    c.getStatus().name(),
                    c.getAnonymity().name(),
                    c.getParticipantCount(),
                    c.getMinMannerTemperature(),
                    c.getRepeatDays(),
                    c.getDurationDays(),
                    c.getStartDate().toString(),
                    c.getEndDate().toString(),
                    memberStatus);
        }
    }
}
