package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.verification.domain.SignalExclusion;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.SignalDomain;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 이상탐지 입력 적재 (인증 공통 3절 ② · 백엔드 4-1 「이상탐지 입력」).
 *
 * <h4>원본을 뒤지지 않게 하려고 있다</h4>
 * 판정 원본은 사흘이면 파티션째 사라진다. 온라인 이상탐지가 원본을 봐야 한다면 보관 기간을
 * 늘릴 수밖에 없고, 그러면 개인정보 보관 범위가 탐지 편의 때문에 넓어진다. 그래서 <b>성공
 * 판정에서 탐지에 필요한 값만</b> 뽑아 별도 도메인에 30일 둔다.
 *
 * <h4>무엇을 남기고 무엇을 남기지 않는가</h4>
 * <ul>
 *   <li><b>FEATURE</b> — 최종 성공 인증에서 뽑은 파생값(체류 분·사용 분·걸음 값·배제 건수).
 *       실패 인증은 승격하지 않는다(스펙 명시). 좌표 원본은 넣지 않는다 — GPS 조기 파기
 *       정책이 30일 보관보다 우선한다.</li>
 *   <li><b>HYGIENE</b> — 배제 근거의 최소 형태(누가·어떤 신호·왜·언제). 성공·실패와 무관하다.</li>
 * </ul>
 *
 * <p>적재 실패가 판정을 되돌리지 않는다. 탐지 입력이지 판정의 일부가 아니다.
 */
@Component
@RequiredArgsConstructor
public class AnomalyEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AnomalyEventRecorder.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 성공 인증에서 옮길 feature 키. 목록에 없는 값은 남기지 않는다 — 수집 최소화. */
    private static final Set<String> FEATURE_KEYS = Set.of(
            "dwellMinutes", "goalMinutes", "source", "insideGeofence",
            "usageMinutes", "firstUnlockAt", "beforeTime",
            "value", "goal", "metric", "unit", "sleepHours", "bedtime",
            "distanceKm", "excludedMock", "excludedAccuracy");

    private final JdbcTemplate jdbc;

    /**
     * 성공 인증의 탐지 feature. 실패 인증에는 부르지 않는다.
     *
     * @param evidence 판정 근거. 여기서 {@link #FEATURE_KEYS} 만 골라 담는다
     */
    public void recordSuccessFeature(UUID userId, UUID verificationId, VerificationMethod method,
                                     LocalDate targetDate, Map<String, Object> evidence, Instant at) {
        if (userId == null || method == null) return;
        Map<String, Object> features = new LinkedHashMap<>();
        if (evidence != null) {
            evidence.forEach((k, v) -> { if (FEATURE_KEYS.contains(k)) features.put(k, v); });
        }
        String signalType = signalTypeOf(method);
        insert(SignalDomain.ofSignalTypeOrDefault(signalType, SignalDomain.DEVICE_USAGE),
                userId, verificationId, "FEATURE", signalType, null, 1, features,
                observedAt(targetDate, at), targetDate);
    }

    /** 배제 근거를 같은 3개 도메인에 남긴다 — {@code signal_exclusions} 와 같은 사건의 탐지용 사본이다. */
    public void recordHygiene(List<SignalExclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return;
        for (SignalExclusion e : exclusions) {
            if (e == null) continue;
            LocalDate day = LocalDate.ofInstant(e.getExcludedAt(), KST);
            insert(SignalDomain.ofSignalTypeOrDefault(e.getSignalType(), SignalDomain.DEVICE_USAGE),
                    e.getUserId(), e.getVerificationDailyId(), "HYGIENE", e.getSignalType(),
                    e.getReason(),
                    e.getSignalCount(), null, e.getExcludedAt(), day);
        }
    }

    private void insert(SignalDomain domain, UUID userId, UUID verificationId, String eventType,
                        String signalType, String anomalyType, int signalCount,
                        Map<String, Object> features, Instant observedAt, LocalDate observedDate) {
        try {
            jdbc.update("INSERT INTO " + domain.anomalyTable()
                            + " (id, observedDate, userId, verificationId, eventType, signalType,"
                            + "  anomalyType, signalCount, features, observedAt)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    bytes(UuidGenerator.generate()), Date.valueOf(observedDate), bytes(userId),
                    (verificationId != null) ? bytes(verificationId) : null,
                    eventType, signalType, anomalyType, signalCount,
                    (features != null && !features.isEmpty()) ? JSON.writeValueAsString(features) : null,
                    Timestamp.from(observedAt));
        } catch (RuntimeException e) {
            // 판정은 이미 끝났다. 탐지 입력 한 줄 때문에 되돌리지 않는다.
            log.warn("이상탐지 입력 적재 실패 — 판정은 유지한다. table={} err={}",
                    domain.anomalyTable(), e.toString());
        }
    }

    /**
     * 귀속일의 관측 시각. 확정은 귀속일 이틀 뒤라 확정 시각을 그대로 쓰면 파티션이 어긋난다 —
     * 탐지는 「언제 일어난 일인가」로 묶어 보므로 귀속일 안에 둔다.
     */
    private static Instant observedAt(LocalDate targetDate, Instant fallback) {
        return (targetDate != null) ? targetDate.atStartOfDay(KST).toInstant() : fallback;
    }

    /** 판정 방식 → 신호 타입. {@code SignalExclusionRecorder} 와 같은 매핑이다. */
    private static String signalTypeOf(VerificationMethod method) {
        return switch (method) {
            case GPS_PRESENCE, GPS_DISTANCE -> "LOCATION";
            case HEALTH -> "HEALTH";
            case SCREEN_TIME -> "SCREEN_TIME";
            case WAKE -> "WAKE";
            case SLEEP -> "SLEEP";
            default -> "UNKNOWN";
        };
    }

    private static byte[] bytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
