package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.SignalExclusion;
import com.ruleup.ruleup_backend.verification.domain.SignalExclusionReason;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.repository.SignalExclusionRepository;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 배제 로그 기록 — <b>신호 위생 층의 유일한 쓰기 경로</b>(공통 5-3 · 백엔드 4-1-1).
 *
 * <h4>언제 쓰는가</h4>
 * <ul>
 *   <li><b>게이트 단계</b> — 기기 전체를 못 믿는 경우(VPN · 무결성 실패). sync 마다 기록한다.
 *       판정 전이라 귀속할 판정이 없어 {@code verificationDailyId} 는 null 이다.</li>
 *   <li><b>판정 확정 시</b> — evaluator 가 evidence 에 쌓아 둔 위생 기록을 <b>한 번만</b> 옮긴다.
 *       evidence 는 sync 마다 누적되므로 그때마다 기록하면 같은 배제가 여러 행이 된다.</li>
 * </ul>
 *
 * <h4>실패해도 판정을 되돌리지 않는다</h4>
 * 탐지 입력이지 판정의 일부가 아니다. 여기서 예외를 올리면 <b>배제 로그 한 줄 때문에 인증
 * 판정이 롤백</b>된다 — 신호 위생과 제재를 분리하라는 스펙의 취지와 정반대다.
 */
@Component
@RequiredArgsConstructor
public class SignalExclusionRecorder {

    private static final Logger log = LoggerFactory.getLogger(SignalExclusionRecorder.class);

    private final SignalExclusionRepository repository;

    /**
     * 게이트 단계 배제 — 같은 신호 타입끼리 묶어 한 행으로 센다. 신호 하나에 한 행이면
     * sync 한 번에 수백 행이 되고, 탐지가 보는 것은 어차피 「반복되는가」다.
     */
    public void recordGateDrop(UUID userId, SignalExclusionReason reason,
                               List<SyncSignal> dropped, Instant at) {
        if (userId == null || reason == null || dropped == null || dropped.isEmpty()) return;

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (SyncSignal s : dropped) {
            String type = (s == null || s.type() == null) ? "UNKNOWN" : s.type();
            byType.merge(type, 1, Integer::sum);
        }
        List<SignalExclusion> rows = new ArrayList<>(byType.size());
        byType.forEach((type, count) ->
                rows.add(SignalExclusion.of(userId, null, type, reason, count, at)));
        save(rows);
    }

    /**
     * 판정 확정 시점의 위생 기록 — evaluator 가 evidence 에 남긴 누적 카운트를 옮긴다.
     *
     * <p>확정은 판정 하나에 한 번뿐이라(즉시 성공 · 확정 배치) 중복 기록이 생기지 않는다.
     * 성공·실패를 가리지 않는다 — 스펙이 「신호 위생 이상은 성공·실패와 무관하게 최소 근거를
     * 남길 수 있다」고 적었다.
     */
    public void recordEvaluationHygiene(UUID userId, UUID verificationDailyId,
                                        VerificationMethod method, Map<String, Object> evidence,
                                        Instant at) {
        if (userId == null || evidence == null || evidence.isEmpty()) return;

        String signalType = signalTypeOf(method);
        List<SignalExclusion> rows = new ArrayList<>(4);

        count(evidence, "excludedMock").ifPresent(c -> rows.add(SignalExclusion.of(
                userId, verificationDailyId, signalType, SignalExclusionReason.MOCK, c, at)));
        count(evidence, "excludedAccuracy").ifPresent(c -> rows.add(SignalExclusion.of(
                userId, verificationDailyId, signalType, SignalExclusionReason.ACCURACY_LOW, c, at)));

        // Health 는 「출처:사유」 문자열로 쌓아 둔다 — 직접 입력과 비신뢰 출처는 유저가 할 일이 다르다.
        Map<SignalExclusionReason, Integer> origins = rejectedOrigins(evidence);
        origins.forEach((reason, c) -> rows.add(SignalExclusion.of(
                userId, verificationDailyId, signalType, reason, c, at)));

        if (Boolean.TRUE.equals(evidence.get("untrustedExcluded")) && !origins.containsKey(
                SignalExclusionReason.UNTRUSTED_SOURCE)) {
            rows.add(SignalExclusion.of(userId, verificationDailyId, signalType,
                    SignalExclusionReason.UNTRUSTED_SOURCE, 1, at));
        }
        save(rows);
    }

    private void save(List<SignalExclusion> rows) {
        if (rows.isEmpty()) return;
        try {
            repository.saveAll(rows);
        } catch (RuntimeException e) {
            // 판정은 이미 끝났다. 탐지 입력 한 줄 때문에 되돌리지 않는다.
            log.warn("신호 배제 기록 실패 — 판정은 유지한다. count={} err={}", rows.size(), e.toString());
        }
    }

    /** {@code ["fitbit:UNTRUSTED_ORIGIN", "user:MANUAL"]} → 사유별 건수. */
    @SuppressWarnings("unchecked")
    private Map<SignalExclusionReason, Integer> rejectedOrigins(Map<String, Object> evidence) {
        Object raw = evidence.get("rejectedOrigins");
        Map<SignalExclusionReason, Integer> byReason = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list)) return byReason;

        for (Object item : (List<Object>) list) {
            if (item == null) continue;
            String text = item.toString();
            int sep = text.lastIndexOf(':');
            String gate = (sep >= 0) ? text.substring(sep + 1) : text;
            SignalExclusionReason reason = "MANUAL".equalsIgnoreCase(gate)
                    ? SignalExclusionReason.MANUAL_ENTRY : SignalExclusionReason.UNTRUSTED_SOURCE;
            byReason.merge(reason, 1, Integer::sum);
        }
        return byReason;
    }

    private java.util.Optional<Integer> count(Map<String, Object> evidence, String key) {
        Object value = evidence.get(key);
        if (!(value instanceof Number n) || n.intValue() <= 0) return java.util.Optional.empty();
        return java.util.Optional.of(n.intValue());
    }

    /** 판정 방식 → 신호 타입. 어떤 종류의 신호가 빠졌는지가 탐지 규칙의 입력이다. */
    private String signalTypeOf(VerificationMethod method) {
        if (method == null) return "UNKNOWN";
        return switch (method) {
            case GPS_PRESENCE, GPS_DISTANCE -> "LOCATION";
            case HEALTH -> "HEALTH";
            case SCREEN_TIME -> "SCREEN_TIME";
            case WAKE -> "WAKE";
            case SLEEP -> "SLEEP";
            default -> "UNKNOWN";
        };
    }
}
