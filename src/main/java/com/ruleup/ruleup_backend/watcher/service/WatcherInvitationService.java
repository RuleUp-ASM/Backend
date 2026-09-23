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
        // 상태는 <b>행에서</b> 읽는다. "INVITED" 를 박아 두면 이미 수락된 초대도 계속 수락 가능한 것처럼 보인다(QA WAT-06).
        String status = (i.getAcceptedAt() != null) ? "ACCEPTED" : "INVITED";
        return new InvitationEntryResponse(i.getId().toString(), status, c.publicTitle(), nickname(i.getInviterUserId()),
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
        // 같은 사람의 재수락은 위에서 ALREADY_WATCHER 로 끝난다 — 여기 걸리는 건 <b>남의 초대장</b>이다.
        requireUnused(i);
        Instant now = Instant.now();
        // <b>초대장 하나는 한 사람만 쓴다.</b> 조건부 UPDATE(acceptedAt IS NULL)의 결과를 버리고 있어서,
        // 카카오톡으로 전달된 링크를 여러 계정이 차례로 수락하면 전부 감시자가 됐다(QA WAT-06).
        // 관계를 만들기 <b>전에</b> 선점해야 동시 요청에서도 한 명만 통과한다.
        if (invitationRepository.acceptOnce(i.getId(), now) == 0) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_ACCEPTED);
        }
        if (r == null) r = relationRepository.save(WatcherRelation.accepted(c.getId(), i.getInviterUserId(), watcherId, i.getExpiresAt().minus(WatcherInvitation.TTL), now));
        else r.accept(now);
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
    /** 이미 누군가 수락한 초대장인가 — 수락 경로에서만 막는다(조회는 상태를 보여 주는 것이 낫다). */
    private static void requireUnused(WatcherInvitation i) {
        if (i.getAcceptedAt() != null) throw new BusinessException(ErrorCode.INVITATION_ALREADY_ACCEPTED);
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
