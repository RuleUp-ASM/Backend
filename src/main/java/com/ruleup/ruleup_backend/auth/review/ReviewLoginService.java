package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.auth.service.TokenService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.sanction.SanctionService;
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.user.UserActivityService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.TempNicknameAllocator;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.service.DeviceSyncPolicyService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 심사 코드 로그인 {@code POST /api/v1/auth/review-login} (심사 계정 테크 스펙 5-2 · 5-4 · 5-5 · 5-7).
 *
 * <p>순서: 스위치(꺼지면 404 — 시도 제한·대조까지 가지 않는다) → IP 시도 제한(429) → 코드 대조(400) →
 * 계정 잠금 · 이번 회차 확인(400) · 정지 게이트(403) · 탈퇴 복원 → 토큰 발급 → <b>스위치 재확인</b>.
 *
 * <h4>발급 뒤 재확인</h4>
 * 스위치 확인을 통과한 요청이 지연돼 꺼진 뒤에 토큰을 만들 수 있다. 발급한 뒤 다시 확인하고, 꺼졌으면 트랜잭션을
 * 롤백해 그 RT 를 남기지 않고 404 로 돌려보낸다. 그러면 전달되는 AT 는 모두 꺼지기 전에 만들어진 것이라 끊김이
 * 「꺼진 시각 + AT 수명」을 넘지 않는다.
 *
 * <h4>기기를 받지 않는다(오픈 이슈 #7)</h4>
 * deviceId·deviceInfo·installationId 는 보내도 무시한다. device_id 가 NULL 이라 sync 의 활성 기기 대조를 건너뛰고,
 * 단일 활성 기기 revoke·세션 종료 알림·설치 인계가 없다 — 심사위원 본인 계정이 같은 기기에 있어도 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReviewLoginService {

    private static final Logger log = LoggerFactory.getLogger(ReviewLoginService.class);

    private final ReviewAccountPolicy policy;
    private final ReviewLoginAttemptLimiter limiter;
    private final UserRepository userRepository;
    private final SanctionService sanctionService;
    private final TokenService tokenService;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final DeviceSyncPolicyService syncPolicy;
    private final UserActivityService activity;
    private final TempNicknameAllocator tempNicknameAllocator;
    private final NotificationPublisher notificationPublisher;
    private final TransactionTemplate transactionTemplate;

    public OAuthLoginResponse login(String code, String clientIp) {
        if (!policy.isSwitchOn()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            limiter.check(clientIp);
        } catch (BusinessException e) {
            log.warn("review_login_failed reason=TOO_MANY_ATTEMPTS ip={}", clientIp);
            throw e;
        }

        // 입력값 원문은 어떤 로그에도 남기지 않는다(5-7).
        ReviewLoginCode matched = policy.match(code).orElse(null);
        if (matched == null) {
            limiter.recordFailure(clientIp);
            log.warn("review_login_failed reason=CODE_MISMATCH ip={}", clientIp);
            throw new BusinessException(ErrorCode.INVALID_REVIEW_CODE);
        }

        OAuthLoginResponse res;
        try {
            res = transactionTemplate.execute(status -> openSession(matched, clientIp));
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_REVIEW_CODE) limiter.recordFailure(clientIp);
            throw e;
        }
        activity.touchNow(matched.userId());   // 커밋 뒤 — 롤백된 로그인을 활동으로 남기지 않는다
        log.info("review_login_succeeded label={} userId={} ip={} restored={}",
                matched.label(), matched.userId(), clientIp, res.restored());
        return res;
    }

    private void refused(ReviewLoginCode matched, String reason, String clientIp) {
        log.warn("review_login_failed reason={} label={} userId={} ip={}", reason, matched.label(), matched.userId(), clientIp);
    }

    private OAuthLoginResponse openSession(ReviewLoginCode matched, String clientIp) {
        UUID userId = matched.userId();
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            refused(matched, "ACCOUNT_MISSING", clientIp);
            throw new BusinessException(ErrorCode.INVALID_REVIEW_CODE);
        }
        // 지난 회차 계정 — 이전 심사위원의 동의·데이터가 남아 있다. 코드가 맞아도 열지 않는다(운영 실수 방어).
        if (!policy.isCurrentRoundAccount(user.getOauthSubject())) {
            refused(matched, "EARLIER_REVIEW_ACCOUNT", clientIp);
            throw new BusinessException(ErrorCode.INVALID_REVIEW_CODE);
        }
        if (sanctionService.isBanActive(userId)) {
            refused(matched, "ACCOUNT_BANNED", clientIp);
            throw new BusinessException(ErrorCode.ACCOUNT_BANNED);
        }

        boolean restored = user.isWithdrawn();
        if (restored) restore(user);
        user.touchLastLogin();
        userRepository.saveAndFlush(user);

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);

        // 발급 뒤 재확인 — 그사이 꺼졌으면 롤백(발급한 RT 가 남지 않는다)하고 꺼진 뒤의 요청과 같은 404.
        if (policy.isCodeSessionEnded(userId)) {
            refused(matched, "SWITCHED_OFF_DURING_LOGIN", clientIp);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        var summary = scoreSummaryRepository.findById(userId).orElse(null);
        var lock = user.isSuspended() ? sanctionService.activeSanction(userId).orElse(null) : null;
        return OAuthLoginResponse.reviewCode(pair, user, summary, syncPolicy.forUser(user), lock, restored);
    }

    /** 소셜 로그인 복원과 같다 — 닉네임 선점 충돌 처리, 탈퇴 직전 상태로 복귀, 제재 해동. 설치 값은 계정의 고정 값 그대로. */
    private void restore(User user) {
        UUID userId = user.getId();
        boolean conflict = userRepository.isNicknameTaken(user.getApprovedNickname(), userId)
                || (user.isNicknamePending() && userRepository.isNicknameTaken(user.getNickname(), userId));
        if (conflict) {
            tempNicknameAllocator.assign(user, value -> userRepository.isNicknameTaken(value, userId));
            user.markNicknameConflict();
            notificationPublisher.publish(NotificationEvent.of(userId, NotificationType.MODERATION_REJECTED,
                    Map.of(NotificationParams.VARIANT, "NICKNAME_TAKEN", NotificationParams.TARGET_KEY, "nickname",
                            NotificationParams.EVENT_KEY, "restore:" + Instant.now())));
        }
        user.restore(user.getInstallationId(), null);
        sanctionService.thawAll(userId, Instant.now());
    }
}
