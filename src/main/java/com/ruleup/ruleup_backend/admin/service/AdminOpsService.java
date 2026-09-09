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
import com.ruleup.ruleup_backend.common.error.Confirmation;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.announcement.Announcement;
import com.ruleup.ruleup_backend.notification.announcement.AnnouncementRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 유저 통합 뷰 · 이상탐지 · 직권 폐쇄 · 장애 구제 · 운영 공지 · 제재 이력.
 */
@Service
@RequiredArgsConstructor
public class AdminOpsService {

    private static final int PAGE = 50;
    private static final int NOTICE_PAGE = 100;

    private final UserRepository userRepository;
    private final SanctionRepository sanctionRepository;
    private final AnomalySignalRepository anomalyRepository;
    private final OutageReliefRepository reliefRepository;
    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final AdminAuditService auditService;
    private final AdminReviewService reviewService;
    private final ConfirmationTokens confirmationTokens;
    private final AnnouncementRepository announcementRepository;
    private final JdbcTemplate jdbc;

    // ===== 유저 통합 뷰 =====

    /** 판단 근거만 모은다 — 자동·직권을 <b>별개 배열</b>로 내리며 합산하지 않는다. */
    @Transactional(readOnly = true)
    public AdminDtos.UserView userView(UUID operatorId, UUID userId) {
        auditService.allowed(operatorId, AdminAction.USER_VIEW,
                AdminAuditLog.TargetType.USER, userId, null);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String nickname = user.visibleNicknameTo(null);
        Instant now = Instant.now();

        return new AdminDtos.UserView(
                userId.toString(),
                nickname,
                user.getStatus().name(),
                null,
                sanctionItems(userId, SanctionTrack.DISCRETIONARY, nickname, now),
                sanctionItems(userId, SanctionTrack.AUTO, nickname, now),
                anomalyRepository.findByTargetUserIdOrderByDetectedAtDesc(userId).stream()
                        .map(s -> toAnomalyItem(s, nickname)).toList(),
                count("SELECT COUNT(*) FROM reports WHERE target_type = 'USER' AND target_id = ?",
                        bytes(userId)),
                count("SELECT COUNT(*) FROM reports WHERE reporter_id = ?", bytes(userId)),
                count("SELECT COUNT(*) FROM challenge_members WHERE user_id = ? AND status = 'ACTIVE'",
                        bytes(userId)),
                count("SELECT COUNT(*) FROM inquiries WHERE user_id = ? AND status = 'RECEIVED'",
                        bytes(userId)));
    }

    private List<AdminDtos.SanctionItem> sanctionItems(UUID userId, SanctionTrack track,
                                                       String nickname, Instant now) {
        return sanctionRepository.findByUserIdAndTrackOrderByStartsAtDesc(userId, track).stream()
                .map(s -> AdminSanctionItems.of(s, nickname, now))
                .toList();
    }

    // ===== 제재 이력 =====

