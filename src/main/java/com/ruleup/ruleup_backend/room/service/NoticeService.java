package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.domain.Notice;
import com.ruleup.ruleup_backend.room.domain.NoticeRead;
import com.ruleup.ruleup_backend.room.domain.RoomActivityLog;
import com.ruleup.ruleup_backend.room.domain.RoomLogAction;
import com.ruleup.ruleup_backend.room.dto.NoticeDtos;
import com.ruleup.ruleup_backend.room.repository.NoticeReadRepository;
import com.ruleup.ruleup_backend.room.repository.NoticeRepository;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 챌린지 공지(방 내부기능 §7.1~7.2). 방장 CRUD + 상세 조회 읽음 + 단일 pin + ACTIVE 멤버 인앱 fan-out.
 * 방장 판정은 RoomAuthority 단일 게이트 경유.
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private static final int RECENT_LIMIT = 10;
    private static final int PREVIEW_LEN = 80;
    private static final int TITLE_MAX = 100;
    private static final int CONTENT_MAX = 2000;

    private final RoomAuthority roomAuthority;
    private final NoticeRepository noticeRepo;
    private final NoticeReadRepository noticeReadRepo;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RoomActivityLogger activityLogger;

    // ===== 목록 =====
    @Transactional(readOnly = true)
    public NoticeDtos.ListResponse list(UUID userId, UUID challengeId) {
        roomAuthority.requireMember(challengeId, userId);
        List<Notice> notices = noticeRepo.findByChallengeIdOrderByPinnedDescCreatedAtDesc(
                challengeId, PageRequest.of(0, RECENT_LIMIT));
        Set<UUID> read = noticeReadRepo.findByUserIdAndNoticeIdIn(userId,
                        notices.stream().map(Notice::getId).toList()).stream()
                .map(NoticeRead::getNoticeId).collect(Collectors.toSet());

        List<NoticeDtos.ListResponse.Item> items = notices.stream()
                .map(n -> new NoticeDtos.ListResponse.Item(
                        n.getId().toString(), n.getTitle(), preview(n.getContent()),
                        n.isPinned(), read.contains(n.getId()), n.getCreatedAt().toString()))
                .toList();
        return new NoticeDtos.ListResponse(items);
    }

    // ===== 생성(방장) =====
    @Transactional
    public NoticeDtos.CreateResponse create(UUID userId, UUID challengeId, NoticeDtos.CreateRequest req) {
        Challenge c = roomAuthority.requireOwner(challengeId, userId);
        validate(req.title(), req.content());

        if (req.pinnedOrFalse()) unpinExisting(challengeId, null);
        Notice notice = noticeRepo.saveAndFlush(
                Notice.create(challengeId, userId, req.title(), req.content(), req.pinnedOrFalse()));

        activityLogger.log(challengeId, userId, RoomActivityLog.ENTITY_NOTICE, notice.getId(),
                RoomLogAction.CREATE, Map.of("title", notice.getTitle(), "pinned", notice.isPinned()));
        fanOut(challengeId, userId, notice.getTitle());
        return new NoticeDtos.CreateResponse(
                notice.getId().toString(), notice.isPinned(), notice.getCreatedAt().toString());
    }

    // ===== 상세(조회 = 읽음) =====
    @Transactional
    public NoticeDtos.DetailResponse detail(UUID userId, UUID challengeId, UUID noticeId) {
        roomAuthority.requireMember(challengeId, userId);
        Notice n = loadNotice(noticeId, challengeId);

        // 상세 조회 = 읽음(멱등 upsert). uq(noticeId,userId) 경합은 무시.
        if (!noticeReadRepo.existsByNoticeIdAndUserId(noticeId, userId)) {
            try {
                noticeReadRepo.save(NoticeRead.of(noticeId, challengeId, userId, Instant.now()));
            } catch (DataIntegrityViolationException dup) { /* 이미 읽음 — 멱등 */ }
        }

        User author = userRepository.findById(n.getAuthorId()).orElse(null);
        String nickname = (author != null) ? author.visibleNicknameTo(userId) : null;
        String profile = (author != null) ? author.visibleProfileImageTo(userId) : null;
        String updatedAt = n.getUpdatedAt() != null && !n.getUpdatedAt().equals(n.getCreatedAt())
                ? n.getUpdatedAt().toString() : null;   // 수정된 적 있을 때만
        return new NoticeDtos.DetailResponse(
                n.getId().toString(), n.getTitle(), n.getContent(), n.isPinned(),
                new NoticeDtos.DetailResponse.Author(nickname, profile),
                n.getCreatedAt().toString(), updatedAt);
    }

    // ===== 수정(방장) =====
    @Transactional
    public NoticeDtos.EditResponse edit(UUID userId, UUID challengeId, UUID noticeId, NoticeDtos.EditRequest req) {
        roomAuthority.requireOwner(challengeId, userId);
        validate(req.title(), req.content());
        Notice n = loadNotice(noticeId, challengeId);
        n.edit(req.title(), req.content());

        boolean readReset = req.resetReadOrFalse();
        if (readReset) {
            noticeReadRepo.deleteByNoticeId(noticeId);   // 전 멤버 미읽음 복귀
            fanOut(challengeId, userId, n.getTitle());   // 재발송
        }
        noticeRepo.saveAndFlush(n);
        activityLogger.log(challengeId, userId, RoomActivityLog.ENTITY_NOTICE, noticeId,
                RoomLogAction.UPDATE, Map.of("title", n.getTitle(), "readReset", readReset));
        return new NoticeDtos.EditResponse(n.getId().toString(), n.getUpdatedAt().toString(), readReset);
    }

    // ===== 삭제(방장, 물리 삭제 + 로그 보존) =====
    @Transactional
    public void delete(UUID userId, UUID challengeId, UUID noticeId) {
        roomAuthority.requireOwner(challengeId, userId);
        Notice n = loadNotice(noticeId, challengeId);

        // 물리 삭제 전 원본 스냅샷을 로그로 보존(삭제 후에도 내용 확인 가능).
        Map<String, ?> snapshot = Map.of(
                "title", n.getTitle(), "content", n.getContent(), "pinned", n.isPinned());
        noticeReadRepo.deleteByNoticeId(noticeId);   // FK: 읽음 먼저 정리
        noticeRepo.deleteById(noticeId);             // 물리 삭제
        activityLogger.log(challengeId, userId, RoomActivityLog.ENTITY_NOTICE, noticeId,
                RoomLogAction.DELETE, snapshot);
    }

    // ===== 고정(방장, 단일 pin) =====
    @Transactional
    public NoticeDtos.PinResponse pin(UUID userId, UUID challengeId, UUID noticeId, NoticeDtos.PinRequest req) {
        roomAuthority.requireOwner(challengeId, userId);
        Notice n = loadNotice(noticeId, challengeId);

        String unpinnedId = null;
        if (req.pinnedOrFalse()) {
            unpinnedId = unpinExisting(challengeId, noticeId);   // 기존 고정 교체(자신 제외)
            n.pin();
        } else {
            n.unpin();
        }
        activityLogger.log(challengeId, userId, RoomActivityLog.ENTITY_NOTICE, noticeId,
                RoomLogAction.UPDATE, Map.of("pinned", n.isPinned()));
        return new NoticeDtos.PinResponse(n.getId().toString(), n.isPinned(), unpinnedId);
    }

    // ===== 헬퍼 =====
    /** 현재 고정 공지를 해제(except 제외). 해제된 공지 id 반환(없으면 null). */
    private String unpinExisting(UUID challengeId, UUID exceptId) {
        Notice pinned = noticeRepo.findByChallengeIdAndPinnedTrue(challengeId).orElse(null);
        if (pinned == null || pinned.getId().equals(exceptId)) return null;
        pinned.unpin();
        return pinned.getId().toString();
    }

    /** ACTIVE 멤버(작성자 제외) 인앱 알림 fan-out. */
    private void fanOut(UUID challengeId, UUID authorId, String title) {
        for (ChallengeMember m : memberRepository.findByChallengeIdAndStatusOrderByJoinedAtAsc(challengeId, MemberStatus.ACTIVE)) {
            if (m.getUserId().equals(authorId)) continue;
            notificationService.notify(m.getUserId(), NotificationType.NOTICE_CREATED,
                    "새 공지가 등록되었어요", title);
        }
    }

    private Notice loadNotice(UUID noticeId, UUID challengeId) {
        return noticeRepo.findByIdAndChallengeId(noticeId, challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));
    }

    private void validate(String title, String content) {
        if (title == null || title.isBlank() || title.length() > TITLE_MAX
                || content == null || content.isBlank() || content.length() > CONTENT_MAX) {
            throw new BusinessException(ErrorCode.INVALID_NOTICE_PAYLOAD);
        }
    }

    private String preview(String content) {
        if (content == null) return null;
        return content.length() <= PREVIEW_LEN ? content : content.substring(0, PREVIEW_LEN);
    }
}
