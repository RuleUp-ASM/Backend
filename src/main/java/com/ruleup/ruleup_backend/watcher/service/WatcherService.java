package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.ConsentEvent;
import com.ruleup.ruleup_backend.watcher.domain.WatcherConsentLog;
import com.ruleup.ruleup_backend.watcher.domain.WatcherInvitation;
import com.ruleup.ruleup_backend.watcher.domain.WatcherRelation;
import com.ruleup.ruleup_backend.watcher.domain.WatcherSlots;
import com.ruleup.ruleup_backend.watcher.dto.MyWatchingDtos;
import com.ruleup.ruleup_backend.watcher.dto.WatcherListResponse;
import com.ruleup.ruleup_backend.watcher.repository.WatcherConsentLogRepository;
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
    private final WatcherInvitationRepository invitationRepository;
    private final WatcherConsentLogRepository consentLogRepository;
    private final ChallengeQueryService challengeQuery;
    private final UserRepository userRepository;

    // ===== 피감시자 화면 =====

    /**
     * 내가 지정한 감시자 목록 — 슬롯 현황 + 상태별 목록.
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
            for (WatcherRelation r : relations) items.add(toItem(r, nicknames));
        }
        if (filter.includesInvitations()) {
            for (WatcherInvitation i : outstanding) items.add(toItem(i));
        }

        // 슬롯은 필터와 무관하다 — 목록을 좁혀도 숫자는 그대로여야 한다. 세는 대상은 실제
        // 감시자(살아 있는 관계)뿐이며, 미수락 초대는 자리를 잠그지 않는다.
        return new WatcherListResponse(slots(relations.size()), items);
    }

    /** 슬롯 현황. 구독 도메인이 없어 {@code subscribed} 는 아직 상수 false 다. */
    private WatcherListResponse.Slots slots(int used) {
        boolean subscribed = false;
        return new WatcherListResponse.Slots(used, WatcherSlots.freeLimitFor(subscribed), subscribed);
    }

    /** 성립한 관계 한 줄. 채널이 IN_APP 뿐인 것은 SMS·이메일이 정책상 폐지됐기 때문이다. */
    private WatcherListResponse.Item toItem(WatcherRelation r, Map<UUID, String> nicknames) {
        return new WatcherListResponse.Item(
                r.getId().toString(), "USER", "IN_APP", r.getStatus().name(),
                nicknames.getOrDefault(r.getWatcherUserId(), "회원"),
                null,                                    // 연락처를 수집하지 않는다
                r.getInvitedAt() == null ? null : r.getInvitedAt().toString(),
                null,                                    // 성립한 관계에는 만료가 없다
                null);                                   // 해제 개념이 없어 재초대 대기도 없다
    }

    /**
     * 미수락 초대 한 줄. {@code watcherId} 가 초대 id 인 것은 <b>아직 감시자가 정해지지 않았기</b>
     * 때문이다 — 이 줄이 가리키는 것은 사람이 아니라 살아 있는 초대장이다.
     */
    private WatcherListResponse.Item toItem(WatcherInvitation i) {
        return new WatcherListResponse.Item(
                i.getId().toString(), "USER", null, "INVITED",
                null, null,
                i.getExpiresAt().minus(WatcherInvitation.TTL).toString(),
                i.getExpiresAt().toString(),
                null);
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
