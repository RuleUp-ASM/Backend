package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.ConsentEvent;
import com.ruleup.ruleup_backend.watcher.domain.WatcherConsentLog;
import com.ruleup.ruleup_backend.watcher.domain.WatcherRelation;
import com.ruleup.ruleup_backend.watcher.dto.MyWatchingDtos;
import com.ruleup.ruleup_backend.watcher.dto.WatcherListResponse;
import com.ruleup.ruleup_backend.watcher.repository.WatcherConsentLogRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 감시 관계 조회와 수신 토글.
 *
 * <p><b>해제 경로가 없다.</b> 관계를 끊는 기능은 정책상 폐지됐고, 루틴 종료 시 배치가 자동으로
 * 제거한다. 사용자가 지금 통지를 멈추려면 토글을 끄면 되고, 그 시각은 동의 이력에 남는다.
 */
@Service
@RequiredArgsConstructor
public class WatcherService {

    private final WatcherRelationRepository relationRepository;
    private final WatcherConsentLogRepository consentLogRepository;
    private final ChallengeQueryService challengeQuery;
    private final UserRepository userRepository;

    // ===== 피감시자 화면 =====

    /** 내가 지정한 감시자 목록 — 상태와 수락 여부. */
    @Transactional(readOnly = true)
    public WatcherListResponse listWatchers(UUID ownerId, UUID challengeId) {
        Challenge challenge = challengeQuery.findChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!challenge.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);

        List<WatcherRelation> relations =
                relationRepository.findByChallengeIdAndTargetUserIdAndRemovedAtIsNull(challengeId, ownerId);
        Map<UUID, String> nicknames = nicknamesOf(
                relations.stream().map(WatcherRelation::getWatcherUserId).toList());

        return new WatcherListResponse(relations.stream()
                .map(r -> new WatcherListResponse.Item(
                        r.getId().toString(),
                        nicknames.getOrDefault(r.getWatcherUserId(), "회원"),
                        r.getStatus().name(),
                        r.getAcceptedAt() == null ? null : r.getAcceptedAt().toString()))
                .toList());
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

        return new MyWatchingDtos.ListResponse(relations.stream()
                .map(r -> new MyWatchingDtos.Item(
                        r.getId().toString(),
                        titles.get(r.getChallengeId()),
                        nicknames.getOrDefault(r.getTargetUserId(), "회원"),
                        r.getStatus().name(),
                        r.isPushEnabled(),
                        r.getAcceptedAt() == null ? null : r.getAcceptedAt().toString()))
                .toList());
    }

    /**
     * 수신 토글. 관계는 그대로 두고 통지만 닫으며, <b>알림함 적재는 유지</b>된다.
     * OFF 시각을 동의 이력에 남기는 이유는 "언제부터 받지 않겠다고 했는지"가 분쟁의 근거이기 때문이다.
     */
    @Transactional
    public MyWatchingDtos.PatchResponse togglePush(UUID watcherUserId, UUID relationId,
                                                   MyWatchingDtos.PatchRequest request) {
        if (request == null || request.pushEnabled() == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        // 남의 관계는 404 로 존재를 숨긴다 — 관계 ID 로 타인의 감시 사실을 확인할 수 없게 한다.
        WatcherRelation relation = relationRepository
                .findByIdAndWatcherUserId(relationId, watcherUserId)
                .filter(r -> r.getRemovedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.WATCHER_NOT_FOUND));

        boolean enabled = request.pushEnabled();
        if (relation.isPushEnabled() != enabled) {
            relation.togglePush(enabled);
            if (!enabled) consentLogRepository.save(WatcherConsentLog.of(
                    relation.getId(), ConsentEvent.TOGGLE_OFF, Instant.now()));
        }
        return new MyWatchingDtos.PatchResponse(
                relation.getId().toString(), relation.getStatus().name(), enabled, true);
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
