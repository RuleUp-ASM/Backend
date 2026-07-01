package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.util.List;

/**
 * 챌린지 전체 조회(탐색/목록) 응답. 카드 렌더에 필요한 최소 필드만 담는다.
 *
 * <p>상세(§3.3 {@link ChallengeDetailResponse})와 달리 owner/stats/eligibility/verification/params 는
 * 싣지 않는다(목록은 가벼워야 하고, 상세는 진입 시 별도 조회). 노출 대상은 모더레이션 APPROVED·미삭제 챌린지.
 *
 *  - challenges     : 카드 항목.
 *  - page/size      : 0-base 페이지 번호 / 페이지 크기.
 *  - totalElements  : 필터 적용된 전체 건수.
 *  - totalPages     : 전체 페이지 수.
 *  - hasNext        : 다음 페이지 존재 여부(무한 스크롤용).
 */
public record ChallengeListResponse(
        List<Item> challenges,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public record Item(
            String challengeId,
            String title,
            String description,
            String imageUrl,
            String category,
            String participationType,
            String status,
            String anonymity,
            Integer participantCount,
            BigDecimal minMannerTemperature,
            List<String> repeatDays,
            Integer durationDays,
            String startDate,
            String endDate
    ) {
        public static Item from(Challenge c) {
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
                    c.getEndDate().toString());
        }
    }

    public static ChallengeListResponse from(Page<Challenge> page) {
        return new ChallengeListResponse(
                page.getContent().stream().map(Item::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
