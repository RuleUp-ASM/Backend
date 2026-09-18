package com.ruleup.ruleup_backend.report;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 신고 접수와 개인 차단 — 방 내부 기능 5-3·5-5, 신고 접수 API 명세(2026-08-26 개편).
 *
 * <p>개편의 요지는 <b>접수 단계에서 판단하지 않는다</b>는 것이다. 임계값·누적 카운트·LLM 접수
 * 필터가 전부 사라졌고, 서버는 세 가지만 한다 — <b>차단 등재 · 컨텍스트 스냅샷 저장 · 전건 적재</b>.
 * <b>적재 자체는 어떤 제재도 발동시키지 않으며</b> 제재는 운영자가 검토해 계정 단위로만 내린다.
 *
 * <p>비동기 단계가 없다. 단일 동기 트랜잭션으로 끝난다.
 */
@Service
@RequiredArgsConstructor
public class BlockService {

    /** 유저 신고 사유. 챌린지는 CHEATING_SUSPECT 가 빠진다 — 방이 부정행위를 하지는 않는다. */
    private static final Set<String> USER_REASONS =
            Set.of("CHEATING_SUSPECT", "INAPPROPRIATE", "SPAM_AD", "ETC");
    private static final Set<String> CHALLENGE_REASONS =
            Set.of("INAPPROPRIATE", "SPAM_AD", "ETC");

    /** 신고 진입점. NOTICE·COMMENT 는 공지·댓글과 함께 Phase 2 라 아직 받지 않는다. */
    private static final Set<String> CONTEXT_TYPES =
            Set.of("PROFILE", "CHALLENGE_DETAIL", "ROOM");

    private static final String TARGET_USER = "USER";
    private static final String TARGET_CHALLENGE = "CHALLENGE";

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final ChallengeRepository challengeRepository;

    // ===== 접수 =====

    @Transactional
    public ReportDtos.CreateResponse report(UUID reporterId, ReportDtos.CreateRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);

