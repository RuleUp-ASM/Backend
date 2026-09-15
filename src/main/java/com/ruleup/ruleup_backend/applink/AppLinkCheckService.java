package com.ruleup.ruleup_backend.applink;

import com.ruleup.ruleup_backend.challenge.domain.InvitationTokens;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeInvitationRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.watcher.infra.WatcherHashes;
import com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 앱링크 유효성 검사 — 형식 · 존재 · 만료 세 단계.
 *
 * <p>이 API 의 값은 <b>순서</b>에 있다. 클라가 화면을 그리기 전에 부르므로, 위조·만료된 링크로 상세
 * 화면까지 들어갔다가 에러를 만나는 대신 진입 시점에 걸러 안내 화면으로 보낼 수 있다.
 *
 * <p>여기서 통과해도 <b>진입 가능 여부는 별개</b>다. 정원이 찼는지, 재입장 대기 중인지 같은 판정은
 * 각 타입의 조회·수락 API 가 한다 — 이 API 는 링크 자체의 유효성만 본다. 그 경계를 흐리면
 * "링크는 유효한데 못 들어가는" 상태를 여기서 다시 표현해야 하고, 두 곳의 판정이 어긋나기 시작한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppLinkCheckService {

    private final AppLinks appLinks;
    private final ChallengeInvitationRepository challengeInvitationRepository;
    private final WatcherInvitationRepository watcherInvitationRepository;
    private final com.ruleup.ruleup_backend.watcher.infra.Tokens watcherTokens;
    private final com.ruleup.ruleup_backend.invitation.InviteCodeRepository inviteCodeRepository;

    public AppLinkCheckDtos.Response check(String url) {
        if (url == null || url.isBlank()) throw new BusinessException(ErrorCode.APP_LINK_URL_REQUIRED);

        AppLinks.Parsed parsed = appLinks.parse(url);
        if (!parsed.isWellFormed())
            return AppLinkCheckDtos.Response.invalid(null, null, parsed.failure(), null);

        Target target = targetOf(parsed);
        if (!target.exists())
            return AppLinkCheckDtos.Response.invalid(parsed.type(), parsed.token(),
                    AppLinkCheckReason.NOT_FOUND, null);

        Instant expiry = target.expiresAt();
        if (expiry != null && !expiry.isAfter(Instant.now()))
            return AppLinkCheckDtos.Response.invalid(parsed.type(), parsed.token(),
                    AppLinkCheckReason.EXPIRED, expiry.toString());

        return AppLinkCheckDtos.Response.valid(parsed.type(), parsed.token());
    }

    /**
     * 링크가 가리키는 대상.
     *
     * <p><b>존재와 만료를 따로 담는다.</b> 예전에는 만료 시각 하나로 둘을 겸했는데, 그러면 만료가
     * 없는 링크를 「대상 없음」과 구분할 수 없다 — 친구 초대 코드가 그런 경우다.
     *
     * @param exists    대상을 찾았는가
     * @param expiresAt 만료 시각. {@code null} 이면 만료가 없는 링크다
     */
    private record Target(boolean exists, Instant expiresAt) {
        static Target none() { return new Target(false, null); }
        static Target neverExpires() { return new Target(true, null); }
        static Target expiring(Instant at) { return new Target(true, at); }
        static Target of(Optional<Instant> expiry) {
            return expiry.map(Target::expiring).orElseGet(Target::none);
        }
    }

    /**
     * 대상을 찾는다.
     *
     * <p>토큰 원본은 어디에도 저장돼 있지 않으므로 각 타입의 해시 규칙으로 다시 해싱해 찾는다 —
     * DB 를 읽을 수 있게 된 사람이 곧바로 남의 방에 들어갈 수 없게 하려는 설계다.
     * 챌린지는 SHA-256 바이트, 감시자는 같은 해시의 hex 문자열로 저장한다.
     *
     * <p>친구 초대만 해싱하지 않는다. 그 값은 비밀이 아니라 <b>사람이 읽고 옮겨 적는 6자리 코드</b>라
     * 가입 화면에 직접 입력하는 경로도 있다. 알아내도 얻는 것이 남의 가입에 내 코드를 붙이는 일뿐이다.
     */
    private Target targetOf(AppLinks.Parsed parsed) {
        return switch (parsed.type()) {
            case CHALLENGE_INVITATION -> Target.of(challengeInvitationRepository
                    .findByTokenHash(InvitationTokens.hash(parsed.token()))
                    .map(i -> i.getExpiresAt()));
            case WATCHER_INVITATION -> Target.of(watcherExpiry(parsed.token()));
            case FRIEND_INVITATION -> inviteCodeRepository.existsByCode(parsed.token())
                    ? Target.neverExpires() : Target.none();
        };
    }
    private Optional<Instant> watcherExpiry(String token) {
        try {
            var claims = watcherTokens.verify(token);
            return watcherInvitationRepository.findByTokenHash(WatcherHashes.sha256Hex(token))
                    .filter(i -> i.getChallengeId().equals(claims.challengeId()) && i.getInviterUserId().equals(claims.inviterId()))
                    .map(i -> i.getExpiresAt().isBefore(claims.expiresAt()) ? i.getExpiresAt() : claims.expiresAt());
        } catch (BusinessException e) { return Optional.empty(); }
    }
}
