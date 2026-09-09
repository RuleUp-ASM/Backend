package com.ruleup.ruleup_backend.inquiry;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.inquiry.domain.Inquiry;
import com.ruleup.ruleup_backend.inquiry.domain.InquiryCategory;
import com.ruleup.ruleup_backend.inquiry.dto.InquiryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * CS 문의 접수·열람(유저) — 앱 운영 정책 § 5.
 *
 * <h4>이 서비스가 하는 일은 접수와 열람뿐이다</h4>
 * 답변·분류 변경은 운영자 경로({@code AdminInquiryService})가 소유한다. 유저는 접수한 뒤
 * <b>읽기만</b> 하며, 답변 이후 같은 스레드에 글을 더할 경로가 없다(§ 5.5 — 재문의 폐지).
 *
 * <h4>계정이 잠겨도 여기는 열려 있다</h4>
 * 운영자 제재 정책 § 5.3 의 로그인 정지 중 허용 행위는 열람과 CS 문의뿐이다. 그래서
 * {@code AccountStatusFilter} 의 화이트리스트에 접수 경로가 들어가 있다 — 제재를 다투는
 * 유일한 창구를 제재가 막으면 재검토 자체가 성립하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    /** 본문 길이 — § 5.2. */
    private static final int BODY_MIN = 10;
    private static final int BODY_MAX = 1000;

    /** 이미지 최대 장수 — § 5.2. 장당 크기·형식은 업로드 API 가 이미 검증한다. */
    private static final int IMAGE_MAX = 3;

    /**
     * 하루 접수 상한. § 5.7 이 「하루 3건 · 같은 분류 중복 접수 차단」을 함께 제안했지만
     * <b>상한만 넣는다</b> — 미결 사항 스스로가 적었듯 후속 질문을 새 문의로만 받는 구조에서
     * 같은 분류 중복을 막으면 후속 경로가 사라진다.
     */
    private static final int DAILY_LIMIT = 3;

    /** 하루의 경계는 KST 다. UTC 자정으로 자르면 한국 유저에게 아침 9시에 상한이 풀린다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final int LIST_SIZE = 50;
    private static final int PREVIEW_LENGTH = 60;

    private final InquiryRepository repository;

    @Transactional
    public InquiryDtos.CreateResponse create(UUID userId, InquiryDtos.CreateRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        InquiryCategory category = parseCategory(request.category());
        String body = request.body() == null ? "" : request.body().trim();
        if (body.length() < BODY_MIN || body.length() > BODY_MAX)
            throw new BusinessException(ErrorCode.INQUIRY_BODY_LENGTH);

        List<String> images = request.imageUrls() == null ? List.of() : request.imageUrls();
        if (images.size() > IMAGE_MAX) throw new BusinessException(ErrorCode.INQUIRY_IMAGE_LIMIT);

        Instant now = Instant.now();
        if (repository.countByUserIdAndCreatedAtBetween(userId, startOfKstDay(now), now) >= DAILY_LIMIT)
            throw new BusinessException(ErrorCode.INQUIRY_DAILY_LIMIT);

        Inquiry inquiry = repository.save(Inquiry.of(userId, category, body, images,
                request.appVersion(), request.osVersion(), request.deviceModel(),
                request.errorLogId(), now));

        return new InquiryDtos.CreateResponse(inquiry.getId().toString(),
                inquiry.getStatus().name(), now.toString());
    }

    @Transactional(readOnly = true)
    public InquiryDtos.ListResponse list(UUID userId) {
        return new InquiryDtos.ListResponse(
                repository.findByUserIdOrderByCreatedAtDesc(userId, Limit.of(LIST_SIZE)).stream()
                        .map(i -> new InquiryDtos.Item(
                                i.getId().toString(),
                                i.getCategory().name(),
                                i.getStatus().name(),
                                preview(i.getBody()),
                                i.getCreatedAt().toString(),
                                i.getAnsweredAt() == null ? null : i.getAnsweredAt().toString()))
                        .toList());
    }

    /** 남의 문의는 없는 것과 같다 — 소유자가 아니면 404 다. */
    @Transactional(readOnly = true)
    public InquiryDtos.Detail detail(UUID userId, UUID inquiryId) {
        Inquiry inquiry = repository.findById(inquiryId)
                .filter(i -> i.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));

        return new InquiryDtos.Detail(
                inquiry.getId().toString(),
                inquiry.getCategory().name(),
                inquiry.getStatus().name(),
                inquiry.getBody(),
                inquiry.imageUrlsOrEmpty(),
                inquiry.getCreatedAt().toString(),
                inquiry.getAnswerText(),
                inquiry.getAnsweredAt() == null ? null : inquiry.getAnsweredAt().toString());
    }

    private String preview(String body) {
        return body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH) + "…";
    }

    private Instant startOfKstDay(Instant now) {
        return LocalDate.ofInstant(now, KST).atStartOfDay(KST).toInstant();
    }

    private InquiryCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        try {
            return InquiryCategory.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
