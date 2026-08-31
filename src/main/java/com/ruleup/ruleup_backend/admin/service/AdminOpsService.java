package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.*;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.repository.AnomalySignalRepository;
import com.ruleup.ruleup_backend.admin.repository.OutageReliefRepository;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.sanction.SanctionRepository;
import com.ruleup.ruleup_backend.sanction.domain.Sanction;
import com.ruleup.ruleup_backend.sanction.domain.SanctionTrack;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 유저 통합 뷰 · 이상탐지 · 직권 폐쇄 · 장애 구제 · 운영 공지.
 */
@Service
@RequiredArgsConstructor
public class AdminOpsService {

    private static final int ANOMALY_PAGE = 100;

    private final UserRepository userRepository;
    private final SanctionRepository sanctionRepository;
    private final AnomalySignalRepository anomalyRepository;
    private final OutageReliefRepository reliefRepository;
    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final AdminAuditService auditService;
    private final ConfirmationTokens confirmationTokens;
    private final NotificationPublisher notificationPublisher;
    private final JdbcTemplate jdbc;

    // ===== 유저 통합 뷰 =====

    /** 판단 근거만 모은다 — 자동·직권을 <b>별개 배열</b>로 내리며 합산하지 않는다. */
    @Transactional(readOnly = true)
    public AdminDtos.UserView userView(UUID operatorId, UUID userId) {
        auditService.allowed(operatorId, AdminAction.USER_VIEW,
                AdminAuditLog.TargetType.USER, userId, null);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Long reportCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reports WHERE target_user_id = ?", Long.class, bytes(userId));

        return new AdminDtos.UserView(
                userId.toString(),
                user.visibleNicknameTo(null),
                user.getStatus().name(),
                sanctionItems(userId, SanctionTrack.DISCRETIONARY),
                sanctionItems(userId, SanctionTrack.AUTO),
                anomalyRepository.findByTargetUserIdOrderByDetectedAtDesc(userId).stream()
                        .map(this::toAnomalyItem).toList(),
                reportCount == null ? 0 : reportCount);
    }

    private List<AdminDtos.SanctionItem> sanctionItems(UUID userId, SanctionTrack track) {
        return sanctionRepository.findByUserIdAndTrackOrderByStartsAtDesc(userId, track).stream()
                .map(s -> new AdminDtos.SanctionItem(
                        s.getId().toString(), s.getType().name(), s.getReasonCode().name(),
                        s.getSource().name(), s.getStartsAt().toString(),
                        s.getEndsAt() == null ? null : s.getEndsAt().toString(),
                        s.getRevokedAt() == null ? null : s.getRevokedAt().toString()))
                .toList();
    }

    // ===== 이상탐지 =====

    /** 미검토 신호를 강도순으로. <b>탐지만으로는 제재하지 않는다</b> — 여기서 승격 경로를 두지 않는다. */
    @Transactional(readOnly = true)
    public AdminDtos.AnomalyResponse anomalies(UUID operatorId) {
        auditService.allowed(operatorId, AdminAction.ANOMALY_VIEW, null, null, null);
        return new AdminDtos.AnomalyResponse(
                anomalyRepository.findUnreviewed(Limit.of(ANOMALY_PAGE)).stream()
                        .map(this::toAnomalyItem).toList());
    }

    private AdminDtos.AnomalyItem toAnomalyItem(AnomalySignal s) {
        return new AdminDtos.AnomalyItem(
                s.getId().toString(), s.getSignalType().name(), s.getTargetUserId().toString(),
                s.getScore(), s.getDetectedAt().toString(),
                s.getReviewedAt() == null ? null : s.getReviewedAt().toString());
    }

    // ===== 직권 폐쇄 =====

    /**
     * 챌린지 직권 폐쇄. <b>영향 인원 수를 먼저 응답</b>해 오조작을 막는다.
     * 일반 참여자는 <b>감점 없이</b> 자동 탈퇴하며 방장은 별도 제재 대상이다.
     */
    @Transactional
    public AdminDtos.CloseResponse closeChallenge(UUID operatorId, UUID challengeId,
                                                  AdminDtos.CloseRequest request) {
        if (request == null || request.reasonText() == null || request.reasonText().isBlank())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Challenge challenge = challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        int affected = memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE).size();

