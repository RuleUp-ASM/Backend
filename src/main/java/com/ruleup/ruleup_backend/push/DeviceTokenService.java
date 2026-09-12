package com.ruleup.ruleup_backend.push;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.push.domain.DeviceToken;
import com.ruleup.ruleup_backend.push.domain.DevicePlatform;
import com.ruleup.ruleup_backend.push.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FCM 디바이스 토큰 등록/조회/정리. 고스트 푸시 전송 어댑터({@link FcmPushSender})가 조회 대상으로 쓰고,
 * 클라가 {@code /api/v1/devices} 로 등록/해제한다.
 */
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    /** {@code DeviceToken.token} 컬럼 길이. 넘으면 저장 단계에서 잘려 다른 토큰이 된다. */
    private static final int TOKEN_MAX = 512;

    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * 토큰 upsert. 이미 존재하면(다른 유저 소유 포함) 소유자·플랫폼을 재바인딩하고 최근 확인 시각을 갱신한다
     * → 재로그인/기기 양도로 같은 토큰이 넘어와도 유니크 위반 없이 최신 소유자로 수렴.
     *
     * <p><b>등록과 동시에 이 유저의 다른 활성 토큰을 내린다</b>(단일 활성 기기 정책). upsert 만
     * 하고 끝내면 기기를 바꿔도 이전 기기 토큰이 활성으로 남아 알림이 두 기기에 동시에 뜬다.
     */
    @Transactional
    public void register(UUID userId, String token, DevicePlatform platform) {
        String normalized = requireValidToken(token);
        DevicePlatform p = (platform != null) ? platform : DevicePlatform.ANDROID;
        Instant now = Instant.now();

        deviceTokenRepository.findByToken(normalized).ifPresentOrElse(
                existing -> existing.reassign(userId, p, now),
                () -> deviceTokenRepository.save(DeviceToken.create(userId, normalized, p, now)));
        // 방금 등록한 토큰만 남긴다. flush 순서상 위 저장이 먼저 반영돼야 하므로 그 뒤에 부른다.
        deviceTokenRepository.deactivateOthers(userId, normalized, now);
    }

    /**
     * 로그아웃/토큰 폐기 시 해제(본인 토큰만) — <b>지우지 않고 내린다</b>.
     * 「알림이 안 와요」를 받았을 때 그 기기가 언제 빠졌는지 볼 자리를 남긴다.
     */
    @Transactional
    public void unregister(UUID userId, String token) {
        if (token == null || token.isBlank()) return;   // 해제는 멱등이다
        deviceTokenRepository.deactivateOwn(userId, token.trim(), Instant.now());
    }

    /**
     * 무효 토큰 정리(전송 시 UNREGISTERED 판정). 소유자 무관하게 <b>비활성화</b>한다.
     *
     * <p>구 구현은 행을 지웠다. 새 파이프라인이 「지우지 않고 내린다」로 정한 규칙(V36)과
     * 어긋나 있었고, 같은 토큰이 두 경로에서 서로 다르게 처리됐다.
     */
    @Transactional
    public void remove(String token) {
        if (token == null || token.isBlank()) return;
        deviceTokenRepository.deactivate(List.of(token.trim()), Instant.now());
    }

    /** 해당 유저의 <b>활성</b> 등록 토큰들(전송 대상). 비활성 행은 빠진다. */
    @Transactional(readOnly = true)
    public List<String> tokensOf(UUID userId) {
        return deviceTokenRepository.findActiveTokensOf(userId);
    }

    /**
     * 토큰 형식 검증. 공백만 막으면 개행이 섞였거나 컬럼(512)을 넘는 값이 저장되고, 그 토큰은
     * <b>발송 단계에서야</b> 조용히 실패한다 — 등록 시점에 거절해야 원인이 드러난다.
     */
    private static String requireValidToken(String token) {
        if (token == null) throw new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN);
        String normalized = token.trim();
        if (normalized.isEmpty() || normalized.length() > TOKEN_MAX)
            throw new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN);
        // FCM 토큰은 공백·개행을 포함하지 않는다. 섞여 들어오면 클라이언트의 복사 오류다.
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isWhitespace(normalized.charAt(i)))
                throw new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN);
        }
        return normalized;
    }
}
