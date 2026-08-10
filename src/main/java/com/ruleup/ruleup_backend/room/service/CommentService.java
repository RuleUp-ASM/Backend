package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.domain.CommentTargetType;
import com.ruleup.ruleup_backend.room.domain.RoomComment;
import com.ruleup.ruleup_backend.room.dto.CommentDtos;
import com.ruleup.ruleup_backend.room.repository.NoticeRepository;
import com.ruleup.ruleup_backend.room.repository.RoomCommentRepository;
import com.ruleup.ruleup_backend.report.BlacklistService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentService {
    private final RoomAuthority authority;
    private final RoomCommentRepository commentRepository;
    private final NoticeRepository noticeRepository;
    private final VerificationDailyRepository verificationRepository;
    private final UserRepository userRepository;
    private final BlacklistService blacklistService;
    private final NotificationService notificationService;

    @Transactional
    public CommentDtos.CreateResponse create(UUID userId, CommentDtos.CreateRequest request) {
        CommentTargetType type = targetType(request.targetType());
        UUID targetId = uuid(request.targetId(), ErrorCode.COMMENT_TARGET_NOT_FOUND);
        String body = normalizeBody(request.body());
        UUID challengeId = challengeId(type, targetId);
        authority.requireMember(challengeId, userId);

        UUID parentId = request.parentCommentId() == null ? null
                : uuid(request.parentCommentId(), ErrorCode.COMMENT_NOT_FOUND);
        UUID recipientId = null;
        if (parentId != null) {
            RoomComment parent = commentRepository.findByIdAndDeletedAtIsNull(parentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
            if (!parent.getTargetType().equals(type) || !parent.getTargetId().equals(targetId))
                throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
            if (parent.getParentCommentId() != null)
                throw new BusinessException(ErrorCode.REPLY_DEPTH_EXCEEDED);
            recipientId = parent.getAuthorId();
        } else if (type == CommentTargetType.NOTICE) {
            recipientId = noticeRepository.findByIdAndDeletedAtIsNull(targetId).map(n -> n.getAuthorId()).orElse(null);
        } else {
            recipientId = verificationRepository.findById(targetId).map(v -> v.getUserId()).orElse(null);
        }

        RoomComment saved = commentRepository.saveAndFlush(
                RoomComment.create(challengeId, type, targetId, userId, parentId, body));
        boolean notified = recipientId != null && !recipientId.equals(userId);
        if (notified) notificationService.notify(recipientId, NotificationType.COMMENT_CREATED,
                "새 댓글이 달렸어요", body);
        return new CommentDtos.CreateResponse(saved.getId().toString(), type.name(), targetId.toString(),
                author(userId), parentId == null ? null : parentId.toString(), saved.getCreatedAt().toString(), notified);
    }

    @Transactional(readOnly = true)
    public CommentDtos.ListResponse list(UUID userId, String targetType, String rawTargetId,
                                         String cursor, Integer requestedSize) {
        CommentTargetType type = targetType(targetType);
        UUID targetId = uuid(rawTargetId, ErrorCode.COMMENT_TARGET_NOT_FOUND);
        UUID challengeId = challengeId(type, targetId);
        boolean owner = authority.requireMember(challengeId, userId).isOwner(userId);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));

        Set<UUID> blocked = blacklistService.blockedUsers(userId);
        List<RoomComment> all = commentRepository
                .findByTargetTypeAndTargetIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(type, targetId).stream()
                .filter(comment -> !blocked.contains(comment.getAuthorId())).toList();
        Map<UUID, User> users = userRepository.findAllById(all.stream().map(RoomComment::getAuthorId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<RoomComment> roots = all.stream().filter(c -> c.getParentCommentId() == null).toList();
        int start = cursorStart(roots, cursor);
        List<RoomComment> page = roots.stream().skip(start).limit(size).toList();
        String next = start + page.size() < roots.size() && !page.isEmpty()
                ? page.get(page.size() - 1).getId().toString() : null;

        List<CommentDtos.Comment> dto = new ArrayList<>();
        for (RoomComment root : page) {
            List<CommentDtos.Comment> replies = all.stream()
                    .filter(c -> root.getId().equals(c.getParentCommentId()))
                    .map(c -> dto(c, userId, users, blocked, List.of(), owner)).toList();
            dto.add(dto(root, userId, users, blocked, replies, owner));
        }
        return new CommentDtos.ListResponse(dto, next);
    }

    @Transactional
    public CommentDtos.DeleteResponse delete(UUID userId, UUID commentId) {
        RoomComment comment = commentRepository.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
        var challenge = authority.requireMember(comment.getChallengeId(), userId);
        if (!comment.getAuthorId().equals(userId) && !challenge.isOwner(userId))
            throw new BusinessException(ErrorCode.NOT_COMMENT_DELETABLE);
        Instant now = Instant.now();
        comment.delete(now);
        if (comment.getParentCommentId() == null) {
            commentRepository.findByParentCommentIdAndDeletedAtIsNull(commentId)
                    .forEach(reply -> reply.delete(now));
        }
        return new CommentDtos.DeleteResponse(true);
    }

    private UUID challengeId(CommentTargetType type, UUID targetId) {
        return switch (type) {
            case NOTICE -> noticeRepository.findByIdAndDeletedAtIsNull(targetId)
                    .map(n -> n.getChallengeId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_TARGET_NOT_FOUND));
            case VERIFY_EVENT -> verificationRepository.findById(targetId)
                    .filter(v -> (v.getStatus() == com.ruleup.ruleup_backend.common.verification.VerificationStatus.SUCCESS
                                    && v.getVerifiedAt() != null)
                            || (v.getStatus() == com.ruleup.ruleup_backend.common.verification.VerificationStatus.FAILED
                                    && v.getShareableAt() != null && !v.getShareableAt().isAfter(Instant.now())))
                    .map(v -> v.getChallengeId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_TARGET_NOT_FOUND));
        };
    }

    private CommentDtos.Comment dto(RoomComment c, UUID viewerId, Map<UUID, User> users,
                                    Set<UUID> blocked, List<CommentDtos.Comment> replies, boolean owner) {
        User u = users.get(c.getAuthorId());
        CommentDtos.Author author = new CommentDtos.Author(c.getAuthorId().toString(),
                u == null ? null : u.visibleNicknameTo(viewerId),
                u == null ? null : u.visibleProfileImageTo(viewerId),
                blocked.contains(c.getAuthorId()));
        return new CommentDtos.Comment(c.getId().toString(), c.getBody(), author,
                c.getParentCommentId() == null ? null : c.getParentCommentId().toString(),
                c.getCreatedAt().toString(), owner || c.getAuthorId().equals(viewerId), replies);
    }

    private CommentDtos.Author author(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        return new CommentDtos.Author(userId.toString(), user == null ? null : user.visibleNicknameTo(userId),
                user == null ? null : user.visibleProfileImageTo(userId), false);
    }

    private int cursorStart(List<RoomComment> roots, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        UUID id = uuid(cursor, ErrorCode.CURSOR_INVALID);
        for (int i = 0; i < roots.size(); i++) if (roots.get(i).getId().equals(id)) return i + 1;
        throw new BusinessException(ErrorCode.CURSOR_INVALID);
    }

    private CommentTargetType targetType(String raw) {
        try { return CommentTargetType.valueOf(raw == null ? "" : raw); }
        catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_COMMENT_PAYLOAD); }
    }

    private String normalizeBody(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 500)
            throw new BusinessException(ErrorCode.INVALID_COMMENT_PAYLOAD);
        return value;
    }

    private UUID uuid(String raw, ErrorCode code) {
        try { return UUID.fromString(raw); }
        catch (RuntimeException e) { throw new BusinessException(code); }
    }
}
