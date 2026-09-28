package com.ruleup.ruleup_backend.applink;

import com.ruleup.ruleup_backend.challenge.domain.InvitationTokens;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeInvitationRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.invitation.InviteCodeRepository;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.infra.WatcherHashes;
import com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 앱이 없던 사람이 초대 링크로 설치·가입했을 때 그 초대를 이어 준다(09-28 결정, QA NAV-06).
 *
 * <p>설치 경로는 스토어를 거치므로 링크가 앱에 바로 닿지 않는다. 앱은 Install Referrer 로 받은 링크를
 * 가입 요청({@code inviteLink})에 싣고, 가입 화면 흐름은 일반 가입과 같다 — 그래서 받은 초대로 돌아갈
 * 자리를 <b>알림함</b>에 만든다. 알림을 누르면 링크를 다시 탄 것처럼 초대 화면이 열린다.
 *
 * <p><b>가입을 막지 않는다.</b> 링크가 위조·만료·소모됐으면 조용히 넘어간다 — 초대는 덤이고, 초대가
 * 잘못됐다고 가입이 실패하면 설치한 사람은 원인을 알 수 없다. 존재·만료 판정은
 * {@link AppLinkCheckService} 와 같은 규칙을 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstallReferrerInvitation {

    private final AppLinks appLinks;
    private final ChallengeInvitationRepository challengeInvitationRepository;
    private final ChallengeRepository challengeRepository;
    private final WatcherInvitationRepository watcherInvitationRepository;
    private final com.ruleup.ruleup_backend.watcher.infra.Tokens watcherTokens;
    private final InviteCodeRepository inviteCodeRepository;
    private final UserRepository userRepository;
    private final NotificationPublisher notificationPublisher;

    /** 링크가 친구 초대면 그 코드 — 가입 요청에 {@code inviteCode} 가 없을 때 초대 기록 연동에 쓴다. */
    public Optional<String> friendCode(String inviteLink) {
        return parse(inviteLink)
                .filter(p -> p.type() == AppLinkType.FRIEND_INVITATION)
                .map(AppLinks.Parsed::token);
    }

    /** 가입 트랜잭션 안에서 부른다. 유효한 초대면 알림함에 한 건 남긴다. */
    public void notifyReceived(UUID newUserId, String inviteLink) {
        Optional<AppLinks.Parsed> parsed = parse(inviteLink);
        if (parsed.isEmpty()) return;
        AppLinkType type = parsed.get().type();
        String token = parsed.get().token();

        Optional<Map<String, String>> params = switch (type) {
            case CHALLENGE_INVITATION -> challengeParams(token);
            case WATCHER_INVITATION -> watcherParams(token, newUserId);
            case FRIEND_INVITATION -> friendParams(token, newUserId);
        };
        if (params.isEmpty()) {
            log.info("install_referrer_invite_ignored userId={} type={}", newUserId, type);
            return;
        }
        Map<String, String> p = new java.util.HashMap<>(params.get());
        p.put(NotificationParams.EVENT_KEY, "signup-invite:" + newUserId);
        notificationPublisher.publish(NotificationEvent.of(newUserId, NotificationType.INVITATION_RECEIVED, p)
                .withDeeplink(appLinks.buildScheme(type, token)));
    }

    private Optional<AppLinks.Parsed> parse(String inviteLink) {
        if (inviteLink == null || inviteLink.isBlank()) return Optional.empty();
        AppLinks.Parsed parsed = appLinks.parse(inviteLink);
        return parsed.isWellFormed() ? Optional.of(parsed) : Optional.empty();
    }

    /** 만료·소모되지 않은 챌린지 초대 — 방 제목은 공개 제목으로 싣는다(잠금화면에 뜨는 문장이다). */
    private Optional<Map<String, String>> challengeParams(String token) {
        Instant now = Instant.now();
        return challengeInvitationRepository.findByTokenHash(InvitationTokens.hash(token))
                .filter(i -> i.getUsedAt() == null && i.getExpiresAt().isAfter(now))
                .flatMap(i -> challengeRepository.findByIdAndDeletedAtIsNull(i.getChallengeId()))
                .map(c -> Map.of(NotificationParams.VARIANT, "CHALLENGE",
                        NotificationParams.CHALLENGE_TITLE, c.publicTitle()));
    }

    /** 수락 전·만료 전 감시자 초대. 토큰 서명과 저장 행이 서로 맞아야 한다(링크 검사와 같은 규칙). */
    private Optional<Map<String, String>> watcherParams(String token, UUID newUserId) {
        try {
            var claims = watcherTokens.verify(token);
            Instant now = Instant.now();
            return watcherInvitationRepository.findByTokenHash(WatcherHashes.sha256Hex(token))
                    .filter(i -> i.getChallengeId().equals(claims.challengeId())
                            && i.getInviterUserId().equals(claims.inviterId()))
                    .filter(i -> i.getAcceptedAt() == null && !i.isExpired(now) && claims.expiresAt().isAfter(now))
                    .flatMap(i -> nicknameOf(i.getInviterUserId(), newUserId))
                    .map(name -> Map.of(NotificationParams.VARIANT, "WATCHER", NotificationParams.ACTOR_NAME, name));
        } catch (BusinessException invalid) {
            return Optional.empty();
        }
    }

    /** 친구 초대 — 코드 주인이 곧 초대한 사람이다. 자기 코드는 초대가 아니다. */
    private Optional<Map<String, String>> friendParams(String code, UUID newUserId) {
        return inviteCodeRepository.findByCode(code.trim().toUpperCase())
                .filter(c -> !c.getUserId().equals(newUserId))
                .flatMap(c -> nicknameOf(c.getUserId(), newUserId))
                .map(name -> Map.of(NotificationParams.VARIANT, "FRIEND", NotificationParams.ACTOR_NAME, name));
    }

    private Optional<String> nicknameOf(UUID userId, UUID viewerId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId).map(u -> u.visibleNicknameTo(viewerId));
    }
}
