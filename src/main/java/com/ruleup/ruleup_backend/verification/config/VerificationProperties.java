package com.ruleup.ruleup_backend.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 도메인 운영 상수. 배포 없이 조정할 수 있게 설정으로 뺀다.
 *
 * @param geofenceRadiusM 지오펜스 반경(m) — 유저가 정하는 값이 아닌 <b>서버 단일값</b>이라
 *                        요청에는 없고 응답의 serverRadiusM으로만 내려간다. 성능 테스트 후 조정
 * @param maxPayloadBytes sync 한 번의 본문 상한(bytes). 압축 해제 후 누적 바이트에도 같은 상한을 적용한다
 * @param syncMinIntervalSec 평상시 sync 최소 간격(초). 기기 스펙 기반 flushIntervalSec 보다 짧게 잡아
 *                           정상 주기 전송이 걸리지 않게 한다
 * @param syncBacklogMinIntervalSec 복구 전송(backlog=true) 최소 간격(초). 밀린 구간을 나눠 올려야 해서 더 짧다
 * @param avoidGraceMinutes 장소 피하기의 "스침" 허용 시간(분). 이 시간 안에 나오면 위반이 아니다 —
 *                          금지 장소 앞을 지나가기만 해도 지오펜스는 ENTER 를 쏘기 때문이다.
 *                          실기기 테스트로 조절할 값이라 코드 상수로 두지 않는다
 */
@ConfigurationProperties(prefix = "app.verification")
public record VerificationProperties(Integer geofenceRadiusM, Integer maxPayloadBytes,
                                     Integer syncMinIntervalSec, Integer syncBacklogMinIntervalSec,
                                     Integer avoidGraceMinutes) {

    private static final int DEFAULT_GEOFENCE_RADIUS_M = 500;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 1_048_576;
    private static final int DEFAULT_SYNC_MIN_INTERVAL_SEC = 300;
    private static final int DEFAULT_SYNC_BACKLOG_MIN_INTERVAL_SEC = 10;
    private static final int DEFAULT_AVOID_GRACE_MINUTES = 5;

    public VerificationProperties {
        geofenceRadiusM = positiveOrDefault(geofenceRadiusM, DEFAULT_GEOFENCE_RADIUS_M);
        maxPayloadBytes = positiveOrDefault(maxPayloadBytes, DEFAULT_MAX_PAYLOAD_BYTES);
        // 0 은 "제한 없음"으로 인정한다(테스트·부하 시나리오).
        syncMinIntervalSec = nonNegativeOrDefault(syncMinIntervalSec, DEFAULT_SYNC_MIN_INTERVAL_SEC);
        syncBacklogMinIntervalSec = nonNegativeOrDefault(syncBacklogMinIntervalSec, DEFAULT_SYNC_BACKLOG_MIN_INTERVAL_SEC);
        avoidGraceMinutes = nonNegativeOrDefault(avoidGraceMinutes, DEFAULT_AVOID_GRACE_MINUTES);
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static int nonNegativeOrDefault(Integer value, int fallback) {
        return value != null && value >= 0 ? value : fallback;
    }
}
