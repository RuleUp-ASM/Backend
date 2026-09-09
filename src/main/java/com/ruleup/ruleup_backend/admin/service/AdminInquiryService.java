package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.dto.AdminInquiryDtos;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.inquiry.InquiryRepository;
import com.ruleup.ruleup_backend.inquiry.domain.Inquiry;
import com.ruleup.ruleup_backend.inquiry.domain.InquiryCategory;
import com.ruleup.ruleup_backend.inquiry.domain.InquiryStatus;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.sanction.SanctionRepository;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CS 문의 처리(운영자) — 백오피스 공통 5-2-1 B.
 *
 * <h4>답변 등록이 곧 종결이다</h4>
 * 상태 전이가 하나뿐이라 「답변 후 재답변」이라는 경로가 없다(409). 유저 화면이 열람 전용이라
 * 정정 답변을 보낼 자리도 없으므로, 막는 것이 아니라 <b>애초에 없는 동작</b>이다.
 *
 * <h4>신고와 달리 신원을 가리지 않는다</h4>
 * 신고는 익명성과 보복 방지 때문에 신고자 신원을 어떤 응답에도 넣지 않지만, CS 는 <b>그 사람에게
 * 답을 하는 일</b>이다. 계정 상태와 활성 제재를 함께 보지 않으면 「신고·제재」 분류의 문의에
 * 답할 수 없다 — 1차 확인 항목이 제재 이력·근거이기 때문이다(§ 5.1).
 */
@Service
@RequiredArgsConstructor
public class AdminInquiryService {

    private static final int PAGE = 30;
    private static final int PREVIEW_LENGTH = 80;

    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;
    private final SanctionRepository sanctionRepository;
    private final AdminAuditService auditService;
    private final AdminImageLinks imageLinks;
    private final NotificationPublisher notificationPublisher;

    // ===== 큐 =====

    @Transactional(readOnly = true)
    public AdminInquiryDtos.QueueResponse queue(UUID operatorId, String status, String category,
                                                String keyword, String cursor, Integer size) {
        auditService.allowed(operatorId, AdminAction.INQUIRY_QUEUE_VIEW, null, null, null);

        InquiryStatus statusFilter = parseOrNull(InquiryStatus.class, status);
        InquiryCategory categoryFilter = parseOrNull(InquiryCategory.class, category);
        String q = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        int limit = (size == null || size <= 0 || size > 100) ? PAGE : size;

        // 다음 커서를 만들려면 마지막 행 뒤에 뭔가 더 있는지 알아야 한다 — 한 건 더 읽는다.
        List<Inquiry> rows = inquiryRepository.findQueue(statusFilter, categoryFilter, q,
                AdminCursors.toInstant(cursor), Limit.of(limit + 1));

        boolean hasMore = rows.size() > limit;
        List<Inquiry> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? AdminCursors.ofInstant(page.getLast().getCreatedAt()) : null;

        Map<UUID, User> users = usersOf(page);
        Instant now = Instant.now();

        return new AdminInquiryDtos.QueueResponse(
                page.stream().map(i -> new AdminInquiryDtos.Item(
                        i.getId().toString(),
                        i.getUserId().toString(),
                        nicknameOf(users, i.getUserId()),
                        i.getCategory().name(),
                        i.getStatus().name(),
                        preview(i.getBody()),
                        i.getCreatedAt().toString(),
                        i.getAnsweredAt() == null ? null : i.getAnsweredAt().toString(),
                        Duration.between(i.getCreatedAt(),
                                i.getAnsweredAt() == null ? now : i.getAnsweredAt()).toHours()))
                        .toList(),
                nextCursor,
                inquiryRepository.countQueue(statusFilter, categoryFilter, q));
    }

    // ===== 상세 =====

