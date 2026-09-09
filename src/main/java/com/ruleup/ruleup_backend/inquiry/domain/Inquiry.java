package com.ruleup.ruleup_backend.inquiry.domain;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CS 문의 1건 — 앱 운영 정책 § 5, 백오피스 공통 5-2-1 B.
 *
 * <h4>답변이 컬럼인 이유</h4>
 * 스레드 테이블이 아니라 이 행에 답변을 붙인다. <b>재문의 경로가 없어</b>(§ 5.5) 문의:답변이
 * 1:1 로 고정이고, 그 구조를 테이블로 강제하면 「답변이 2개인 문의」라는 상태가 아예 생기지
 * 않는다. 답변 재등록은 409 로 막지만, 막는 코드보다 그런 행을 만들 수 없는 스키마가 낫다.
 *
 * <h4>분류가 둘인 이유</h4>
 * {@code category} 는 운영자가 바꿀 수 있고 {@code originCategory} 는 유저가 처음 고른 값이다.
 * <b>변경 사실을 유저에게 노출하지 않으므로</b>(§ 5.1) 유저 응답에는 현재 분류만 나가고,
 * 「분류별 접수 비중」 지표(§ 5.8)는 운영자 손을 타지 않은 원본 분류로 센다.
 */
@Entity
@Table(name = "inquiries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private InquiryCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_category", nullable = false, length = 30, updatable = false)
    private InquiryCategory originCategory;

    @Column(name = "body", nullable = false, length = 1000, updatable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_urls", updatable = false)
    private List<String> imageUrls;

    @Column(name = "app_version", length = 20, updatable = false)
    private String appVersion;

    @Column(name = "os_version", length = 30, updatable = false)
    private String osVersion;

    @Column(name = "device_model", length = 60, updatable = false)
    private String deviceModel;

    @Column(name = "error_log_id", length = 64, updatable = false)
    private String errorLogId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InquiryStatus status;

    @Column(name = "answer_text", length = 2000)
    private String answerText;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "answered_by")
    private UUID answeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Inquiry of(UUID userId, InquiryCategory category, String body,
                             List<String> imageUrls, String appVersion, String osVersion,
                             String deviceModel, String errorLogId, Instant at) {
        Inquiry inquiry = new Inquiry();
        inquiry.id = UuidGenerator.generate();
        inquiry.userId = userId;
        inquiry.category = category;
        inquiry.originCategory = category;
        inquiry.body = body;
        inquiry.imageUrls = (imageUrls == null || imageUrls.isEmpty()) ? null : List.copyOf(imageUrls);
        inquiry.appVersion = appVersion;
        inquiry.osVersion = osVersion;
        inquiry.deviceModel = deviceModel;
        inquiry.errorLogId = errorLogId;
        inquiry.status = InquiryStatus.RECEIVED;
        inquiry.createdAt = at;
        return inquiry;
    }

    /** 답변 등록 = 종결. 이미 답변된 건은 호출부가 409 로 막는다. */
    public void answer(String text, UUID operatorId, Instant at) {
        this.answerText = text;
        this.answeredBy = operatorId;
        this.answeredAt = at;
        this.status = InquiryStatus.ANSWERED;
    }

    /** 운영자 분류 변경 — 원본 분류는 건드리지 않는다. */
    public void reclassify(InquiryCategory category) {
        this.category = category;
    }

    public boolean isAnswered() {
        return status == InquiryStatus.ANSWERED;
    }

    public List<String> imageUrlsOrEmpty() {
        return imageUrls == null ? List.of() : imageUrls;
    }
}
