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
 * @param signalRetentionDays 판정 원본을 유지할 <b>일수</b>. raw 는 현재 귀속일과 직전 유예
 *                          귀속일만 필요한 hot storage 이고, D일 신호는 D+2 00:00 KST 확정이
 *                          끝나면 목적이 끝난다. 기본 3일(오늘·D-1·D-2)을 남기고 그보다 오래된
 *                          일자 파티션을 떨어뜨린다
 * @param signalPartitionLookaheadDays 미리 만들어 둘 미래 파티션 일수. 스펙이 <b>최소 7일</b>을
 *                          요구한다 — 잡이 하루 이틀 밀려도 적재가 MAXVALUE 파티션으로 몰리지
 *                          않게 하는 여유다
 * @param gpsRetentionDays  GPS 원본 좌표를 <b>확정 이후</b> 유지할 일수. 위치정보법의 목적 달성 시
 *                          즉시 파기 원칙 대상이라 다른 신호와 규칙이 다르다. 기준은 고정 일괄
 *                          시각이 아니라 <b>건별 확정 시각 + 이 값</b>이다 — 일괄로 잡으면 아직
 *                          확정 전인 건까지 지워진다. 기본 30일이며 이상탐지 윈도우에 맞춰 조정한다
 * @param anomalyRetentionDays 이상탐지 입력(유형별 anomaly 도메인)의 보관 일수. 스펙이 <b>최대 30일</b>
 *                          이라고 못 박았고, 만료분은 행 삭제가 아니라 일자 파티션 DROP 으로 걷는다
 * @param requireActiveDevice sync 요청에 기기 식별자를 <b>강제</b>할지. 스펙의 「AT + 활성 기기 검증」을
 *                          엄격히 적용하면 기기를 밝히지 않은 요청은 거부해야 한다. 다만 계약에 기기가
 *                          없던 시절의 앱이 아직 있어 <b>기본값은 false</b> 다 — 켜는 순간 그 앱들은
 *                          전부 인증 불가가 된다. `verification.sync.device_id_missing` 이 0 으로
 *                          떨어진 것을 보고 켠다
 * @param requireSignalOrigin Health Connect 기록에 출처 메타데이터를 <b>강제</b>할지. 걸음·거리는
 *                          이미 출처가 없으면 거부하지만 수면은 통과시키고 있다 — 보내지 않는 클라가
 *                          남아 있어서다. 같은 이유로 기본값 false 이며, evidence 의 {@code originMissing}
 *                          이 0 으로 떨어진 것을 보고 켠다
 */
@ConfigurationProperties(prefix = "app.verification")
public record VerificationProperties(Integer geofenceRadiusM, Integer maxPayloadBytes,
                                     Integer syncMinIntervalSec, Integer syncBacklogMinIntervalSec,
                                     Integer avoidGraceMinutes, Integer signalRetentionDays,
                                     Integer signalPartitionLookaheadDays,
                                     Integer gpsRetentionDays, Integer anomalyRetentionDays,
                                     Boolean requireActiveDevice, Boolean requireSignalOrigin) {

    private static final int DEFAULT_GEOFENCE_RADIUS_M = 500;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 1_048_576;
    private static final int DEFAULT_SYNC_MIN_INTERVAL_SEC = 300;
    private static final int DEFAULT_SYNC_BACKLOG_MIN_INTERVAL_SEC = 10;
    private static final int DEFAULT_AVOID_GRACE_MINUTES = 5;
    private static final int DEFAULT_SIGNAL_RETENTION_DAYS = 3;
    private static final int DEFAULT_SIGNAL_PARTITION_LOOKAHEAD_DAYS = 10;
    private static final int DEFAULT_GPS_RETENTION_DAYS = 30;
    private static final int DEFAULT_ANOMALY_RETENTION_DAYS = 30;

    public VerificationProperties {
        geofenceRadiusM = positiveOrDefault(geofenceRadiusM, DEFAULT_GEOFENCE_RADIUS_M);
        maxPayloadBytes = positiveOrDefault(maxPayloadBytes, DEFAULT_MAX_PAYLOAD_BYTES);
        // 0 은 "제한 없음"으로 인정한다(테스트·부하 시나리오).
        syncMinIntervalSec = nonNegativeOrDefault(syncMinIntervalSec, DEFAULT_SYNC_MIN_INTERVAL_SEC);
        syncBacklogMinIntervalSec = nonNegativeOrDefault(syncBacklogMinIntervalSec, DEFAULT_SYNC_BACKLOG_MIN_INTERVAL_SEC);
        avoidGraceMinutes = nonNegativeOrDefault(avoidGraceMinutes, DEFAULT_AVOID_GRACE_MINUTES);
        signalRetentionDays = positiveOrDefault(signalRetentionDays, DEFAULT_SIGNAL_RETENTION_DAYS);
        signalPartitionLookaheadDays = positiveOrDefault(
                signalPartitionLookaheadDays, DEFAULT_SIGNAL_PARTITION_LOOKAHEAD_DAYS);
        gpsRetentionDays = positiveOrDefault(gpsRetentionDays, DEFAULT_GPS_RETENTION_DAYS);
        anomalyRetentionDays = positiveOrDefault(anomalyRetentionDays, DEFAULT_ANOMALY_RETENTION_DAYS);
        // 게이트 강화는 <b>끄는 쪽이 기본</b>이다. 구버전 앱을 한 번에 인증 불가로 만드는 변경은
        // 관측으로 안전을 확인한 뒤 켜야 한다.
        requireActiveDevice = requireActiveDevice != null && requireActiveDevice;
        requireSignalOrigin = requireSignalOrigin != null && requireSignalOrigin;
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static int nonNegativeOrDefault(Integer value, int fallback) {
        return value != null && value >= 0 ? value : fallback;
    }
}
