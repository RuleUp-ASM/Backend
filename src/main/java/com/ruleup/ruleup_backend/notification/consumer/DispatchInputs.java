package com.ruleup.ruleup_backend.notification.consumer;

import com.ruleup.ruleup_backend.notification.domain.UserNotificationSetting;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 발송 판정에 필요한 유저 상태 — <b>묶음 조회 결과를 한 번에 담는다</b>.
 *
 * <p>건당 조회를 금지하려고 이 모양이다. 08:00 에 8만 건이 소진되는데 N+1 이 나면 RDS 가 밀린다.
 * 컨슈머는 메시지 묶음당 설정·음소거·기기·억제 이력을 IN 으로 한 번씩만 읽는다.
 *
 * @param settings 저장된 적 없으면 기본값(전부 ON)이 들어온다. 읽음 커서도 여기 있다.
 * @param lastPushed {@code suppress_key → 마지막 발송 성공 시각}. <b>{@code created_at} 이 아니다</b> —
 *                   적재 시각으로 판정하면 억제된 행도 시각을 남겨 억제가 영원히 풀리지 않는다.
 * @param marketingConsent 광고성 정보 수신 동의 — <b>약관 상태가 원본</b>이고 설정 토글이 아니다.
 *                   묶음에 마케팅이 없으면 조회하지 않으므로 그때는 {@code false} 로 들어온다
 *                   (마케팅이 아닌 타입은 이 값을 보지 않는다).
 */
public record DispatchInputs(
        UserNotificationSetting settings,
        Set<UUID> mutedChallengeIds,
        Map<String, Instant> lastPushed,
        boolean hasActiveDevice,
        boolean marketingConsent) {

    public DispatchInputs withSettings(UserNotificationSetting other) {
        return new DispatchInputs(other, mutedChallengeIds, lastPushed, hasActiveDevice,
                marketingConsent);
    }

    public DispatchInputs withMuted(Set<UUID> muted) {
        return new DispatchInputs(settings, muted, lastPushed, hasActiveDevice, marketingConsent);
    }

    public DispatchInputs withLastPushed(Map<String, Instant> pushed) {
        return new DispatchInputs(settings, mutedChallengeIds, pushed, hasActiveDevice,
                marketingConsent);
    }

    public DispatchInputs withHasDevice(boolean hasDevice) {
        return new DispatchInputs(settings, mutedChallengeIds, lastPushed, hasDevice,
                marketingConsent);
    }

    public DispatchInputs withMarketingConsent(boolean consented) {
        return new DispatchInputs(settings, mutedChallengeIds, lastPushed, hasActiveDevice,
                consented);
    }
}
