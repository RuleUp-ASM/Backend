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
 *                          끝나면 목적이 끝난다. <b>정확히 이 일수</b>를 남긴다 — 기본 3이면
 *                          오늘·D-1·D-2 세 날짜다(예전에는 경계 계산이 하루 더 남겼다)
 * @param signalPartitionLookaheadDays 미리 만들어 둘 미래 파티션 일수. 스펙이 <b>최소 7일</b>을
 *                          요구한다 — 잡이 하루 이틀 밀려도 적재가 MAXVALUE 파티션으로 몰리지
 *                          않게 하는 여유다
 * @param gpsRetentionDays  GPS 원본 좌표를 <b>확정 이후</b> 유지할 일수. 위치정보법의 목적 달성 시
 *                          즉시 파기 원칙 대상이라 다른 신호와 규칙이 다르다. 기준은 고정 일괄
 *                          시각이 아니라 <b>건별 확정 시각 + 이 값</b>이다 — 일괄로 잡으면 아직
 *                          확정 전인 건까지 지워진다.
 *                          <p>기준 시각은 <b>귀속일의 확정 경계</b>(D+2 00:00 KST)다. 「이 판정이 확정된
 *                          시각」이 아니다 — 위치 원본은 여러 챌린지가 공유하므로, 먼저 확정한 챌린지
 *                          기준으로 잡으면 같은 날짜의 다른 챌린지가 확정되기 전에 좌표가 사라진다.
 *                          <p>기본값 <b>0</b> 은 「경계가 지나면 곧바로 파기 대상」이라는 뜻이고,
 *                          위치정보법의 목적 달성 시 즉시 파기 원칙에 가장 가깝다.
 *                          <p><b>반드시 {@code signalRetentionDays} 보다 짧아야 한다.</b> 공통 스펙은
 *                          「30일 예정」이라 적었지만 백엔드 스펙은 판정 원본을 D+2 확정 직후 파티션째
 *                          걷으라고 적었다. 30일로 두면 좌표 행이 파기 타이머가 도달하기 <b>전에</b>
 *                          파티션과 함께 사라져 {@code purgedAt} 경로가 한 번도 돌지 않는다 —
 *                          「파기했다」는 기록이 없는 채로 사라지는 셈이다. 둘 중 짧은 쪽이 법 취지에
 *                          맞으므로 <b>1일</b>을 기본으로 삼아 파기 배치가 먼저 닿게 한다. 30일은
 *                          이상탐지가 볼 수 있는 <b>상한</b>이지 목표가 아니다
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
    private static final int DEFAULT_GPS_RETENTION_DAYS = 0;
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
        // 0 은 「확정 경계가 지나면 즉시」라는 유효한 값이다.
        gpsRetentionDays = nonNegativeOrDefault(gpsRetentionDays, DEFAULT_GPS_RETENTION_DAYS);
        if (gpsRetentionDays >= signalRetentionDays) {
            // 설정만으로 조용히 어긋나면 파기 경로가 죽은 것을 아무도 모른다. 기동 때 막는다.
            throw new IllegalArgumentException(
                    "app.verification.gps-retention-days(" + gpsRetentionDays + ") 는 "
                            + "signal-retention-days(" + signalRetentionDays + ") 보다 짧아야 한다 — "
                            + "그렇지 않으면 좌표가 파기 타이머 도달 전에 파티션째 사라져 파기 기록이 남지 않는다");
        }
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
