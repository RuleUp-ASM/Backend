package com.ruleup.ruleup_backend.report;

import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BlacklistService {
    private static final Set<String> REASONS = Set.of(
            "CHEATING_SUSPECT", "INAPPROPRIATE", "SPAM_AD", "ETC",
            "SPAM", "ABUSE", "HATE", "SEXUAL", "VIOLENCE", "OTHER");
    private static final Set<String> CONTEXT_TYPES = Set.of(
            "PROFILE", "CHALLENGE_DETAIL", "ROOM", "NOTICE", "COMMENT", "VERIFY_EVENT");
    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final ChallengeRepository challengeRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public ReportDtos.CreateResponse report(UUID reporterId, ReportDtos.CreateRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);
        Timestamp suspendedUntil = jdbc.query("SELECT suspended_until FROM report_suspensions " +
                        "WHERE user_id=? AND suspended_until>CURRENT_TIMESTAMP(6)",
                rs -> rs.next() ? rs.getTimestamp(1) : null, bytes(reporterId));
        if (suspendedUntil != null)
            throw new BusinessException(ErrorCode.REPORT_SUSPENDED, suspendedUntil.toInstant().toString());
        String type = request.targetType() == null ? "" : request.targetType();
        if (request.reason() == null || !REASONS.contains(request.reason()))
            throw new BusinessException(ErrorCode.INVALID_REPORT_REASON);
        if (request.detail() == null || request.detail().isBlank())
            throw new BusinessException(ErrorCode.DETAIL_REQUIRED);
        if (request.contextType() == null || !CONTEXT_TYPES.contains(request.contextType())
                || request.detail().length() > 1000)
            throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);

        UUID contextId = null;
        if (request.contextId() != null && !request.contextId().isBlank()) contextId = uuid(request.contextId());
        if (Set.of("NOTICE", "COMMENT", "VERIFY_EVENT").contains(request.contextType()) && contextId == null)
            throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);

        UUID targetUser = null;
        UUID targetChallenge = null;
        if ("USER".equals(type)) {
            targetUser = uuid(request.targetUserId());
            if (targetUser.equals(reporterId)) throw new BusinessException(ErrorCode.CANNOT_REPORT_SELF);
            if (!userRepository.existsById(targetUser)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
            if (request.targetChallengeId() != null && !request.targetChallengeId().isBlank()) {
                targetChallenge = uuid(request.targetChallengeId());
                if (!challengeRepository.existsById(targetChallenge))
                    throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
            }
            if (!"PROFILE".equals(request.contextType()) && targetChallenge == null)
                throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);
        } else if ("CHALLENGE".equals(type)) {
            if ("PROFILE".equals(request.contextType()))
                throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);
            targetChallenge = uuid(request.targetChallengeId());
            if (!challengeRepository.existsById(targetChallenge))
                throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        } else throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET);

        boolean duplicate = duplicate(reporterId, type, targetUser, targetChallenge);
        UUID reportId = UuidGenerator.generate();
        jdbc.update("INSERT INTO reports (id, reporter_id, target_type, target_user_id, target_challenge_id, " +
                        "context_type, context_id, reason, detail, duplicate_report) VALUES (?,?,?,?,?,?,?,?,?,?)",
                bytes(reportId), bytes(reporterId), type, bytesOrNull(targetUser), bytesOrNull(targetChallenge),
                request.contextType(), bytesOrNull(contextId), request.reason(), normalize(request.detail()), duplicate);
        if (targetUser != null) {
            jdbc.update("INSERT IGNORE INTO blacklist_users (owner_id, blocked_user_id, source_report_id) VALUES (?,?,?)",
                    bytes(reporterId), bytes(targetUser), bytes(reportId));
        } else {
            jdbc.update("INSERT IGNORE INTO blacklist_challenges (owner_id, blocked_challenge_id, source_report_id) VALUES (?,?,?)",
                    bytes(reporterId), bytes(targetChallenge), bytes(reportId));
        }
        events.publishEvent(new ReportSubmitted(reportId));
        String hiddenEffect = targetUser != null ? "USER_CONTENT_MASKED"
                : participating(reporterId, targetChallenge) ? "CHALLENGE_MASKED" : "CHALLENGE_HIDDEN";
        return new ReportDtos.CreateResponse(reportId.toString(), duplicate, true, hiddenEffect);
    }

    @Transactional(readOnly = true)
    public ReportDtos.BlacklistResponse list(UUID ownerId) {
        record BlockedUser(UUID id, Instant at) {}
        List<BlockedUser> blockedUsers = jdbc.query(
                "SELECT blocked_user_id,created_at FROM blacklist_users WHERE owner_id=? ORDER BY created_at DESC",
                (rs, row) -> new BlockedUser(uuid(rs.getBytes(1)), rs.getTimestamp(2).toInstant()), bytes(ownerId));
        Map<UUID, com.ruleup.ruleup_backend.user.domain.User> userMap = userRepository
                .findAllById(blockedUsers.stream().map(BlockedUser::id).toList()).stream()
                .collect(Collectors.toMap(com.ruleup.ruleup_backend.user.domain.User::getId, Function.identity()));
        List<ReportDtos.UserItem> users = blockedUsers.stream().map(blocked -> {
            var user = userMap.get(blocked.id());
            return new ReportDtos.UserItem(blocked.id().toString(),
                    user == null ? null : user.deriveTempNickname(), blocked.at().toString());
        }).toList();
        List<ReportDtos.ChallengeItem> challenges = jdbc.query(
                "SELECT b.blocked_challenge_id,b.created_at,EXISTS(" +
                        "SELECT 1 FROM challenge_members m WHERE m.user_id=b.owner_id " +
                        "AND m.challenge_id=b.blocked_challenge_id AND m.status='ACTIVE') " +
                        "FROM blacklist_challenges b WHERE b.owner_id=? ORDER BY b.created_at DESC",
                (rs, row) -> new ReportDtos.ChallengeItem(uuid(rs.getBytes(1)).toString(),
                        "숨김 처리된 챌린지", rs.getBoolean(3), rs.getTimestamp(2).toInstant().toString()),
                bytes(ownerId));
        return new ReportDtos.BlacklistResponse(users, challenges);
    }

    @Transactional
    public ReportDtos.DeleteResponse unblockUser(UUID ownerId, UUID blockedId) {
        int changed = jdbc.update("DELETE FROM blacklist_users WHERE owner_id=? AND blocked_user_id=?",
                bytes(ownerId), bytes(blockedId));
        if (changed == 0) throw new BusinessException(ErrorCode.BLACKLIST_ENTRY_NOT_FOUND);
        return new ReportDtos.DeleteResponse(true);
    }

    @Transactional
    public ReportDtos.DeleteResponse unblockChallenge(UUID ownerId, UUID challengeId) {
        int changed = jdbc.update("DELETE FROM blacklist_challenges WHERE owner_id=? AND blocked_challenge_id=?",
                bytes(ownerId), bytes(challengeId));
        if (changed == 0) throw new BusinessException(ErrorCode.BLACKLIST_ENTRY_NOT_FOUND);
        return new ReportDtos.DeleteResponse(true);
    }

    public boolean isUserBlocked(UUID ownerId, UUID targetId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blacklist_users WHERE owner_id=? AND blocked_user_id=?",
                Integer.class, bytes(ownerId), bytes(targetId));
        return count != null && count > 0;
    }

    public Set<UUID> blockedUsers(UUID ownerId) {
        return jdbc.query("SELECT blocked_user_id FROM blacklist_users WHERE owner_id=?",
                        (rs, row) -> uuid(rs.getBytes(1)), bytes(ownerId)).stream().collect(Collectors.toSet());
    }

    private boolean participating(UUID userId, UUID challengeId) {
        if (challengeId == null) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM challenge_members " +
                        "WHERE user_id=? AND challenge_id=? AND status='ACTIVE'", Integer.class,
                bytes(userId), bytes(challengeId));
        return count != null && count > 0;
    }

    private boolean duplicate(UUID reporter, String type, UUID targetUser, UUID targetChallenge) {
        Integer count;
        Timestamp since = Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS));
        if (targetUser != null) {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE reporter_id=? AND target_type=? " +
                            "AND target_user_id=? AND created_at>=?", Integer.class,
                    bytes(reporter), type, bytes(targetUser), since);
        } else {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE reporter_id=? AND target_type=? " +
                            "AND target_challenge_id=? AND created_at>=?", Integer.class,
                    bytes(reporter), type, bytes(targetChallenge), since);
        }
        return count != null && count > 0;
    }

    private UUID uuid(String raw) {
        try { return UUID.fromString(raw); }
        catch (RuntimeException e) { throw new BusinessException(ErrorCode.INVALID_REPORT_TARGET); }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static byte[] bytesOrNull(UUID id) { return id == null ? null : bytes(id); }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
