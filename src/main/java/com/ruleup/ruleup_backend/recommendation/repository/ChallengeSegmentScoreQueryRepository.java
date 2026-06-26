package com.ruleup.ruleup_backend.recommendation.repository;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.recommendation.dto.ChallengeCreatorSegmentRow;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 추천 점수 재계산용 Challenge 조회(읽기 전용 마커 Repository).
 *  - CRUD를 노출하지 않으려고 JpaRepository가 아닌 Repository 마커를 상속.
 *  - 윈도우(생성일 >= since) 안의 살아있는 챌린지를 생성자 인구통계와 JOIN해서 투영만 가져온다.
 */
public interface ChallengeSegmentScoreQueryRepository extends Repository<Challenge, UUID> {

    @Query("""
            select new com.ruleup.ruleup_backend.recommendation.dto.ChallengeCreatorSegmentRow(
                c.templateId, u.countryCode, u.gender, u.birthDate)
            from Challenge c, User u
            where u.id = c.creatorId
              and c.deletedAt is null
              and c.templateId is not null
              and c.createdAt >= :since
            """)
    List<ChallengeCreatorSegmentRow> findCreatorSegmentRowsSince(@Param("since") Instant since);
}
