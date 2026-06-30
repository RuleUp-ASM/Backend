package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.watcher.domain.Watcher;
import com.ruleup.ruleup_backend.watcher.domain.WatcherInvitation;
import com.ruleup.ruleup_backend.watcher.domain.WatcherStatus;
import com.ruleup.ruleup_backend.watcher.dto.WatcherListResponse;
import com.ruleup.ruleup_backend.watcher.dto.WatcherRevokeResponse;
import com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 감시자 목록·해제 (생성자 전용). 비유저 연락처는 마스킹만 노출(§5.9).
 */
@Service
@RequiredArgsConstructor
public class WatcherService {

    private static final int FREE_LIMIT = 3;

    private final WatcherRepository watcherRepository;
    private final WatcherInvitationRepository invitationRepository;
    private final ChallengeQueryService challengeQuery;

    // ===== 목록 (생성자) =====
    @Transactional(readOnly = true)
    public WatcherListResponse list(UUID ownerId, UUID challengeId, String statusFilter) {
        ensureOwner(ownerId, challengeId);

        String filter = (statusFilter == null || statusFilter.isBlank()) ? "ACTIVE" : statusFilter.toUpperCase();
        List<Watcher> watchers = switch (filter) {
            case "ALL"     -> watcherRepository.findByChallengeIdOrderByInvitedAtAsc(challengeId);
            case "INVITED" -> watcherRepository.findByChallengeIdAndStatusOrderByInvitedAtAsc(challengeId, WatcherStatus.INVITED);
            case "ACTIVE"  -> watcherRepository.findByChallengeIdAndStatusOrderByInvitedAtAsc(challengeId, WatcherStatus.ACTIVE);
            default        -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        };

        // INVITED 항목 expiresAt = 초대 만료시각. N+1 방지로 일괄 매핑.
        Map<UUID, Instant> expiryByInvitation = invitationRepository.findByChallengeId(challengeId).stream()
                .collect(Collectors.toMap(WatcherInvitation::getId, WatcherInvitation::getExpiresAt, (a, b) -> a));

        List<WatcherListResponse.Item> items = watchers.stream().map(w -> {
            Instant expiry = (w.getStatus() == WatcherStatus.INVITED)
                    ? expiryByInvitation.get(w.getInvitationId()) : null;
            return new WatcherListResponse.Item(
                    w.getId().toString(),
                    w.getType() != null ? w.getType().name() : null,
                    w.getChannel() != null ? w.getChannel().name() : null,
                    w.getStatus().name(),
                    w.getDisplayName(),
                    w.getContactMasked(),
                    w.getInvitedAt() != null ? w.getInvitedAt().toString() : null,
                    expiry != null ? expiry.toString() : null);
        }).toList();

        return new WatcherListResponse(challengeId.toString(), FREE_LIMIT, items);
    }

    // ===== 해제 (생성자) =====
    @Transactional
    public WatcherRevokeResponse revoke(UUID ownerId, UUID challengeId, UUID watcherId) {
        ensureOwner(ownerId, challengeId);

        Watcher watcher = watcherRepository.findById(watcherId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WATCHER_NOT_FOUND));
        if (!watcher.getChallengeId().equals(challengeId))
            throw new BusinessException(ErrorCode.WATCHER_NOT_FOUND);

        // 생성자 해제는 REVOKED + 연락처 파기(수신거부와 달리 30일 차단은 만들지 않는다 — §5.9).
        watcher.revoke(Instant.now());
        invitationRepository.findById(watcher.getInvitationId()).ifPresent(WatcherInvitation::markRevoked);

        return new WatcherRevokeResponse(WatcherStatus.REVOKED.name());
    }

    private void ensureOwner(UUID ownerId, UUID challengeId) {
        Challenge c = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
    }
}
