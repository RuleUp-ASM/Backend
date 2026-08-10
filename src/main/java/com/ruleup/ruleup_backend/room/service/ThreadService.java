package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.domain.CommentTargetType;
import com.ruleup.ruleup_backend.room.domain.Notice;
import com.ruleup.ruleup_backend.room.dto.ThreadDtos;
import com.ruleup.ruleup_backend.room.repository.NoticeRepository;
import com.ruleup.ruleup_backend.room.repository.RoomCommentRepository;
import com.ruleup.ruleup_backend.report.BlacklistService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThreadService {
    private final RoomAuthority authority;
    private final NoticeRepository noticeRepository;
    private final VerificationDailyRepository verificationRepository;
    private final ChallengeMemberRepository memberRepository;
    private final RoomCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final BlacklistService blacklistService;

    private record Row(UUID id, Instant at, String type, UUID userId, Integer streak,
                       String failDate, String title, long comments) {}

    public ThreadDtos.Response get(UUID viewerId, UUID challengeId, String cursor, Integer requestedSize) {
        authority.requireMember(challengeId, viewerId);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        Instant now = Instant.now();
        Set<UUID> blocked = blacklistService.blockedUsers(viewerId);
        List<Row> rows = new ArrayList<>();

        for (Notice n : noticeRepository.findByChallengeIdAndDeletedAtIsNullOrderByCreatedAtDesc(challengeId)) {
            if (blocked.contains(n.getAuthorId())) continue;
            rows.add(new Row(n.getId(), n.getCreatedAt(), "NOTICE", n.getAuthorId(), null, null, n.getTitle(),
                    commentRepository.countByTargetTypeAndTargetIdAndDeletedAtIsNull(CommentTargetType.NOTICE, n.getId())));
        }
        Map<UUID, ChallengeMember> memberships = memberRepository
                .findByChallengeIdOrderByJoinedAtAsc(challengeId).stream()
                .collect(Collectors.toMap(ChallengeMember::getUserId, Function.identity(), (a, b) -> a));
        for (VerificationDaily v : verificationRepository.findByChallengeIdAndStatusIn(
                challengeId, List.of(VerificationStatus.SUCCESS, VerificationStatus.FAILED))) {
            boolean success = v.getStatus() == VerificationStatus.SUCCESS;
            Instant at = success ? v.getVerifiedAt() : v.getShareableAt();
            if (at == null || at.isAfter(now)) continue;
            ChallengeMember member = memberships.get(v.getUserId());
            rows.add(new Row(v.getId(), at, success ? "VERIFY_SUCCESS" : "VERIFY_FAIL", v.getUserId(),
                    success && member != null ? member.getSuccessDays() : null,
                    success ? null : v.getTargetDate().toString(), null,
                    commentRepository.countByTargetTypeAndTargetIdAndDeletedAtIsNull(
                            CommentTargetType.VERIFY_EVENT, v.getId())));
        }
        rows.sort(Comparator.comparing(Row::at).reversed().thenComparing(Row::id, Comparator.reverseOrder()));
        int start = cursorStart(rows, cursor);
        List<Row> page = rows.stream().skip(start).limit(size).toList();
        String next = start + page.size() < rows.size() && !page.isEmpty()
                ? page.get(page.size() - 1).id().toString() : null;

        Map<UUID, User> users = userRepository.findAllById(page.stream().map(Row::userId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<ThreadDtos.Item> items = page.stream().map(row -> {
            User user = users.get(row.userId());
            boolean masked = blocked.contains(row.userId());
            ThreadDtos.User author = new ThreadDtos.User(row.userId().toString(),
                    user == null || masked ? null : user.visibleNicknameTo(viewerId),
                    user == null || masked ? null : user.visibleProfileImageTo(viewerId), masked);
            return new ThreadDtos.Item(row.type(), row.id().toString(), author, row.at().toString(), row.streak(),
                    row.failDate(), row.title(), row.comments());
        }).toList();

        Notice pinned = noticeRepository.findByChallengeIdAndPinnedTrueAndDeletedAtIsNull(challengeId).orElse(null);
        ThreadDtos.PinnedNotice pinnedDto = pinned == null ? null
                : new ThreadDtos.PinnedNotice(pinned.getId().toString(), pinned.getTitle(),
                pinned.getContent(), pinned.getCreatedAt().toString());
        return new ThreadDtos.Response(pinnedDto, items, next);
    }

    private int cursorStart(List<Row> rows, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            UUID id = UUID.fromString(cursor);
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(id)) return i + 1;
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessException(ErrorCode.CURSOR_INVALID);
    }
}
