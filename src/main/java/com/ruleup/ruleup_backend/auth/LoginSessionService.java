package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.dto.DeviceInfoRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.web.CountryResolver;
import com.ruleup.ruleup_backend.notification.NotificationService;
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
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final SocialTokenService socialTokenService;
    private final NotificationService notificationService;
    private final CountryResolver countryResolver;
    private final TokenService tokenService;
    private final com.ruleup.ruleup_backend.user.domain.TempNicknameAllocator tempNicknameAllocator;

    @Transactional
    public OAuthLoginResponse loginExisting(UUID userId, OAuthProvider provider,
                                            OAuthLoginRequest req, OAuthUserInfo info) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        // 영구 정지 계정은 로그인 자체를 차단한다. (재가입 경로도 없음 — subject 가 살아있는 한 이 분기로 온다)
        if (user.isBanned()) throw new BusinessException(ErrorCode.ACCOUNT_BANNED);

        // ===== 탈퇴 복원 — 동일 소셜 계정 재로그인 시 전부 복원 (DB 정리 §12.2) =====
        // ⚠️ 정책은 "1년 이내"만 복원이지만 지금은 <b>경과 시간을 보지 않는다</b>(레거시로 유지, 2026-08-04 확정).
        // 근거: 1년 파기 배치가 아직 없어 데이터가 그대로 남아 있다 → 살아 있는 기록을 두고
        // 신규 회원으로 취급하면 오히려 계정이 갈라진다. 파기 배치를 넣을 때 함께 조건을 건다
        // (배치가 oauth_subject 를 익명화하면 이 분기 자체가 자연히 신규 가입으로 빠진다).
        boolean restored = false;
        if (user.isWithdrawn()) {
            // 기존 승인 닉네임(또는 심사 중이던 신청 닉네임)을 타인이 선점했으면 CONFLICT + 임시 닉네임
            boolean nicknameTaken = userRepository.isNicknameTaken(user.getApprovedNickname(), user.getId())
                    || (user.isNicknamePending()
                        && userRepository.isNicknameTaken(user.getNickname(), user.getId()));
            if (nicknameTaken) {
                tempNicknameAllocator.assign(user,
                        candidate -> userRepository.isNicknameTaken(candidate, user.getId()));
                user.markNicknameConflict();   // 클라는 닉네임 재설정 화면에서 진행을 막는다(계약)
            }
            user.restore(req.installationId(), req.deviceId());
            restored = true;
        }

        // ===== 단일 활성 기기 — 다른 기기 로그인이면 기존 세션 전부 종료 =====
        boolean deviceChanged = user.getDeviceId() != null && req.deviceId() != null
                && !req.deviceId().equals(user.getDeviceId());
        if (deviceChanged) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
            notificationService.notify(user.getId(), NotificationType.SYSTEM,
                    "다른 기기에서 로그인됨",
                    "새 기기에서 로그인되어 기존 기기의 세션이 종료됐어요. 본인이 아니라면 계정 보안을 확인해주세요.");
        }

        // ===== 설치 인계 — uq_users_installation_id: 하나의 설치는 한 계정에만 연결 =====
        if (req.installationId() != null && !req.installationId().isBlank()) {
            userRepository.findByInstallationId(req.installationId())
                    .filter(holder -> !holder.getId().equals(user.getId()))
                    .ifPresent(holder -> {
                        holder.detachInstallation();
                        refreshTokenRepository.revokeAllByUserId(holder.getId(), Instant.now());
                        userRepository.saveAndFlush(holder);   // UNIQUE 선해제 후 아래 attach
                    });
        }

        applyDeviceInfo(user, req.deviceInfo());
        user.attachInstallation(req.installationId(), req.deviceId());
        user.updateCountryCode(countryResolver.resolve(deviceCountry(req.deviceInfo())));
        user.touchLastLogin();
        user.touchLastActive();
        userRepository.save(user);

        socialTokenService.upsert(user.getId(), provider, info.idpTokens());   // unlink 근거 최신화

        TokenService.TokenPair pair = tokenService.issueTokenPair(user);
        UserScoreSummary summary = scoreSummaryRepository.findById(user.getId()).orElse(null);
        int flushIntervalSec = FlushIntervalPolicy.forUser(user);
        return OAuthLoginResponse.existing(pair, user, summary, flushIntervalSec, restored);
    }

    private void applyDeviceInfo(User user, DeviceInfoRequest device) {
        if (device == null) return;
        user.updateDeviceInfo(device.toPlatform(), device.versionCode(), device.versionName(),
                device.osVersion(), device.sdkInt(), device.deviceModel(),
                device.manufacturer(), device.lowRam());
    }

    private String deviceCountry(DeviceInfoRequest device) {
        return (device != null) ? device.country() : null;
    }
}
