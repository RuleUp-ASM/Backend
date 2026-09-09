package com.ruleup.ruleup_backend.inquiry;

import com.ruleup.ruleup_backend.inquiry.domain.Inquiry;
import com.ruleup.ruleup_backend.inquiry.domain.InquiryStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {

    /** 내 문의 내역 — 최신순. {@code ix_inquiries_user} 를 탄다. */
    List<Inquiry> findByUserIdOrderByCreatedAtDesc(UUID userId, Limit limit);

    /** 하루 접수 상한(§ 5.7 제안값 3건) 판정. 기간은 호출부가 KST 하루로 잘라 넘긴다. */
    long countByUserIdAndCreatedAtBetween(UUID userId, Instant from, Instant to);

    /**
     * 관리자 큐 — <b>접수 순(오래된 것 먼저)</b>이며 커서는 {@code createdAt} 한 축이다.
     *
     * <p>최신순으로 두지 않는 이유는 이것이 작업 큐이기 때문이다. 최신순이면 가장 오래
     * 기다린 문의가 목록 맨 아래로 밀려 SLA 를 어기는 건부터 눈에서 사라진다.
     *
     * <p>필터가 전부 nullable 이라 <b>같은 쿼리 하나</b>로 조합을 받는다. 조건마다 메서드를
     * 만들면 상태·분류·검색어의 8가지 조합이 그대로 메서드 수가 된다.
     */
    @Query("""
            SELECT i FROM Inquiry i
             WHERE (:status IS NULL OR i.status = :status)
               AND (:category IS NULL OR i.category = :category)
               AND (:keyword IS NULL OR i.body LIKE %:keyword%)
               AND (:cursor IS NULL OR i.createdAt > :cursor)
             ORDER BY i.createdAt ASC
            """)
    List<Inquiry> findQueue(@Param("status") InquiryStatus status,
                            @Param("category") com.ruleup.ruleup_backend.inquiry.domain.InquiryCategory category,
                            @Param("keyword") String keyword,
                            @Param("cursor") Instant cursor,
                            Limit limit);

    @Query("""
            SELECT COUNT(i) FROM Inquiry i
             WHERE (:status IS NULL OR i.status = :status)
               AND (:category IS NULL OR i.category = :category)
               AND (:keyword IS NULL OR i.body LIKE %:keyword%)
            """)
    long countQueue(@Param("status") InquiryStatus status,
                    @Param("category") com.ruleup.ruleup_backend.inquiry.domain.InquiryCategory category,
                    @Param("keyword") String keyword);

    long countByStatus(InquiryStatus status);

    long countByAnsweredAtBetween(Instant from, Instant to);

    long countByCreatedAtBetween(Instant from, Instant to);
}
