package com.ruleup.ruleup_backend.push;

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

    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * 토큰 upsert. 이미 존재하면(다른 유저 소유 포함) 소유자·플랫폼을 재바인딩하고 최근 확인 시각을 갱신한다
     * → 재로그인/기기 양도로 같은 토큰이 넘어와도 유니크 위반 없이 최신 소유자로 수렴.
     */
    @Transactional
    public void register(UUID userId, String token, DevicePlatform platform) {
        DevicePlatform p = (platform != null) ? platform : DevicePlatform.ANDROID;
        Instant now = Instant.now();
        deviceTokenRepository.findByToken(token).ifPresentOrElse(
                existing -> existing.reassign(userId, p, now),
                () -> deviceTokenRepository.save(DeviceToken.create(userId, token, p, now)));
    }

    /** 로그아웃/토큰 폐기 시 해제(본인 토큰만). */
    @Transactional
    public void unregister(UUID userId, String token) {
        deviceTokenRepository.deleteByUserIdAndToken(userId, token);
    }

    /** 무효 토큰 정리(전송 시 UNREGISTERED 판정). 소유자 무관하게 그 토큰 제거. */
    @Transactional
    public void remove(String token) {
        deviceTokenRepository.deleteByToken(token);
    }

    /** 해당 유저의 활성 등록 토큰들(전송 대상). */
    @Transactional(readOnly = true)
    public List<String> tokensOf(UUID userId) {
        return deviceTokenRepository.findByUserId(userId).stream()
                .map(DeviceToken::getToken)
                .toList();
    }
}
