package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.Confirmation;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.sanction.SanctionRepository;
import com.ruleup.ruleup_backend.sanction.SanctionService;
import com.ruleup.ruleup_backend.sanction.domain.*;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 직권 제재 집행 — 백오피스 백엔드 4-2.
 *
 * <h4>순서가 계약이다</h4>
 * <pre>
 *   ① 2단계 확인 → ② 감사 로그 → ③ sanctions INSERT → ④ users.status 전이
 *   → ⑤ BAN 이면 ban_list → <b>커밋</b> → ⑥ 전 챌린지 자동 탈퇴 · 필수(A) 고지
 * </pre>
 * ⑥ 을 커밋 뒤로 미루는 이유는 두 방향이다 — <b>알림 실패가 제재를 롤백시키면 안 되고,
 * 제재가 롤백됐는데 고지만 나가면 더 안 된다.</b>
 *
 * <h4>미루되 잃지 않는다 — 아웃박스</h4>
 * ⑥ 을 {@code afterCommit} 콜백에 맡기면 <b>DB 커밋 직후 서버가 죽는 순간 필수(A) 고지와 자동
 * 탈퇴가 통째로 사라진다</b>. 콜백은 JVM 메모리에만 있어 재시작 후 주울 근거가 남지 않는다.
 * 그래서 발행 의사를 ⑤ 와 <b>같은 커밋</b>에 {@code outbox_messages} 로 적고, 디스패처가 커밋
 * 이후에 집어 간다. 제재가 롤백되면 발행 의사도 함께 사라지므로 반대 방향도 그대로 지켜진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSanctionService {

    /** 잠금 기본 기간. 기능 정지는 사안별이지만 페이지1은 같은 값을 쓴다. */
    private static final Duration LOCK_DURATION = Duration.ofDays(30);

    private final SanctionService sanctionService;
    private final AdminReviewService reviewService;
    private final SanctionRepository sanctionRepository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ConfirmationTokens confirmationTokens;
    private final NotificationPublisher notificationPublisher;
    private final OutboxService outboxService;
    private final OutboxDispatcher outboxDispatcher;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional
    public AdminDtos.SanctionResponse apply(UUID operatorId, UUID targetUserId,
                                            AdminDtos.SanctionRequest request) {
        if (request == null || isBlank(request.type()) || isBlank(request.reasonCode())
                || isBlank(request.reasonText()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);   // 사유는 필수다

        // 수단은 3종 + permanent 플래그다(운영자 제재 정책 § 5.4 — 영구 정지를 별도 종류로 두지
        // 않는다). LOCK + permanent 는 저장 시 BAN 으로 접힌다 — 게이트가 읽는 값이 그것이다.
        SanctionType type = permanentOf(request, parse(SanctionType.class, request.type()));
        SanctionReason reasonCode = parse(SanctionReason.class, request.reasonCode());
        SanctionSource source = isBlank(request.source())
                ? SanctionSource.DIRECT : parse(SanctionSource.class, request.source());

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Instant endsAt = type.isPermanent() ? null : Instant.now().plus(LOCK_DURATION);

        // ① 2단계 확인 — 대상·내용에 묶인 토큰이라 다른 요청의 토큰은 통하지 않는다.
        //    재제시 문구는 **서버가 만든다**. 클라이언트가 조립하면 확인한 문장과 집행할 내용이
        //    어긋날 수 있고, 어긋난 채로 받은 확인은 확인이 아니다.
        String payload = payloadOf(request);
        if (!confirmationTokens.verify(request.confirmationToken(), operatorId,
                AdminAction.SANCTION_APPLY.name(), targetUserId.toString(), payload)) {
            ConfirmationTokens.Issued issued = confirmationTokens.issue(operatorId,
                    AdminAction.SANCTION_APPLY.name(), targetUserId.toString(), payload);
            throw BusinessException.confirmationRequired(Confirmation.of(
                    issued.token(), issued.expiresAt(),
                    target.visibleNicknameTo(null), actionLabel(type),
                    endsAt,
                    List.of(new Confirmation.SideEffect("전 챌린지 자동 탈퇴",
                                    activeChallengeCount(targetUserId), true),
                            new Confirmation.SideEffect("근거 신고 종결",
                                    request.resolveReportIds() == null ? 0
                                            : request.resolveReportIds().size(), false))));
        }

        // 같은 수준의 제재가 이미 진행 중이면 겹쳐 걸지 않는다.
        if (sanctionService.activeSanctions(targetUserId).stream()
                .anyMatch(s -> s.getType() == type))
            throw new BusinessException(ErrorCode.SANCTION_ALREADY_ACTIVE);

        // ② 감사 로그 — 집행보다 먼저 남긴다. 뒤에 남기면 집행은 됐는데 기록이 없는 창이 생긴다.
        auditService.allowed(operatorId, AdminAction.SANCTION_APPLY,
                AdminAuditLog.TargetType.USER, targetUserId, payload);

        // ③④⑤ 제재 + 상태 전이 + 밴리스트를 한 트랜잭션에서.
        Sanction sanction = sanctionService.impose(targetUserId, SanctionTrack.DISCRETIONARY, type,
                isBlank(request.featureCode()) ? null : parse(FeatureCode.class, request.featureCode()),
                reasonCode, request.reasonText(), source,
                parseUuid(request.sourceId()), operatorId, endsAt);

        // ⑥ 고지와 자동 탈퇴는 커밋 뒤에 — 다만 "무엇을 발행할지"는 지금 이 커밋에 적는다.
        //    notifiedAt 은 먼저 채워 "고지 없는 제재"로 보이지 않게 한다.
        sanction.markNotified(Instant.now());
        enqueueSideEffects(sanction, target, type);

        // ⑦ 근거 신고를 함께 종결한다. 제재해 놓고 신고가 미검토로 남으면 다음 운영자가
        //    같은 사안을 처음부터 다시 판단한다.
        int resolved = reviewService.resolveAsSanctioned(operatorId, request.resolveReportIds(),
                "계정 제재: " + request.reasonText());

        return new AdminDtos.SanctionResponse(
                sanction.getId().toString(), targetUserId.toString(), type.name(),
                type.isPermanent(),
                userRepository.findById(targetUserId).orElseThrow().getStatus().name(),
                sanction.getStartsAt().toString(),
                sanction.getEndsAt() == null ? null : sanction.getEndsAt().toString(),
                sanction.getNotifiedAt().toString(),
                resolved);
    }

    /**
     * 재검토 인용 해제 — 원본을 지우지 않고 {@code revokedAt} 만 채운다.
     *
     * <p><b>해제 사유도 감사 로그에 남긴다.</b> 제재를 건 근거만 남고 푼 근거가 없으면 재검토
     * 대응에서 절반의 이야기만 할 수 있다.
     */
    @Transactional
    public AdminDtos.SanctionItem revoke(UUID operatorId, UUID targetUserId, UUID sanctionId,
                                         AdminDtos.RevokeRequest request) {
        String reasonText = (request == null || isBlank(request.reasonText()))
                ? null : request.reasonText().trim();
        if (reasonText == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Sanction sanction = sanctionRepository.findById(sanctionId)
                .filter(s -> s.getUserId().equals(targetUserId))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (sanction.getRevokedAt() != null)
            throw new BusinessException(ErrorCode.APPEAL_ALREADY_USED);

        auditService.allowed(operatorId, AdminAction.SANCTION_REVOKE,
                AdminAuditLog.TargetType.USER, targetUserId, sanctionId + "|" + reasonText);
        sanctionService.revoke(sanctionId, Instant.now());
        sanction.markAppealUsed();

        // publish 는 이 트랜잭션에 합류해 발행 의사만 적는다 — 해제가 롤백되면 고지도 함께 사라진다.
        notificationPublisher.publish(NotificationEvent.of(
                sanction.getUserId(), NotificationType.ACCOUNT_SANCTION,
                "제재가 해제됐어요", "재검토 결과 제재가 해제됐어요. 다시 이용하실 수 있어요.",
                // 집행 고지와 해제 고지가 같은 제재 id 를 쓰므로 접두어로 가른다.
                Map.of(NotificationParams.EVENT_KEY, "revoke:" + sanctionId)));

        return com.ruleup.ruleup_backend.admin.service.AdminSanctionItems.of(sanction,
                userRepository.findById(targetUserId).map(u -> u.visibleNicknameTo(null)).orElse(null),
                Instant.now());
    }

    /**
     * 요청의 {@code permanent} 를 저장 값으로 접는다.
     *
     * <p>정책은 수단을 3종으로 두고 영구 여부를 플래그로 다루지만, 게이트가 읽는 값은
     * {@code sanctions.type} 하나다. 두 표기를 여기서 한 번만 맞춰 두면 게이트·마이페이지·
     * 콘솔이 각자 「LOCK 인데 endsAt 이 null 이면 영구」 같은 판정을 반복하지 않아도 된다.
     */
    private SanctionType permanentOf(AdminDtos.SanctionRequest request, SanctionType type) {
        if (!Boolean.TRUE.equals(request.permanent())) return type;
        if (type == SanctionType.FEATURE_SUSPENSION)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);   // 영구 기능 정지는 정책에 없다
        return SanctionType.BAN;
    }

    private String actionLabel(SanctionType type) {
        return switch (type) {
            case BAN -> "로그인 정지 · 영구";
            case LOCK -> "로그인 정지 · 1개월";
            case FEATURE_SUSPENSION -> "기능 정지 · 1개월";
        };
    }

    /** 428 의 「영향 인원」에 해당하는 값 — 이 제재로 나가게 될 방의 수다. */
    private int activeChallengeCount(UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM challenge_members WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, bytes(userId));
        return count == null ? 0 : count;
    }

    private static byte[] bytes(UUID id) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    /**
     * 부수 효과 예약 — 필수(A) 고지와 전 챌린지 자동 탈퇴. <b>제재와 같은 커밋에 적는다.</b>
     *
     * <p>자동 탈퇴는 <b>강퇴가 아니다</b> — 감점도 재참여 백오프도 붙지 않는다. 수신하는 챌린지
     * 도메인이 그렇게 처리하도록 사유를 실어 보낸다.
     *
     * <p>자동 탈퇴에는 dedup 키로 제재 ID 를 쓴다. 같은 제재로 두 번 나가게 만들 이유가 없고,
     * 재시도 중복은 아웃박스 단계에서 걸러 두는 편이 아래 도메인이 방어하는 것보다 확실하다.
     */
    private void enqueueSideEffects(Sanction sanction, User target, SanctionType type) {
        String endsAt = sanction.getEndsAt() == null ? null : sanction.getEndsAt().toString();

        notificationPublisher.publish(NotificationEvent.of(target.getId(),
                NotificationType.ACCOUNT_SANCTION,
                noticeTitle(type),
                // 본문에 민감정보를 담지 않는다 — 상세는 앱 안에서 본다.
                endsAt == null ? "자세한 내용은 마이페이지에서 확인해주세요."
                        : "해제 예정일까지 일부 기능을 이용할 수 없어요. 자세한 내용은 마이페이지에서 확인해주세요.",
                // 제재 id 가 이 집행을 유일하게 가리킨다. 해제 고지는 revoke: 접두어로 갈린다.
                Map.of(NotificationParams.EVENT_KEY, sanction.getId().toString())));

        outboxService.enqueue(SanctionLeaveListener.OUTBOX_TYPE,
                new SanctionLeaveListener.Payload(target.getId(), sanction.getReasonText()),
                "sanction-leave:" + sanction.getId());
        outboxDispatcher.requestFlush();
    }

    private String noticeTitle(SanctionType type) {
        return switch (type) {
            case BAN -> "계정이 영구 정지됐어요";
            case LOCK -> "계정이 잠겼어요";
            case FEATURE_SUSPENSION -> "일부 기능이 정지됐어요";
        };
    }

    /**
     * 확인 지문의 입력 — 대상·내용이 바뀌면 토큰이 무효가 된다.
     *
     * <p>{@code permanent} 를 반드시 넣는다. 빠뜨리면 <b>1개월 잠금으로 확인받고 영구 정지를
     * 집행</b>하는 경로가 열린다 — 2단계 확인이 막으려던 바로 그 사고다.
     */
    private String payloadOf(AdminDtos.SanctionRequest r) {
        return String.join("|", nullToEmpty(r.type()), String.valueOf(Boolean.TRUE.equals(r.permanent())),
                nullToEmpty(r.featureCode()), nullToEmpty(r.reasonCode()),
                nullToEmpty(r.reasonText()), nullToEmpty(r.source()));
    }

    private <T extends Enum<T>> T parse(Class<T> type, String raw) {
        try {
            return Enum.valueOf(type, raw);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private UUID parseUuid(String raw) {
        if (isBlank(raw)) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
