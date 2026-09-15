package com.ruleup.ruleup_backend.auth.service;

import com.ruleup.ruleup_backend.auth.repository.RefreshTokenRepository;

import com.ruleup.ruleup_backend.auth.dto.DeviceInfoRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.web.CountryResolver;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.push.repository.DeviceTokenRepository;
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.service.FlushIntervalPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 기존 회원 로그인의 DB 구간 (짧은 트랜잭션 — 외부 IdP 호출은 AuthService가 트랜잭션 밖에서 완료).
 * 처리 순서 (테크 스펙 4-3 · DB 정리 §9):
 *  1) 상태 분기 — BANNED 403 / LOCKED 열람 전용 허용
 *  2) 단일 활성 기기 — deviceId 가 다르면 기존 RT 전부 revoke + 기존 기기에 세션 종료 알림
 *  3) 설치 인계 — 다른 계정이 점유한 installationId 면 그 계정의 연결 해제 + 세션 종료
 *  4) 기기 정보·국가·last_login/active 갱신, IdP 토큰 upsert, 토큰 페어 발급
 */
@Service
@RequiredArgsConstructor
public class LoginSessionService {

    private final com.ruleup.ruleup_backend.verification.service.DeviceSyncPolicyService syncPolicy;
    private final UserRepository userRepository;
    private final com.ruleup.ruleup_backend.user.UserActivityService activity;
    private final com.ruleup.ruleup_backend.sanction.SanctionService sanctionService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final SocialTokenService socialTokenService;
    private final NotificationPublisher notificationPublisher;
    private final DeviceTokenRepository deviceTokenRepository;
    private final CountryResolver countryResolver;
    private final TokenService tokenService;
    private final com.ruleup.ruleup_backend.user.domain.TempNicknameAllocator tempNicknameAllocator;

    @Transactional
    public OAuthLoginResponse loginExisting(UUID userId, OAuthProvider provider,
                                            OAuthLoginRequest req, OAuthUserInfo info) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> BusinessException.withMessage(ErrorCode.LOGIN_FAILED, "ACCOUNT_NOT_FOUND",
                        "로그인 정보를 확인하지 못했어요. 처음부터 다시 로그인해주세요."));

        // 영구 정지 계정은 로그인 자체를 차단한다.
        if (sanctionService.isBanActive(user.getId()))
            throw new BusinessException(ErrorCode.ACCOUNT_BANNED);

        boolean restored = user.isWithdrawn();
        if (restored) {
            boolean conflict = userRepository.isNicknameTaken(user.getApprovedNickname(), userId)
                    || (user.isNicknamePending() && userRepository.isNicknameTaken(user.getNickname(), userId));
            if (conflict) {
                tempNicknameAllocator.assign(user, value -> userRepository.isNicknameTaken(value, userId));
                user.markNicknameConflict();
                notificationPublisher.publish(NotificationEvent.of(userId, NotificationType.MODERATION_REJECTED,
                        Map.of(NotificationParams.VARIANT, "NICKNAME_TAKEN", NotificationParams.TARGET_KEY, "nickname",
                                NotificationParams.EVENT_KEY, "restore:" + Instant.now())));
            }
            // 같은 계정의 개인정보·점수·이력을 유지하고 제재 잔여 기간을 해동한다.
        }

        // ===== 단일 활성 기기 — 다른 기기 로그인이면 기존 세션 전부 종료 =====
        boolean deviceChanged = user.getDeviceId() != null && req.deviceId() != null
                && !req.deviceId().equals(user.getDeviceId());
        if (deviceChanged) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
            // 지금 활성인 토큰이 곧 <b>이전 기기</b>의 것이다. 새 기기는 로그인 직후 별도 호출로
            // 토큰을 올리므로 아직 등록돼 있지 않다 — 이 시점을 놓치면 대상이 사라지고, 고지가
            // 새 기기로 가거나 양쪽에 뜬다. 발송 시점에는 조회로 찾을 수 없다(이미 비활성).
            String previousDevice = deviceTokenRepository.findActiveTokensOf(user.getId())
                    .stream().findFirst().orElse(null);
            // 계정 보안 고지지만 야간 예외는 없다 — 절대 규칙 2 는 강퇴·계정 잠금에도 예외를
            // 두지 않는다. 야간(21:00~08:00)에 일어나면 적재만 되고 푸시는 08:00 에 나간다.
            // 고지 성립 시각은 적재 시각이므로 그것으로 충분하다.
            NotificationEvent loggedOut = NotificationEvent.of(user.getId(),
                    NotificationType.DEVICE_LOGGED_OUT,
                    // 새로 로그인한 기기 id 가 이 사건을 유일하게 가리킨다.
                    Map.of(NotificationParams.EVENT_KEY, req.deviceId()));
            notificationPublisher.publish(previousDevice == null
                    ? loggedOut : loggedOut.withTargetToken(previousDevice));
        }

        // ===== 설치 인계 — uq_users_active_installation_id: 하나의 설치는 한 활성 계정에만 연결 =====
        // 탈퇴 행도 installation_id 를 들고 있으므로(승계 근거) 활성 계정만 인계 대상으로 본다.
        if (req.installationId() != null && !req.installationId().isBlank()) {
            userRepository.findActiveHolderOfInstallation(req.installationId())
                    .filter(holder -> !holder.getId().equals(user.getId()))
                    .ifPresent(holder -> {
                        holder.detachInstallation();
                        refreshTokenRepository.revokeAllByUserId(holder.getId(), Instant.now());
                        userRepository.saveAndFlush(holder);   // UNIQUE 선해제 후 아래 attach
                    });
        }

        if (restored) {
            user.restore(req.installationId(), req.deviceId());
            sanctionService.thawAll(userId, Instant.now());
        }
        applyDeviceInfo(user, req.deviceInfo());
        user.attachInstallation(req.installationId(), req.deviceId());
        applyCountry(user, req.deviceInfo());
        user.touchLastLogin();
        activity.touch(user.getId());
        userRepository.saveAndFlush(user);
        activity.touch(user.getId());

        socialTokenService.upsert(user.getId(), provider, info.idpTokens());   // unlink 근거 최신화

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        UserScoreSummary summary = scoreSummaryRepository.findById(user.getId()).orElse(null);
        int flushIntervalSec = syncPolicy.forUser(user);
        // 잠금 상세는 정지 상태일 때만 읽는다 — 정상 로그인은 제재 테이블을 건드리지 않는다.
        var lock = user.isSuspended() ? sanctionService.activeSanction(user.getId()).orElse(null) : null;
        return OAuthLoginResponse.existing(pair, user, summary, flushIntervalSec, lock).withRestored(restored);
    }

    /**
     * 국가 코드 최신화(로그인마다). 해석 실패 시 기존 값 유지, 신규면 서비스 기본 국가 —
     * users.country_code 가 NULL 로 남던 문제의 마감선이다.
     */
    private void applyCountry(User user, DeviceInfoRequest device) {
        String country = (device != null) ? device.country() : null;
        String timeZone = (device != null) ? device.timeZone() : null;
        user.updateCountryCode(countryResolver.resolveFor(user.getCountryCode(), country, timeZone));
    }

    private void applyDeviceInfo(User user, DeviceInfoRequest device) {
        if (device == null) {
            user.updateDeviceInfo(null, null, null, null, null, null, null, null);
            user.updateRamMb(null);
            return;
        }
        user.updateRamMb(device.ramMb());
        user.updateDeviceInfo(device.toPlatform(), device.versionCode(), device.versionName(),
                device.osVersion(), device.sdkInt(), device.deviceModel(),
                device.manufacturer(), device.lowRam());
    }

}
