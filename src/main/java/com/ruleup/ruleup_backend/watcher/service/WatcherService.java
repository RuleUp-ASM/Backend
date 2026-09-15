package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.WatcherInvitation;
import com.ruleup.ruleup_backend.watcher.domain.WatcherRelation;
import com.ruleup.ruleup_backend.watcher.dto.MyWatchingDtos;
import com.ruleup.ruleup_backend.watcher.dto.WatcherListResponse;
import com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only watcher relations and outstanding invitations. */
@Service
@RequiredArgsConstructor
public class WatcherService {

    private final WatcherRelationRepository relationRepository;
    private final WatcherInvitationRepository invitationRepository;
    private final ChallengeQueryService challengeQuery;
    private final UserRepository userRepository;

    // ===== 피감시자 화면 =====

    /**
     * 내가 지정한 감시자와 초대의 상태별 목록.
     *
     * <p>원천이 둘이다. 성립한 관계는 {@code watcher_relations} 에 있지만 <b>아직 수락되지 않은
     * 초대는 관계 행이 없다</b>(누가 수락할지 모르므로 3중 키를 채울 수 없다). 초대장을 보내 둔
     * 사실이 화면에서 사라지면 사용자는 같은 사람에게 몇 번이고 다시 보내게 되므로, 살아 있는
     * 초대를 {@code INVITED} 줄로 함께 내린다.
     *
     * @param statusFilter {@code ACTIVE}(기본) · {@code INVITED} · {@code ALL}
     */
    @Transactional(readOnly = true)
    public WatcherListResponse listWatchers(UUID ownerId, UUID challengeId, String statusFilter) {
        Challenge challenge = challengeQuery.findChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!challenge.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);

        StatusFilter filter = StatusFilter.parse(statusFilter);
        Instant now = Instant.now();

        List<WatcherRelation> relations =
                relationRepository.findByChallengeIdAndTargetUserIdAndRemovedAtIsNull(challengeId, ownerId);
        List<WatcherInvitation> outstanding =
                invitationRepository.findOutstanding(challengeId, ownerId, now);

        List<WatcherListResponse.Item> items = new java.util.ArrayList<>();
        if (filter.includesRelations()) {
            Map<UUID, String> nicknames = nicknamesOf(
                    relations.stream().map(WatcherRelation::getWatcherUserId).toList());
            for (WatcherRelation r : relations) if (r.isDispatchable()) items.add(toItem(r, nicknames));
        }
        if (filter.includesInvitations()) {
            for (WatcherInvitation i : outstanding) items.add(toItem(i));
        }

        return new WatcherListResponse(items);
    }

    private WatcherListResponse.Item toItem(WatcherRelation r, Map<UUID, String> nicknames) {
        return new WatcherListResponse.Item(r.getId().toString(), null, "USER", "IN_APP", "ACTIVE",
                nicknames.getOrDefault(r.getWatcherUserId(), "회원"), r.getInvitedAt().toString(),
                r.getAcceptedAt().toString(), null);
    }
    private WatcherListResponse.Item toItem(WatcherInvitation i) {
        return new WatcherListResponse.Item(null, i.getId().toString(), "USER", null, "INVITED", null,
                i.getExpiresAt().minus(WatcherInvitation.TTL).toString(), null, i.getExpiresAt().toString());
    }

    /** 명세의 {@code status} 쿼리 — 저장 모델에 INVITED 상태가 없어 원천을 가르는 축으로 읽는다. */
    private enum StatusFilter {
        ACTIVE, INVITED, ALL;

        static StatusFilter parse(String raw) {
            if (raw == null || raw.isBlank()) return ACTIVE;
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }

        boolean includesRelations() { return this != INVITED; }

        boolean includesInvitations() { return this != ACTIVE; }
    }

    // ===== 감시자 화면 =====

    /** 내가 감시자로 등록된 관계 — 조회 전용. */
    @Transactional(readOnly = true)
    public MyWatchingDtos.ListResponse listMyWatching(UUID watcherUserId) {
        List<WatcherRelation> relations =
                relationRepository.findByWatcherUserIdAndRemovedAtIsNull(watcherUserId);
        Map<UUID, String> nicknames = nicknamesOf(
                relations.stream().map(WatcherRelation::getTargetUserId).toList());
        Map<UUID, String> titles = titlesOf(
                relations.stream().map(WatcherRelation::getChallengeId).toList());

        return new MyWatchingDtos.ListResponse(relations.stream().filter(WatcherRelation::isDispatchable)
                .map(r -> new MyWatchingDtos.Item(
                        r.getId().toString(),
                        titles.get(r.getChallengeId()),
                        nicknames.getOrDefault(r.getTargetUserId(), "회원"),
                        r.getStatus().name(),
                        r.getAcceptedAt() == null ? null : r.getAcceptedAt().toString()))
                .toList());
    }

    // ===== 내부 =====

    private Map<UUID, String> nicknamesOf(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(u -> u.getId(), u -> u.visibleNicknameTo(null), (a, b) -> a));
    }

    private Map<UUID, String> titlesOf(List<UUID> challengeIds) {
        if (challengeIds.isEmpty()) return Map.of();
        return challengeIds.stream().distinct()
                .map(id -> challengeQuery.findChallenge(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(Challenge::getId, Challenge::publicTitle, (a, b) -> a));
    }
}
