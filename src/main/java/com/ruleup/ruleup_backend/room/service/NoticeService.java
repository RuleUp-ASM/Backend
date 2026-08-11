package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.text.Texts;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.domain.CommentTargetType;
import com.ruleup.ruleup_backend.room.domain.Notice;
import com.ruleup.ruleup_backend.room.domain.RoomActivityLog;
import com.ruleup.ruleup_backend.room.domain.RoomLogAction;
import com.ruleup.ruleup_backend.room.dto.NoticeDtos;
import com.ruleup.ruleup_backend.room.repository.NoticeRepository;
import com.ruleup.ruleup_backend.room.repository.RoomCommentRepository;
import com.ruleup.ruleup_backend.report.BlacklistService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NoticeService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int PREVIEW_LEN = 80;
    private static final int DAILY_LIMIT = 3;
    private final RoomAuthority roomAuthority;
    private final NoticeRepository noticeRepository;
    private final RoomCommentRepository commentRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RoomActivityLogger activityLogger;
    private final BlacklistService blacklistService;

    @Transactional(readOnly = true)
    public NoticeDtos.ListResponse list(UUID userId, UUID challengeId, String cursor, Integer requestedSize) {
        roomAuthority.requireMember(challengeId, userId);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        Set<UUID> blocked = blacklistService.blockedUsers(userId);
        List<Notice> all = noticeRepository
                .findByChallengeIdAndDeletedAtIsNullOrderByPinnedDescCreatedAtDesc(challengeId).stream()
                .filter(notice -> !blocked.contains(notice.getAuthorId())).toList();
        int start = cursorStart(all, cursor);
        List<Notice> page = all.stream().skip(start).limit(size).toList();
        String next = start + page.size() < all.size() && !page.isEmpty()
                ? page.get(page.size() - 1).getId().toString() : null;
        List<NoticeDtos.ListResponse.Item> items = new ArrayList<>();
        for (Notice notice : page) {
            items.add(new NoticeDtos.ListResponse.Item(notice.getId().toString(), notice.getTitle(),
                    preview(notice.getContent()), notice.isPinned(), author(notice.getAuthorId(), userId),
                    commentRepository.countByTargetTypeAndTargetIdAndDeletedAtIsNull(
                            CommentTargetType.NOTICE, notice.getId()),
                    notice.getCreatedAt().toString(), updatedAt(notice)));
        }
        return new NoticeDtos.ListResponse(items, next);
    }

    @Transactional
    public NoticeDtos.CreateResponse create(UUID userId, UUID challengeId, NoticeDtos.CreateRequest request) {
        roomAuthority.requireOwner(challengeId, userId);
        challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        validate(request.title(), request.content());
        LocalDate today = LocalDate.now(KST);
        Instant start = today.atStartOfDay(KST).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(KST).toInstant();
        long count = noticeRepository
                .countByChallengeIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndDeletedAtIsNull(
                        challengeId, start, end);
        if (count >= DAILY_LIMIT) throw new BusinessException(ErrorCode.NOTICE_DAILY_QUOTA_EXCEEDED);

        Notice notice = noticeRepository.saveAndFlush(
                Notice.create(challengeId, userId, request.title().trim(), request.content().trim(), false));
        activityLogger.log(challengeId, userId, RoomActivityLog.ENTITY_NOTICE, notice.getId(),
                RoomLogAction.CREATE, Map.of("title", notice.getTitle()));
        if (request.sendPushOrFalse()) fanOut(challengeId, userId, notice.getTitle());
        return new NoticeDtos.CreateResponse(notice.getId().toString(), notice.getCreatedAt().toString());
    }

    @Transactional(readOnly = true)
    public NoticeDtos.DetailResponse detail(UUID userId, UUID challengeId, UUID noticeId) {
        roomAuthority.requireMember(challengeId, userId);
        Notice notice = load(noticeId, challengeId);
        if (blacklistService.blockedUsers(userId).contains(notice.getAuthorId()))
            throw new BusinessException(ErrorCode.NOTICE_NOT_FOUND);
        return new NoticeDtos.DetailResponse(notice.getId().toString(), notice.getTitle(), notice.getContent(),
                notice.isPinned(), author(notice.getAuthorId(), userId),
                commentRepository.countByTargetTypeAndTargetIdAndDeletedAtIsNull(
                        CommentTargetType.NOTICE, notice.getId()),
                notice.getCreatedAt().toString(), updatedAt(notice));
    }

    @Transactional
    public NoticeDtos.EditResponse edit(UUID userId, UUID challengeId, UUID noticeId,
                                        NoticeDtos.EditRequest request) {
        roomAuthority.requireOwner(challengeId, userId);
        validate(request.title(), request.content());
        Notice notice = load(noticeId, challengeId);
        notice.edit(request.title().trim(), request.content().trim());
        noticeRepository.saveAndFlush(notice);
        activityLogger.log(challengeId, userId, RoomActivityLog.ENTITY_NOTICE, noticeId,
                RoomLogAction.UPDATE, Map.of("title", notice.getTitle()));
        return new NoticeDtos.EditResponse(notice.getId().toString(), notice.getUpdatedAt().toString());
    }

    @Transactional
    public void delete(UUID userId, UUID challengeId, UUID noticeId) {
        roomAuthority.requireOwner(challengeId, userId);
        challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        Notice notice = load(noticeId, challengeId);
        notice.delete(Instant.now());
        activityLogger.log(challengeId, userId, RoomActivityLog.ENTITY_NOTICE, noticeId,
                RoomLogAction.DELETE, Map.of("title", notice.getTitle(), "content", notice.getContent()));
    }

    @Transactional
    public NoticeDtos.PinResponse pin(UUID userId, UUID challengeId, UUID noticeId, NoticeDtos.PinRequest request) {
        roomAuthority.requireOwner(challengeId, userId);
        challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        Notice notice = load(noticeId, challengeId);
        String unpinnedId = null;
        if (request.pinnedOrFalse()) {
            Notice old = noticeRepository.findByChallengeIdAndPinnedTrueAndDeletedAtIsNull(challengeId).orElse(null);
            if (old != null && !old.getId().equals(noticeId)) {
                old.unpin();
                unpinnedId = old.getId().toString();
            }
            notice.pin();
        } else notice.unpin();
        return new NoticeDtos.PinResponse(noticeId.toString(), notice.isPinned(), unpinnedId);
    }

    private Notice load(UUID noticeId, UUID challengeId) {
        return noticeRepository.findByIdAndChallengeIdAndDeletedAtIsNull(noticeId, challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));
    }

    private NoticeDtos.Author author(UUID authorId, UUID viewerId) {
        User user = userRepository.findById(authorId).orElse(null);
        return new NoticeDtos.Author(authorId.toString(), user == null ? null : user.visibleNicknameTo(viewerId),
                user == null ? null : user.visibleProfileImageTo(viewerId));
    }

    private void fanOut(UUID challengeId, UUID authorId, String title) {
        for (ChallengeMember member : memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE)) {
            if (!member.getUserId().equals(authorId)) {
                notificationService.notify(member.getUserId(), NotificationType.NOTICE_CREATED,
                        "새 공지가 등록되었어요", title);
            }
        }
    }

    private int cursorStart(List<Notice> notices, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            UUID id = UUID.fromString(cursor);
            for (int i = 0; i < notices.size(); i++) if (notices.get(i).getId().equals(id)) return i + 1;
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessException(ErrorCode.CURSOR_INVALID);
    }

    private void validate(String title, String content) {
        if (title == null || title.isBlank() || title.trim().length() > 100
                || content == null || content.isBlank() || content.trim().length() > 2000)
            throw new BusinessException(ErrorCode.INVALID_NOTICE_PAYLOAD);
    }

    /** 목록용 미리보기. 이모지가 경계에 걸려도 반쪽 char 가 남지 않게 코드포인트 기준으로 자른다. */
    private String preview(String content) {
        return Texts.truncate(content, PREVIEW_LEN);
    }

    private String updatedAt(Notice notice) {
        return notice.getUpdatedAt() != null && !notice.getUpdatedAt().equals(notice.getCreatedAt())
                ? notice.getUpdatedAt().toString() : null;
    }
}
