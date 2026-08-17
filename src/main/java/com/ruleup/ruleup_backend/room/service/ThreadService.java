package com.ruleup.ruleup_backend.room.service;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
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
 *
 * <p>페이징은 노출 시각과 id의 복합 seek 커서를 SQL에 적용하고 {@code size+1}건만 읽는다.
 * 커서는 base64url 인코딩되어 내부 정렬 키를 클라이언트 계약에서 감춘다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThreadService {
    private final RoomAuthority authority;
    private final VerificationDailyRepository verificationRepository;
    private final UserRepository userRepository;
    private final BlacklistService blacklistService;

    private record Cursor(Instant at, UUID id) {}
    private record UserDate(UUID userId, java.time.LocalDate targetDate) {}

    public ThreadDtos.Response get(UUID viewerId, UUID challengeId, String cursor, Integer requestedSize) {
        authority.requireMember(challengeId, viewerId);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        Instant now = Instant.now();
        Set<UUID> blocked = blacklistService.blockedUsers(viewerId);

        Cursor decoded = decode(cursor);
        List<VerificationDaily> fetched = decoded == null
                ? verificationRepository.findThreadFirstPage(challengeId, now, PageRequest.of(0, size + 1))
                : verificationRepository.findThreadNextPage(challengeId, now, decoded.at(), decoded.id(),
                        PageRequest.of(0, size + 1));
        boolean hasNext = fetched.size() > size;
        List<VerificationDaily> page = hasNext ? fetched.subList(0, size) : fetched;
        Map<UserDate, Integer> streaks = streaksAt(challengeId, page);

        Map<UUID, User> users = userRepository.findAllById(page.stream().map(VerificationDaily::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<ThreadDtos.Item> items = page.stream().map(row -> {
            boolean success = row.getStatus() == VerificationStatus.SUCCESS;
            Instant at = eventAt(row);
            User user = users.get(row.getUserId());
            boolean masked = blocked.contains(row.getUserId());
            // 마스킹은 "가린 모습으로 남기는 것"이지 지우는 것이 아니다. 닉네임을 비우면 클라가 빈 줄을
            // 그리므로 임시 닉네임(계정 id 끝 8자)을 준다 — 프로필 이미지만 기본값(null)으로 떨어뜨린다.
            String nickname = user == null ? null
                    : masked ? user.deriveTempNickname() : user.visibleNicknameTo(viewerId);
            ThreadDtos.User author = new ThreadDtos.User(row.getUserId().toString(), nickname,
                    user == null || masked ? null : user.visibleProfileImageTo(viewerId), masked);
            return new ThreadDtos.Item(success ? "VERIFY_SUCCESS" : "VERIFY_FAIL", row.getId().toString(),
                    author, at.toString(), success ? streaks.get(new UserDate(row.getUserId(), row.getTargetDate())) : null,
                    success ? null : row.getTargetDate().toString());
        }).toList();

        String next = hasNext && !page.isEmpty()
                ? encode(new Cursor(eventAt(page.get(page.size() - 1)), page.get(page.size() - 1).getId())) : null;
        return new ThreadDtos.Response(null, items, next);
    }

    private Map<UserDate, Integer> streaksAt(UUID challengeId, List<VerificationDaily> page) {
        List<UUID> userIds = page.stream().filter(v -> v.getStatus() == VerificationStatus.SUCCESS)
                .map(VerificationDaily::getUserId).distinct().toList();
        if (userIds.isEmpty()) return Map.of();
        Map<UserDate, Integer> result = new HashMap<>();
        Map<UUID, Integer> current = new HashMap<>();
        for (VerificationDaily v : verificationRepository
                .findByChallengeIdAndUserIdInAndStatusInOrderByUserIdAscTargetDateAsc(
                        challengeId, userIds, List.of(VerificationStatus.SUCCESS, VerificationStatus.FAILED))) {
            int streak = v.getStatus() == VerificationStatus.SUCCESS
                    ? current.getOrDefault(v.getUserId(), 0) + 1 : 0;
            current.put(v.getUserId(), streak);
            if (v.getStatus() == VerificationStatus.SUCCESS)
                result.put(new UserDate(v.getUserId(), v.getTargetDate()), streak);
        }
        return result;
    }

    private Instant eventAt(VerificationDaily row) {
        return row.getStatus() == VerificationStatus.SUCCESS ? row.getVerifiedAt() : row.getShareableAt();
    }

    private Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf('|');
            if (split <= 0) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(decoded.substring(0, split)), UUID.fromString(decoded.substring(split + 1)));
        } catch (RuntimeException e) { throw new BusinessException(ErrorCode.CURSOR_INVALID); }
    }

    private String encode(Cursor cursor) {
        String raw = cursor.at() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
