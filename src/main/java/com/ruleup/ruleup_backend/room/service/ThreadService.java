package com.ruleup.ruleup_backend.room.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.room.RoomAuthority;
import com.ruleup.ruleup_backend.room.dto.ThreadDtos;
import com.ruleup.ruleup_backend.report.BlacklistService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * 방 스레드 피드(Phase 1 — 인증 이벤트 전용).
 *
 * <p>실패 이벤트는 <b>공유 가능 시각(shareableAt)</b>으로만 거른다. 이의 가능 기간(1일) 안이거나
 * 이의가 인용된 건은 인증 모듈이 shareableAt 을 비워두므로 이 한 줄이 곧 "조기 노출 0건" 가드레일이다
 * (기능 스펙 3-3 절대 조건). 정렬 시각도 판정 시각이 아니라 공유 가능 시각이라, 하루 늦게 과거형으로
 * 흐르는 정책 표현과 피드 순서가 어긋나지 않는다.
 *
 * <p>차단 유저의 인증 이벤트는 목록에서 빼지 않고 <b>마스킹해서</b> 노출한다 — 빼버리면 스레드에
 * 구멍이 생겨 맥락이 무너진다(테크 스펙 6). 마스킹은 조회자 컨텍스트라 응답을 캐시하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThreadService {
    private static final Logger log = LoggerFactory.getLogger(ThreadService.class);

    private final RoomAuthority authority;
    private final VerificationDailyRepository verificationRepository;
    private final ChallengeMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final BlacklistService blacklistService;

    private record Row(UUID id, Instant at, String type, UUID userId, Integer streak, String failDate) {}

    public ThreadDtos.Response get(UUID viewerId, UUID challengeId, String cursor, Integer requestedSize) {
        authority.requireMember(challengeId, viewerId);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        Instant now = Instant.now();
        Set<UUID> blocked = blacklistService.blockedUsers(viewerId);

        Map<UUID, ChallengeMember> memberships = memberRepository
                .findByChallengeIdOrderByJoinedAtAsc(challengeId).stream()
                .collect(Collectors.toMap(ChallengeMember::getUserId, Function.identity(), (a, b) -> a));

        List<Row> rows = new ArrayList<>();
        for (VerificationDaily v : verificationRepository.findByChallengeIdAndStatusIn(
                challengeId, List.of(VerificationStatus.SUCCESS, VerificationStatus.FAILED))) {
            boolean success = v.getStatus() == VerificationStatus.SUCCESS;
            Instant at = success ? v.getVerifiedAt() : v.getShareableAt();
            if (at == null || at.isAfter(now)) continue;   // ← 조기 노출 0건 가드레일
            ChallengeMember member = memberships.get(v.getUserId());
            rows.add(new Row(v.getId(), at, success ? "VERIFY_SUCCESS" : "VERIFY_FAIL", v.getUserId(),
                    success && member != null ? member.getSuccessDays() : null,
                    success ? null : v.getTargetDate().toString()));
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
            // 마스킹은 "가린 모습으로 남기는 것"이지 지우는 것이 아니다. 닉네임을 비우면 클라가 빈 줄을
            // 그리므로 임시 닉네임(계정 id 끝 8자)을 준다 — 프로필 이미지만 기본값(null)으로 떨어뜨린다.
            String nickname = user == null ? null
                    : masked ? user.deriveTempNickname() : user.visibleNicknameTo(viewerId);
            ThreadDtos.User author = new ThreadDtos.User(row.userId().toString(), nickname,
                    user == null || masked ? null : user.visibleProfileImageTo(viewerId), masked);
            if ("VERIFY_FAIL".equals(row.type())) auditFailExposure(challengeId, row);
            return new ThreadDtos.Item(row.type(), row.id().toString(), author, row.at().toString(),
                    row.streak(), row.failDate());
        }).toList();

        return new ThreadDtos.Response(items, next);
    }

    /**
     * 실패 아이템을 실제로 내보낸 사실을 남긴다(백엔드 테크 스펙 4-3 · 7).
     *
     * <p>필터가 맞다는 것과 "한 번도 조기 노출이 없었다"는 것은 다른 주장이다. 후자를 사후에 증명하려면
     * 노출 시각과 공유 가능 시각을 대조할 기록이 있어야 한다 — 일 1회 감사 리포트가 이 줄을 센다.
     * 노출 시각이 shareableAt 보다 앞서는 줄이 하나라도 나오면 가드레일 위반이다.
     */
    private void auditFailExposure(UUID challengeId, Row row) {
        log.info("thread_fail_exposed challengeId={} verificationId={} shareableAt={} exposedAt={}",
                challengeId, row.id(), row.at(), Instant.now());
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