    /**
     * 상세. <b>개인정보 열람</b>이라 목록 조회와 다른 action 으로 남긴다 — 본문에는 계정·기기
     * 정보와 유저가 직접 쓴 사연이 담긴다.
     */
    @Transactional(readOnly = true)
    public AdminInquiryDtos.Detail detail(UUID operatorId, UUID inquiryId) {
        auditService.allowed(operatorId, AdminAction.INQUIRY_VIEW,
                AdminAuditLog.TargetType.INQUIRY, inquiryId, null);

        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));
        User user = userRepository.findById(inquiry.getUserId()).orElse(null);

        AdminImageLinks.Links links = imageLinks.presign(inquiry.imageUrlsOrEmpty());
        Instant now = Instant.now();

        return new AdminInquiryDtos.Detail(
                inquiry.getId().toString(),
                inquiry.getUserId().toString(),
                user == null ? null : user.visibleNicknameTo(null),
                user == null ? null : user.getStatus().name(),
                inquiry.getCategory().name(),
                inquiry.getOriginCategory().name(),
                inquiry.getStatus().name(),
                inquiry.getBody(),
                links.urls(),
                links.expiresAt(),
                inquiry.getAppVersion(),
                inquiry.getOsVersion(),
                inquiry.getDeviceModel(),
                inquiry.getErrorLogId(),
                inquiry.getCreatedAt().toString(),
                inquiry.getAnswerText(),
                inquiry.getAnsweredAt() == null ? null : inquiry.getAnsweredAt().toString(),
                inquiry.getAnsweredBy() == null ? null : inquiry.getAnsweredBy().toString(),
                sanctionRepository.findActive(inquiry.getUserId(), now).stream()
                        .map(s -> AdminSanctionItems.of(s, null, now)).toList());
    }

    // ===== 답변 =====

    /**
     * 답변 등록 = 종결. 통지는 <b>같은 트랜잭션</b>에서 적재한다 — 답변이 롤백되면 통지도 함께
     * 사라져야 하고, 통지만 나가면 유저가 빈 문의를 열게 된다.
     */
    @Transactional
    public AdminInquiryDtos.Detail answer(UUID operatorId, UUID inquiryId,
                                          AdminInquiryDtos.AnswerRequest request) {
        if (request == null || request.answerText() == null || request.answerText().isBlank())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));
        if (inquiry.isAnswered()) throw new BusinessException(ErrorCode.ALREADY_ANSWERED);

        auditService.allowed(operatorId, AdminAction.INQUIRY_ANSWER,
                AdminAuditLog.TargetType.INQUIRY, inquiryId, request.answerText());

        inquiry.answer(request.answerText().trim(), operatorId, Instant.now());

        // 본문을 알림에 싣지 않는다 — 잠금화면에 CS 답변 전문이 뜨면 곤란한 사연이 있다.
        notificationPublisher.publish(NotificationEvent.of(inquiry.getUserId(),
                NotificationType.CS_ANSWERED,
                "문의에 답변이 등록됐어요",
                "보내주신 문의에 답변이 등록됐어요. 눌러서 확인해주세요.",
                Map.of(NotificationParams.INQUIRY_ID, inquiryId.toString())));

        return detailOf(inquiry);
    }

    // ===== 분류 변경 =====

    @Transactional
    public AdminInquiryDtos.Detail reclassify(UUID operatorId, UUID inquiryId,
                                              AdminInquiryDtos.CategoryRequest request) {
        InquiryCategory category = (request == null) ? null : parseOrNull(InquiryCategory.class, request.category());
        if (category == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));

        // 유저에게 노출하지 않는 조작이라 감사 로그가 유일한 흔적이다.
        auditService.allowed(operatorId, AdminAction.INQUIRY_RECLASSIFY,
                AdminAuditLog.TargetType.INQUIRY, inquiryId,
                inquiry.getCategory().name() + "->" + category.name());

        inquiry.reclassify(category);
        return detailOf(inquiry);
    }

    // ===== 내부 =====

    /** 답변·분류 변경 응답 — 상세를 그대로 돌려주되 감사 로그를 다시 남기지는 않는다. */
    private AdminInquiryDtos.Detail detailOf(Inquiry inquiry) {
        User user = userRepository.findById(inquiry.getUserId()).orElse(null);
        AdminImageLinks.Links links = imageLinks.presign(inquiry.imageUrlsOrEmpty());
        Instant now = Instant.now();

        return new AdminInquiryDtos.Detail(
                inquiry.getId().toString(),
                inquiry.getUserId().toString(),
                user == null ? null : user.visibleNicknameTo(null),
                user == null ? null : user.getStatus().name(),
                inquiry.getCategory().name(),
                inquiry.getOriginCategory().name(),
                inquiry.getStatus().name(),
                inquiry.getBody(),
                links.urls(),
                links.expiresAt(),
                inquiry.getAppVersion(),
                inquiry.getOsVersion(),
                inquiry.getDeviceModel(),
                inquiry.getErrorLogId(),
                inquiry.getCreatedAt().toString(),
                inquiry.getAnswerText(),
                inquiry.getAnsweredAt() == null ? null : inquiry.getAnsweredAt().toString(),
                inquiry.getAnsweredBy() == null ? null : inquiry.getAnsweredBy().toString(),
                sanctionRepository.findActive(inquiry.getUserId(), now).stream()
                        .map(s -> AdminSanctionItems.of(s, null, now)).toList());
    }

    private Map<UUID, User> usersOf(List<Inquiry> rows) {
        List<UUID> ids = rows.stream().map(Inquiry::getUserId).distinct().toList();
        return userRepository.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
    }

    private String nicknameOf(Map<UUID, User> users, UUID userId) {
        User user = users.get(userId);
        return user == null ? null : user.visibleNicknameTo(null);
    }

    private String preview(String body) {
        return body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH) + "…";
    }

    private <T extends Enum<T>> T parseOrNull(Class<T> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
