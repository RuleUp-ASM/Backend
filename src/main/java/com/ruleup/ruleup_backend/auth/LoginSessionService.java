package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.dto.DeviceInfoRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.web.CountryResolver;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.service.FlushIntervalPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    private final UserRepository userRepository;
    private final com.ruleup.ruleup_backend.sanction.SanctionService sanctionService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final SocialTokenService socialTokenService;
    private final NotificationPublisher notificationPublisher;
    private final CountryResolver countryResolver;
    private final TokenService tokenService;

    @Transactional
    public OAuthLoginResponse loginExisting(UUID userId, OAuthProvider provider,
                                            OAuthLoginRequest req, OAuthUserInfo info) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.withMessage(ErrorCode.LOGIN_FAILED, "ACCOUNT_NOT_FOUND",
                        "로그인 정보를 확인하지 못했어요. 처음부터 다시 로그인해주세요."));

        // 영구 정지 계정은 로그인 자체를 차단한다.
        if (sanctionService.isBanActive(user.getId()))
            throw new BusinessException(ErrorCode.ACCOUNT_BANNED);

        // 탈퇴 계정은 여기로 오지 않는다 — AuthService 가 신규 분기(signupToken)로 보내고,
        // 복원은 가입 요청에서 처리한다("탈퇴 후에는 회원가입을 거쳐 로그인", 회원 정책 §6).

        // ===== 단일 활성 기기 — 다른 기기 로그인이면 기존 세션 전부 종료 =====
        boolean deviceChanged = user.getDeviceId() != null && req.deviceId() != null
                && !req.deviceId().equals(user.getDeviceId());
        if (deviceChanged) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
            // 필수(A) — 계정 보안 고지라 야간에도 즉시 나간다.
            notificationPublisher.publish(NotificationEvent.of(user.getId(),
                    NotificationType.DEVICE_LOGGED_OUT,
                    "다른 기기에서 로그인됨",
                    "새 기기에서 로그인되어 기존 기기의 세션이 종료됐어요. 본인이 아니라면 계정 보안을 확인해주세요."));
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

        applyDeviceInfo(user, req.deviceInfo());
        user.attachInstallation(req.installationId(), req.deviceId());
        applyCountry(user, req.deviceInfo());
        user.touchLastLogin();
        user.touchLastActive();
        userRepository.save(user);

        socialTokenService.upsert(user.getId(), provider, info.idpTokens());   // unlink 근거 최신화

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        UserScoreSummary summary = scoreSummaryRepository.findById(user.getId()).orElse(null);
        int flushIntervalSec = FlushIntervalPolicy.forUser(user);
        return OAuthLoginResponse.existing(pair, user, summary, flushIntervalSec);
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
        if (device == null) return;
        user.updateDeviceInfo(device.toPlatform(), device.versionCode(), device.versionName(),
                device.osVersion(), device.sdkInt(), device.deviceModel(),
                device.manufacturer(), device.lowRam());
    }

}