        String targetType = request.targetType();
        if (!TARGET_USER.equals(targetType) && !TARGET_CHALLENGE.equals(targetType))
            throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);

        String contextType = (request.contextType() == null) ? "PROFILE" : request.contextType();
        if (!CONTEXT_TYPES.contains(contextType))
            throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);

        // 같은 신고자의 요청을 직렬화한다. 선조회만 하면 동시에 들어온 두 요청이 모두
        // '아직 신고하지 않음'을 읽고 서로 다른 신고·스냅샷을 남길 수 있다(QA REP-09).
        userRepository.findByIdForUpdate(reporterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        return TARGET_USER.equals(targetType)
                ? reportUser(reporterId, request, contextType)
                : reportChallenge(reporterId, request, contextType);
    }

    private ReportDtos.CreateResponse reportUser(UUID reporterId, ReportDtos.CreateRequest request,
                                                 String contextType) {
        if (!USER_REASONS.contains(request.reason()))
            throw new BusinessException(ErrorCode.INVALID_REPORT_REASON);

        UUID targetId = parseUuid(request.targetUserId(), ErrorCode.INVALID_REPORT_TARGET);
        if (targetId.equals(reporterId)) throw new BusinessException(ErrorCode.CANNOT_REPORT_SELF);
        requireNotReported(reporterId, TARGET_USER, targetId);
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 행위 신고(프로필 밖)는 발생 챌린지가 필수다 — 스냅샷에 방 정보를 담아야 판단이 된다.
        UUID challengeId = null;
        if (request.targetChallengeId() != null && !request.targetChallengeId().isBlank()) {
            challengeId = parseUuid(request.targetChallengeId(), ErrorCode.INVALID_REPORT_TARGET);
            if (!challengeRepository.existsById(challengeId))
                throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        } else if (!"PROFILE".equals(contextType)) {
            throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);
        }

        UUID reportId = insert(reporterId, TARGET_USER, targetId, request.reason(),
                userSnapshot(target, challengeId, contextType, request.contextId()));
        block(reporterId, TARGET_USER, targetId);

        return new ReportDtos.CreateResponse(reportId.toString(), true, "USER_CONTENT_MASKED");
    }

    private ReportDtos.CreateResponse reportChallenge(UUID reporterId, ReportDtos.CreateRequest request,
                                                      String contextType) {
        if (!CHALLENGE_REASONS.contains(request.reason()))
            throw new BusinessException(ErrorCode.INVALID_REPORT_REASON);

        UUID targetId = parseUuid(request.targetChallengeId(), ErrorCode.INVALID_REPORT_TARGET);
        requireNotReported(reporterId, TARGET_CHALLENGE, targetId);
        Challenge challenge = challengeRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        UUID reportId = insert(reporterId, TARGET_CHALLENGE, targetId, request.reason(),
                challengeSnapshot(challenge, contextType, request.contextId()));
        block(reporterId, TARGET_CHALLENGE, targetId);

        // 참여 중이면 방을 없애지 않는다 — 방 자체가 보기 싫으면 직접 나가야 한다.
        String effect = participating(reporterId, targetId) ? "CHALLENGE_MASKED" : "CHALLENGE_HIDDEN";
        return new ReportDtos.CreateResponse(reportId.toString(), true, effect);
    }

    /** 차단 해제도 신고 취소가 아니므로 원본 신고가 있으면 재접수하지 않는다. */
    private void requireNotReported(UUID reporterId, String targetType, UUID targetId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reports "
                        + "WHERE reporter_id=? AND target_type=? AND target_id=?",
                Integer.class, bytes(reporterId), targetType, bytes(targetId));
        if (count != null && count > 0) throw new BusinessException(ErrorCode.ALREADY_REPORTED);
    }

    /** 최초 신고만 적재한다. 신고자 락과 중복 검사, 차단·스냅샷 저장은 한 트랜잭션이다. */
    private UUID insert(UUID reporterId, String targetType, UUID targetId, String reason, String snapshot) {
        UUID reportId = UuidGenerator.generate();
        // 시각은 <b>UTC 로 명시해 쓴다</b>. 컬럼 기본값 CURRENT_TIMESTAMP 는 DB 세션 시간대를 따라가서,
        // 서버 시간대가 KST 면 벽시계 시각이 그대로 들어가고 JDBC 가 그걸 UTC 로 읽어 9시간 이른 값이 된다
        // (QA REP-07 의 blockedAt 이 그 증상이었다). 나머지 표는 이미 UTC_TIMESTAMP 를 쓴다.
        jdbc.update("INSERT INTO reports (id, reporter_id, target_type, target_id, reason, created_at) "
                + "VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))", bytes(reportId), bytes(reporterId), targetType,
                bytes(targetId), reason);
        // 원본이 수정·삭제돼도 이 값으로 검토한다. 절대 갱신하지 않는다.
        jdbc.update("INSERT INTO report_snapshots (report_id, payload) VALUES (?, ?)",
                bytes(reportId), snapshot);
        return reportId;
    }

    /** 신고와 같은 트랜잭션에서 차단을 적용한다. */
    private void block(UUID blockerId, String targetType, UUID targetId) {
        jdbc.update("INSERT IGNORE INTO user_blocks (blocker_id, target_type, target_id, blocked_at) "
                + "VALUES (?, ?, ?, UTC_TIMESTAMP(3))",
                bytes(blockerId), targetType, bytes(targetId));
    }

    // ===== 스냅샷 =====
    // 유저가 자유 텍스트를 적지 않으므로 서버가 판단 재료를 모은다. 원본이 바뀌어도 이 값이 기준이다.

    private String userSnapshot(User target, UUID challengeId, String contextType, String contextId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", TARGET_USER);
        payload.put("nickname", target.getNickname());
        payload.put("nicknameStatus", target.getNicknameStatus().name());
        payload.put("profileImageStatus", target.getProfileImageStatus().name());
        payload.put("profileImageKey", target.getProfileImageUrl());
        if (challengeId != null) {
            payload.put("challengeId", challengeId.toString());
            challengeRepository.findById(challengeId).ifPresent(c -> payload.put("challenge", challengeContent(c)));
            payload.put("membership", jdbc.queryForList("SELECT status,leave_reason,CAST(joined_at AS CHAR) AS joinedAt,CAST(left_at AS CHAR) AS leftAt " +
                    "FROM challenge_members WHERE challenge_id=? AND user_id=?", bytes(challengeId),bytes(target.getId())));
            if (contextId != null && "ROOM".equals(contextType)) {
                try {
                    UUID verificationId=UUID.fromString(contextId);
                    payload.put("verification",jdbc.queryForList("SELECT d.status,d.method,d.failureReason,CAST(d.targetDate AS CHAR) AS targetDate, " +
                            "CAST(d.shareableAt AS CHAR) AS shareableAt,CAST(r.evidence AS CHAR) AS evidence FROM VerificationDaily d " +
                            "LEFT JOIN VerificationMethodResult r ON r.verificationDailyId=d.id WHERE d.id=? AND d.challengeId=? AND d.userId=?",
                            bytes(verificationId),bytes(challengeId),bytes(target.getId())));
                } catch (IllegalArgumentException ignored) { /* A non-verification screen context has no judgement evidence. */ }
            }
        }
        payload.put("contextType", contextType);
        if (contextId != null && !contextId.isBlank()) payload.put("contextId", contextId);
        payload.put("reportedAt", Instant.now().toString());
        return toJson(payload);
    }

    private String challengeSnapshot(Challenge challenge, String contextType, String contextId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", TARGET_CHALLENGE);
        payload.put("challengeId", challenge.getId().toString());
        payload.putAll(challengeContent(challenge));
        payload.put("contextType", contextType);
        if (contextId != null && !contextId.isBlank()) payload.put("contextId", contextId);
        payload.put("reportedAt", Instant.now().toString());
        return toJson(payload);
    }

    // ===== 차단 목록 =====

    @Transactional(readOnly = true)
    public ReportDtos.BlockListResponse list(UUID blockerId) {
        record Blocked(UUID id, Instant at) {}

        List<Blocked> users = jdbc.query(
                "SELECT target_id, blocked_at FROM user_blocks WHERE blocker_id = ? AND target_type = 'USER' "
                        + "ORDER BY blocked_at DESC",
                (rs, row) -> new Blocked(uuid(rs.getBytes(1)), rs.getTimestamp(2).toInstant()),
                bytes(blockerId));

        Map<UUID, User> userMap = userRepository.findAllById(users.stream().map(Blocked::id).toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));

        List<ReportDtos.UserItem> userItems = users.stream()
                .map(b -> new ReportDtos.UserItem(b.id().toString(),
                        Optional.ofNullable(userMap.get(b.id())).map(User::deriveTempNickname).orElse(null),
                        b.at().toString()))
                .toList();

        List<ReportDtos.ChallengeItem> challengeItems = jdbc.query(
                "SELECT b.target_id, b.blocked_at, EXISTS("
                        + "  SELECT 1 FROM challenge_members m WHERE m.user_id = b.blocker_id "
                        + "     AND m.challenge_id = b.target_id AND m.status = 'ACTIVE') "
                        + "FROM user_blocks b WHERE b.blocker_id = ? AND b.target_type = 'CHALLENGE' "
                        + "ORDER BY b.blocked_at DESC",
                (rs, row) -> new ReportDtos.ChallengeItem(uuid(rs.getBytes(1)).toString(),
                        "숨김 처리된 챌린지", rs.getBoolean(3), rs.getTimestamp(2).toInstant().toString()),
                bytes(blockerId));

        return new ReportDtos.BlockListResponse(userItems, challengeItems);
    }

    /**
     * 차단 해제. <b>신고 취소가 아니다</b> — 이 행만 지우고 {@code reports} 와 스냅샷은 그대로 남는다.
     * 해제를 취소로 처리하면 가해자가 피해자에게 해제를 종용해 기록을 지우는 경로가 생긴다.
     */
    @Transactional
    public ReportDtos.DeleteResponse unblockUser(UUID blockerId, UUID targetId) {
        return unblock(blockerId, TARGET_USER, targetId);
    }

    @Transactional
    public ReportDtos.DeleteResponse unblockChallenge(UUID blockerId, UUID targetId) {
        return unblock(blockerId, TARGET_CHALLENGE, targetId);
    }

    private ReportDtos.DeleteResponse unblock(UUID blockerId, String targetType, UUID targetId) {
        int changed = jdbc.update(
                "DELETE FROM user_blocks WHERE blocker_id = ? AND target_type = ? AND target_id = ?",
                bytes(blockerId), targetType, bytes(targetId));
        if (changed == 0) throw new BusinessException(ErrorCode.BLOCK_ENTRY_NOT_FOUND);
        return new ReportDtos.DeleteResponse(true);
    }

    // ===== 다른 모듈이 쓰는 조회 =====

    /** 알림 생성 필터·프로필 마스킹이 쓴다. */
    public boolean isUserBlocked(UUID blockerId, UUID targetId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND target_type = 'USER' "
                        + "AND target_id = ?",
                Integer.class, bytes(blockerId), bytes(targetId));
        return count != null && count > 0;
    }

    /** 스레드·랭킹·멤버 목록의 마스킹 대상. */
    public Set<UUID> blockedUsers(UUID blockerId) {
        return new HashSet<>(jdbc.query(
                "SELECT target_id FROM user_blocks WHERE blocker_id = ? AND target_type = 'USER'",
                (rs, row) -> uuid(rs.getBytes(1)), bytes(blockerId)));
    }

    /** 탐색 목록에서 빼야 할 챌린지. */
    public Set<UUID> blockedChallenges(UUID blockerId) {
        return new HashSet<>(jdbc.query(
                "SELECT target_id FROM user_blocks WHERE blocker_id = ? AND target_type = 'CHALLENGE'",
                (rs, row) -> uuid(rs.getBytes(1)), bytes(blockerId)));
    }

    // ===== 내부 =====

    private boolean participating(UUID userId, UUID challengeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM challenge_members WHERE user_id = ? AND challenge_id = ? "
                        + "AND status = 'ACTIVE'",
                Integer.class, bytes(userId), bytes(challengeId));
        return count != null && count > 0;
    }

    private UUID parseUuid(String raw, ErrorCode onFailure) {
        if (raw == null || raw.isBlank()) throw new BusinessException(onFailure);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(onFailure);
        }
    }

    private Map<String,Object> challengeContent(Challenge challenge) {
        Map<String,Object> content=new LinkedHashMap<>();
        content.put("challengeTitle",challenge.getTitle());
        content.put("challengeDescription",challenge.getDescription());
        content.put("imageUrl",challenge.getImageUrl());
        content.put("moderationTitle",challenge.getModerationTitle().name());
        content.put("moderationDescription",challenge.getModerationDescription().name());
        content.put("moderationImage",challenge.getModerationImage().name());
        content.put("status",challenge.getStatus().name());
        return content;
    }

    private String toJson(Map<String,Object> payload) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(payload);
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