    /**
     * 전체 제재 이력. 유저 하위 경로만으로는 <b>최근 무엇이 집행됐는지</b>를 볼 수 없다 —
     * 가드레일 감사가 "어제 걸린 잠금 중 근거 없는 건"을 찾는 조회가 여기다.
     */
    @Transactional(readOnly = true)
    public AdminDtos.SanctionListResponse sanctions(UUID operatorId, String track, String type,
                                                    Boolean onlyActive, String cursor, Integer size) {
        auditService.allowed(operatorId, AdminAction.SANCTION_LIST_VIEW, null, null, null);

        int limit = (size == null || size <= 0 || size > 100) ? PAGE : size;
        Instant now = Instant.now();
        Instant before = AdminCursors.toInstant(cursor);

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (track != null && !track.isBlank()) {
            where.append(" AND s.track = ?");
            args.add(track);
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND s.type = ?");
            args.add(type);
        }
        if (Boolean.TRUE.equals(onlyActive)) {
            // 활성 판정은 세 경우다 — 동결·영구를 빠뜨리면 목록에서 조용히 사라진다.
            where.append(" AND s.revoked_at IS NULL AND (s.frozen_remaining_sec IS NOT NULL"
                    + " OR s.ends_at IS NULL OR s.ends_at > ?)");
            args.add(java.sql.Timestamp.from(now));
        }
        if (before != null) {
            where.append(" AND s.starts_at < ?");
            args.add(java.sql.Timestamp.from(before));
        }
        args.add(limit + 1);

        List<UUID> ids = jdbc.query("SELECT s.id FROM sanctions s" + where
                        + " ORDER BY s.starts_at DESC LIMIT ?",
                (rs, i) -> uuid(rs.getBytes(1)), args.toArray());

        boolean hasMore = ids.size() > limit;
        List<UUID> page = hasMore ? ids.subList(0, limit) : ids;

        List<Sanction> sanctions = sanctionRepository.findAllById(page).stream()
                .sorted((a, b) -> b.getStartsAt().compareTo(a.getStartsAt()))
                .toList();
        Map<UUID, User> users = userRepository.findAllById(
                        sanctions.stream().map(Sanction::getUserId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

        String nextCursor = sanctions.isEmpty() || !hasMore ? null
                : AdminCursors.ofInstant(sanctions.getLast().getStartsAt());

        return new AdminDtos.SanctionListResponse(
                sanctions.stream()
                        .map(s -> AdminSanctionItems.of(s, nicknameOf(users, s.getUserId()), now))
                        .toList(),
                nextCursor);
    }

    // ===== 이상탐지 =====

    /**
     * 신호 목록. <b>탐지만으로는 제재하지 않는다</b> — 여기에 일괄 처리·자동 제재 경로를 두지 않는다.
     *
     * <p>점수만 내리지 않고 {@code summary} 를 서버가 만들어 함께 내린다. 「score 82」로는
     * 사람이 판단할 수 없고, 판단할 수 없는 화면은 결국 전부 넘기거나 전부 제재하게 만든다.
     */
    @Transactional(readOnly = true)
    public AdminDtos.AnomalyResponse anomalies(UUID operatorId, String signalType,
                                               Boolean onlyUnreviewed, Integer minScore,
                                               String cursor, Integer size) {
        auditService.allowed(operatorId, AdminAction.ANOMALY_VIEW, null, null, null);

        int limit = (size == null || size <= 0 || size > 100) ? PAGE : size;
        AdminCursors.ScoreCursor after = AdminCursors.toScore(cursor);

        List<AnomalySignal> rows = anomalyRepository.findQueue(
                parseOrNull(AnomalySignal.SignalType.class, signalType),
                !Boolean.FALSE.equals(onlyUnreviewed),
                minScore,
                after == null ? null : after.score(),
                after == null ? null : after.detectedAt(),
                Limit.of(limit + 1));

        boolean hasMore = rows.size() > limit;
        List<AnomalySignal> page = hasMore ? rows.subList(0, limit) : rows;

        Map<UUID, User> users = userRepository.findAllById(
                        page.stream().map(AnomalySignal::getTargetUserId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

        String nextCursor = (page.isEmpty() || !hasMore) ? null
                : AdminCursors.ofScore(page.getLast().getScore(), page.getLast().getDetectedAt());

        return new AdminDtos.AnomalyResponse(
                page.stream().map(s -> toAnomalyItem(s, nicknameOf(users, s.getTargetUserId()))).toList(),
                nextCursor);
    }

    /**
     * 검토 종료 — <b>제재로 승격하는 경로가 아니다</b>. "봤고 이러이러해서 넘겼다"는 기록이며,
     * 제재가 필요하면 운영자가 제재 엔드포인트를 따로 호출한다.
     */
    @Transactional
    public AdminDtos.AnomalyItem reviewAnomaly(UUID operatorId, UUID signalId,
                                               AdminDtos.AnomalyReviewRequest request) {
        AnomalySignal signal = anomalyRepository.findById(signalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANOMALY_NOT_FOUND));
        if (signal.isReviewed()) throw new BusinessException(ErrorCode.REVIEW_ALREADY_RESOLVED);

        String note = (request == null || request.note() == null || request.note().isBlank())
                ? null : request.note().trim();
        auditService.allowed(operatorId, AdminAction.ANOMALY_REVIEW,
                AdminAuditLog.TargetType.ANOMALY, signalId, note);

        signal.review(operatorId, note, Instant.now());
        return toAnomalyItem(signal, userRepository.findById(signal.getTargetUserId())
                .map(u -> u.visibleNicknameTo(null)).orElse(null));
    }

    /**
     * 판단 근거 문장. 신호 종류마다 봐야 할 숫자가 다르므로 <b>서버가 그 숫자를 세어 문장으로</b>
     * 만든다 — 콘솔이 조립하면 종류가 늘 때마다 클라이언트 배포가 따라온다.
     */
    private String summaryOf(AnomalySignal signal) {
        UUID userId = signal.getTargetUserId();
        return switch (signal.getSignalType()) {
            case REPORT_ABUSE -> {
                long filed = count("SELECT COUNT(*) FROM reports WHERE reporter_id = ?", bytes(userId));
                long dismissed = count("SELECT COUNT(*) FROM reports WHERE reporter_id = ?"
                        + " AND status = 'RESOLVED_NO_ACTION'", bytes(userId));
                yield "접수한 신고 " + filed + "건 중 " + dismissed + "건이 문제없음으로 종결됐어요.";
            }
            case APPEAL_ABUSE -> {
                long sanctions = sanctionRepository.findByUserIdOrderByStartsAtDesc(userId).size();
                yield "제재 이력 " + sanctions + "건에 대해 재검토 요청이 반복되고 있어요.";
            }
            case MODERATION_EVASION -> {
                long rejected = count("SELECT COUNT(*) FROM challenges WHERE owner_id = ?"
                        + " AND (moderation_title = 'REJECTED' OR moderation_image = 'REJECTED')",
                        bytes(userId));
                yield "심사에서 거부된 콘텐츠가 " + rejected + "건이에요.";
            }
        };
    }

    private AdminDtos.AnomalyItem toAnomalyItem(AnomalySignal s, String nickname) {
        return new AdminDtos.AnomalyItem(
                s.getId().toString(), s.getSignalType().name(), s.getTargetUserId().toString(),
                nickname, s.getScore(), summaryOf(s), s.getDetectedAt().toString(),
                s.getReviewedAt() == null ? null : s.getReviewedAt().toString(),
                s.getReviewerId() == null ? null : s.getReviewerId().toString(),
                s.getReviewNote());
    }

    // ===== 챌린지 =====

    @Transactional(readOnly = true)
    public AdminDtos.ChallengeDetail challenge(UUID operatorId, UUID challengeId) {
        auditService.allowed(operatorId, AdminAction.CHALLENGE_VIEW,
                AdminAuditLog.TargetType.CHALLENGE, challengeId, null);
        return challengeDetail(challengeId);
    }

    /**
     * 챌린지 직권 폐쇄. <b>영향 인원 수를 먼저 응답</b>해 오조작을 막는다 — 428 의
     * {@code sideEffects} 가 그 역할을 겸하므로 별도 미리보기 엔드포인트를 두지 않는다.
     * 확인과 집행이 같은 토큰으로 묶이는 편이 안전하다.
     *
     * <p>집행하면 일반 참여자는 <b>감점 없이</b> 자동 탈퇴하고 랭킹에서만 빠진다. 방장은
     * 사안에 따라 별도 제재 대상이며 폐쇄가 자동으로 제재를 걸지 않는다.
     */
    @Transactional
    public AdminDtos.ChallengeDetail closeChallenge(UUID operatorId, UUID challengeId,
                                                    AdminDtos.CloseRequest request) {
        if (request == null || request.reasonText() == null || request.reasonText().isBlank())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Challenge challenge = challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        int affected = memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE).size();

        String payload = request.reasonText();
        if (!confirmationTokens.verify(request.confirmationToken(), operatorId,
                AdminAction.CHALLENGE_CLOSE.name(), challengeId.toString(), payload)) {
            ConfirmationTokens.Issued issued = confirmationTokens.issue(operatorId,
                    AdminAction.CHALLENGE_CLOSE.name(), challengeId.toString(), payload);
            throw BusinessException.confirmationRequired(Confirmation.of(
                    issued.token(), issued.expiresAt(), challenge.publicTitle(), "챌린지 직권 폐쇄",
                    null, List.of(
                            new Confirmation.SideEffect("참여자 자동 탈퇴", affected, true),
                            new Confirmation.SideEffect("근거 신고 종결",
                                    request.resolveReportIds() == null ? 0
                                            : request.resolveReportIds().size(), false))));
        }

        auditService.allowed(operatorId, AdminAction.CHALLENGE_CLOSE,
                AdminAuditLog.TargetType.CHALLENGE, challengeId, request.reasonText());

        // 폐쇄된 방의 데이터 처리는 정책에 명시가 없다(공통 오픈 이슈 #4) — 조회만 막고 기록은 남긴다.
        challenge.complete();

        // 근거 신고를 큐에 남겨 두지 않는다. 폐쇄해 놓고 신고가 미검토로 남으면 다음 운영자가
        // 같은 방을 다시 판단한다.
        reviewService.resolveAsSanctioned(operatorId, request.resolveReportIds(),
                "챌린지 직권 폐쇄: " + request.reasonText());

        return challengeDetail(challengeId);
    }

    private AdminDtos.ChallengeDetail challengeDetail(UUID challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        int active = memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE).size();
        User owner = challenge.getCreatorId() == null ? null
                : userRepository.findById(challenge.getCreatorId()).orElse(null);

        return new AdminDtos.ChallengeDetail(
                challengeId.toString(),
                // 운영자에게는 임시 제목이 아니라 **실제 제목**을 보여준다 — 판단 대상이 그 값이다.
                challenge.getTitle(),
                challenge.getStatus().name(),
                challenge.getCreatorId() == null ? null : challenge.getCreatorId().toString(),
                owner == null ? null : owner.visibleNicknameTo(null),
                active,
                challenge.getMaxParticipants() == null ? 0 : challenge.getMaxParticipants(),
                challenge.getStartDate() == null ? null : challenge.getStartDate().toString(),
                challenge.getEndDate() == null ? null : challenge.getEndDate().toString(),
                challenge.getCreatedAt() == null ? null : challenge.getCreatedAt().toString(),
                count("SELECT COUNT(*) FROM reports WHERE target_type = 'CHALLENGE' AND target_id = ?",
                        bytes(challengeId)),
                count("SELECT COUNT(*) FROM reports WHERE target_type = 'CHALLENGE' AND target_id = ?"
                        + " AND status = 'PENDING'", bytes(challengeId)),
                challenge.getImageUrl(),
                challenge.getModerationStatus().name());
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
            ConfirmationTokens.Issued issued = confirmationTokens.issue(operatorId,
                    AdminAction.OUTAGE_RELIEF.name(), "-", payload);
            throw BusinessException.confirmationRequired(Confirmation.of(
                    issued.token(), issued.expiresAt(),
                    request.periodStart() + " ~ " + request.periodEnd(),
                    "장애 구제 · " + scope.name(), null,
                    List.of(new Confirmation.SideEffect("판정 제외", affectedCount, true))));
        }

        auditService.allowed(operatorId, AdminAction.OUTAGE_RELIEF, null, null, payload);
        Instant now = Instant.now();
        OutageRelief relief = reliefRepository.save(
                OutageRelief.of(start, end, scope, operatorId, affectedCount, now));

        return new AdminDtos.ReliefResponse(relief.getId().toString(), scope.name(),
                affectedCount, now.toString());
    }

