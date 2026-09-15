package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.applink.*;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.*;
import com.ruleup.ruleup_backend.report.BlockService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.*;
import com.ruleup.ruleup_backend.watcher.dto.*;
import com.ruleup.ruleup_backend.watcher.infra.*;
import com.ruleup.ruleup_backend.watcher.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WatcherInvitationService {
    private final WatcherInvitationRepository invitationRepository;
    private final WatcherRelationRepository relationRepository;
    private final ChallengeRepository challenges;
    private final UserRepository users;
    private final BlockService blocks;
    private final AppLinks links;
    private final Tokens tokens;
    private final WatcherAudit audit;

    @Transactional
    public InvitationCreateResponse createInvitation(UUID ownerId, UUID challengeId) {
        Challenge c = liveChallenge(challengeId);
        if (!c.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
        requirePenalty(c);
        Instant now = Instant.now();
        String token = tokens.issue(challengeId, ownerId, now.plus(WatcherInvitation.TTL));
        WatcherInvitation i = invitationRepository.save(WatcherInvitation.issue(challengeId, ownerId, WatcherHashes.sha256Hex(token), now));
        String nickname = nickname(ownerId);
        audit.afterCommit("WATCHER_INVITED", i.getId(), ownerId, null, "INVITED", null);
        return new InvitationCreateResponse(i.getId().toString(), token,
                links.build(AppLinkType.WATCHER_INVITATION, token), "INVITED", i.getExpiresAt().toString(),
                new InvitationCreateResponse.KakaoShare(nickname + "님이 당신을 루틴 감시자로 초대했어요",
                        "[" + c.publicTitle() + "]에서 " + nickname + "님의 실패가 확정되면 알림이 가요.", "수락하기"));
    }
    @Transactional(readOnly = true)
    public InvitationEntryResponse getByToken(String token) {
        WatcherInvitation i = invitation(token);
        Challenge c = challenges.findById(i.getChallengeId()).orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));
        return new InvitationEntryResponse(i.getId().toString(), "INVITED", c.publicTitle(), nickname(i.getInviterUserId()),
                true, "ruleup://watchers/invitations/" + token + "/accept", i.getExpiresAt().toString(), WatcherRelation.CONSENT_VERSION);
    }
    @Transactional
    public WatcherAcceptResponse accept(String token, UUID watcherId) {
        WatcherInvitation i = invitation(token);
        Challenge c = liveChallenge(i.getChallengeId());
        requirePenalty(c);
        if (i.isExpired(Instant.now())) throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        if (!c.isOwner(i.getInviterUserId())) throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        if (i.getInviterUserId().equals(watcherId)) throw new BusinessException(ErrorCode.CANNOT_WATCH_SELF);
        users.findByIdForUpdate(watcherId).orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        if (blocks.isUserBlocked(watcherId, i.getInviterUserId())) throw new BusinessException(ErrorCode.WATCHER_BLOCKED);
        WatcherRelation r = relationRepository.findByChallengeIdAndTargetUserIdAndWatcherUserId(c.getId(), i.getInviterUserId(), watcherId).orElse(null);
        if (r != null && (r.isDispatchable() || r.getRemovedAt() != null)) throw new BusinessException(ErrorCode.ALREADY_WATCHER);
        Instant now = Instant.now();
        if (r == null) r = relationRepository.save(WatcherRelation.accepted(c.getId(), i.getInviterUserId(), watcherId, i.getExpiresAt().minus(WatcherInvitation.TTL), now));
        else r.accept(now);
        invitationRepository.acceptOnce(i.getId(), now);
        audit.afterCommit("WATCHER_ACCEPTED", r.getId(), watcherId, "PENDING", "ACTIVE", r.getConsentVersion());
        return new WatcherAcceptResponse(r.getId().toString(), "ACTIVE", "IN_APP", now.toString());
    }
    private WatcherInvitation invitation(String token) {
        Tokens.Claims claims = tokens.verify(token);
        WatcherInvitation i = invitationRepository.findByTokenHash(WatcherHashes.sha256Hex(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));
        if (!claims.challengeId().equals(i.getChallengeId()) || !claims.inviterId().equals(i.getInviterUserId()))
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        Instant now = Instant.now();
        if (!claims.expiresAt().isAfter(now) || i.isExpired(now)) throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        return i;
    }
    private Challenge liveChallenge(UUID id) {
        Challenge c = challenges.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (c.getDeletedAt() != null || c.getStatus() == ChallengeStatus.COMPLETED)
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        return c;
    }
    private void requirePenalty(Challenge c) {
        if (c.getPenalties() == null || !c.getPenalties().watcher()) throw new BusinessException(ErrorCode.WATCHER_PENALTY_DISABLED);
    }
    private String nickname(UUID id) { return users.findById(id).map(u -> u.visibleNicknameTo(null)).orElse("회원"); }
}