        if (!confirmationTokens.verify(request.confirmationToken(), operatorId,
                AdminAction.CHALLENGE_CLOSE.name(), challengeId.toString(), request.reasonText())) {
            throw BusinessException.confirmationRequired(
                    confirmationTokens.issue(operatorId, AdminAction.CHALLENGE_CLOSE.name(),
                            challengeId.toString(), request.reasonText()),
                    new AdminDtos.ClosePreview(challengeId.toString(),
                            challenge.publicTitle(), affected));
        }

        auditService.allowed(operatorId, AdminAction.CHALLENGE_CLOSE,
                AdminAuditLog.TargetType.CHALLENGE, challengeId, request.reasonText());

        // 폐쇄된 방의 데이터 처리는 정책에 명시가 없다(공통 오픈 이슈 #4) — 조회만 막고 기록은 남긴다.
        challenge.complete();
        return new AdminDtos.CloseResponse(challengeId.toString(),
                challenge.getStatus().name(), affected);
    }

    // ===== 장애 구제 =====

    /** 기간과 범위를 받아 해당 판정을 <b>분모에서 제외</b>한다. 성공 처리가 아니다. */
    @Transactional
    public AdminDtos.ReliefResponse applyRelief(UUID operatorId, AdminDtos.ReliefRequest request) {
        if (request == null || request.periodStart() == null || request.periodEnd() == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Instant start = parseInstant(request.periodStart());
        Instant end = parseInstant(request.periodEnd());
        if (!end.isAfter(start)) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        OutageRelief.Scope scope = (request.scope() == null || request.scope().isBlank())
                ? OutageRelief.Scope.ALL : parseScope(request.scope());

        Long affected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM VerificationDaily WHERE verifiedAt BETWEEN ? AND ?",
                Long.class, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
        int affectedCount = (affected == null) ? 0 : affected.intValue();

        String payload = request.periodStart() + "|" + request.periodEnd() + "|" + scope;
        if (!confirmationTokens.verify(request.confirmationToken(), operatorId,
                AdminAction.OUTAGE_RELIEF.name(), "-", payload)) {
            throw BusinessException.confirmationRequired(
                    confirmationTokens.issue(operatorId, AdminAction.OUTAGE_RELIEF.name(), "-", payload),
                    new AdminDtos.ReliefResponse(null, scope.name(), affectedCount, null));
        }

        auditService.allowed(operatorId, AdminAction.OUTAGE_RELIEF, null, null, payload);
        Instant now = Instant.now();
        OutageRelief relief = reliefRepository.save(
                OutageRelief.of(start, end, scope, operatorId, affectedCount, now));

        return new AdminDtos.ReliefResponse(relief.getId().toString(), scope.name(),
                affectedCount, now.toString());
    }

    // ===== 운영 공지 =====

    /** 점검·장애·약관·종료 공지. <b>필수(A) 알림</b>으로 나가므로 끌 수 없고 야간에도 즉시 발송된다. */
    @Transactional
    public AdminDtos.NoticeResponse publishNotice(UUID operatorId, AdminDtos.NoticeRequest request) {
        if (request == null || isBlank(request.title()) || isBlank(request.body()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        String payload = request.title() + "|" + request.body();
        if (!confirmationTokens.verify(request.confirmationToken(), operatorId,
                AdminAction.OPS_NOTICE.name(), "-", payload)) {
            throw BusinessException.confirmationRequired(
                    confirmationTokens.issue(operatorId, AdminAction.OPS_NOTICE.name(), "-", payload),
                    new AdminDtos.NoticeResponse(0, null));
        }

        auditService.allowed(operatorId, AdminAction.OPS_NOTICE, null, null, payload);

        List<UUID> recipients = jdbc.query(
                "SELECT id FROM users WHERE status <> 'WITHDRAWN' AND deleted_at IS NULL",
                (rs, row) -> uuid(rs.getBytes(1)));
        recipients.forEach(userId -> notificationPublisher.publish(NotificationEvent.of(
                userId, NotificationType.TERMS_UPDATED, request.title(), request.body())));

        return new AdminDtos.NoticeResponse(recipients.size(), Instant.now().toString());
    }

    // ===== 내부 =====

    private Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private OutageRelief.Scope parseScope(String raw) {
        try {
            return OutageRelief.Scope.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    private static UUID uuid(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