    // ===== 운영 공지 =====

    /**
     * 점검·장애·약관·종료 공지 — <b>공지 원본만 저장하고 즉시 응답한다</b>.
     *
     * <p>전체 공지 1건이 약 2만 행으로 팬아웃되므로 그 INSERT 를 요청 트랜잭션 안에 둘 수 없다.
     * 실제 적재는 {@link com.ruleup.ruleup_backend.notification.announcement.AnnouncementFanoutJob}
     * 이 청크 단위로 한다.
     *
     * <p>공지는 알림 센터의 <b>공지 탭에만</b> 쌓이고 푸시가 나가지 않는다({@code pushable=false}).
     * 그래서 응답에 푸시 통계 필드가 없다.
     */
    @Transactional
    public AdminDtos.NoticeResponse publishNotice(UUID operatorId, AdminDtos.NoticeRequest request) {
        if (request == null || isBlank(request.title()) || isBlank(request.body()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Announcement.Kind kind = isBlank(request.kind())
                ? Announcement.Kind.MAINTENANCE : parseKind(request.kind());
        Instant scheduledAt = isBlank(request.scheduledAt()) ? null : parseInstant(request.scheduledAt());

        // 팬아웃 잡과 같은 조건이어야 한다 — 예상 수신자와 실제 적재 수가 어긋나면
        // 「공지가 덜 나갔다」는 오해가 매번 생긴다.
        Integer audience = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE status <> 'WITHDRAWN' AND deleted_at IS NULL"
                        + " AND role = 'MEMBER'",
                Integer.class);
        int recipients = audience == null ? 0 : audience;

        String payload = kind + "|" + request.title() + "|" + request.body();
        if (!confirmationTokens.verify(request.confirmationToken(), operatorId,
                AdminAction.OPS_NOTICE.name(), "-", payload)) {
            // 전체 팬아웃이라 되돌릴 수 없다 — 예약분만 취소할 수 있고 적재된 뒤에는 회수되지 않는다.
            ConfirmationTokens.Issued issued = confirmationTokens.issue(operatorId,
                    AdminAction.OPS_NOTICE.name(), "-", payload);
            throw BusinessException.confirmationRequired(Confirmation.of(
                    issued.token(), issued.expiresAt(), request.title(), "운영 공지 발행 · " + kind,
                    null, List.of(new Confirmation.SideEffect("알림함 적재", recipients, true))));
        }

        auditService.allowed(operatorId, AdminAction.OPS_NOTICE, null, null, payload);

        Instant now = Instant.now();
        Announcement announcement = announcementRepository.save(Announcement.of(
                kind, request.title(), request.body(), request.deepLink(), operatorId, scheduledAt, now));

        return noticeResponse(announcement, recipients);
    }

    @Transactional(readOnly = true)
    public AdminDtos.NoticeListResponse notices(UUID operatorId) {
        auditService.allowed(operatorId, AdminAction.OPS_NOTICE, null, null, null);
        return new AdminDtos.NoticeListResponse(
                announcementRepository.findAllByOrderByCreatedAtDesc(Limit.of(NOTICE_PAGE)).stream()
                        .map(a -> noticeResponse(a, a.getRecipientCount())).toList());
    }

    /** 예약 취소. <b>이미 적재된 공지는 회수되지 않는다</b> — 대기 중일 때만 의미가 있다. */
    @Transactional
    public AdminDtos.NoticeResponse cancelNotice(UUID operatorId, UUID announcementId) {
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
        if (announcement.getFannedOutAt() != null)
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_ALREADY_SENT);
        if (announcement.getCanceledAt() != null)
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_RESOLVED);

        auditService.allowed(operatorId, AdminAction.OPS_NOTICE_CANCEL,
                AdminAuditLog.TargetType.ANNOUNCEMENT, announcementId, null);
        announcement.cancel(Instant.now());
        return noticeResponse(announcement, announcement.getRecipientCount());
    }

    private AdminDtos.NoticeResponse noticeResponse(Announcement a, int recipients) {
        return new AdminDtos.NoticeResponse(
                a.getId().toString(),
                a.getKind().name(),
                a.getTitle(),
                recipients,
                a.getScheduledAt() == null ? null : a.getScheduledAt().toString(),
                a.getCreatedAt().toString(),
                a.getFannedOutAt() == null ? null : a.getFannedOutAt().toString(),
                a.getCanceledAt() == null ? null : a.getCanceledAt().toString());
    }

    // ===== 내부 =====

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String nicknameOf(Map<UUID, User> users, UUID userId) {
        User user = users.get(userId);
        return user == null ? null : user.visibleNicknameTo(null);
    }

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

    private Announcement.Kind parseKind(String raw) {
        try {
            return Announcement.Kind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private <T extends Enum<T>> T parseOrNull(Class<T> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw);
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
