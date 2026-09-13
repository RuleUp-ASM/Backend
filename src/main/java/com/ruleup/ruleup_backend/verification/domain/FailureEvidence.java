package com.ruleup.ruleup_backend.verification.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실패 판정의 <b>설명 근거</b> — 사유 코드 + 한 줄 요약 + 기준값 + 실제값.
 *
 * <p>개인정보보호법의 자동화된 결정 조항 대응이다(공통 5-8). 자동 판정이 실패 → 감점 → 강퇴로
 * 이어지므로 <b>왜 실패했는지를 설명할 수 있어야 한다</b> — 「체류 42분, 목표 60분」처럼 숫자가
 * 보여야 하고, 그 숫자는 판정 당시 값으로 고정돼야 한다. 기준값을 나중에 조정해도 과거 판정의
 * 설명이 바뀌면 안 되기 때문이다.
 *
 * <p><b>순수 함수다.</b> evaluator 가 남긴 evidence 와 사유 코드만 읽는다 — 확정 배치가 부르는
 * 경로라 여기서 조회나 예외가 생기면 판정 트랜잭션이 흔들린다.
 */
public record FailureEvidence(String summary,
                              Map<String, Object> expected,
                              Map<String, Object> actual) {

    /** 요약이 컬럼을 넘지 않게 자른다 — {@code evidenceSummary VARCHAR(512)}. */
    private static final int SUMMARY_MAX = 512;

    /**
     * 판정 <b>기준</b>을 담은 evidence 키. evaluator 가 목표를 함께 기록해 두어야 실패 설명에
     * 숫자가 들어간다 — 기록하지 않는 타입은 기준값 없이 사유 코드만 남는다.
     */
    private static final List<String> EXPECTED_KEYS = List.of(
            "goal", "goalKm", "goalMinutes", "beforeTime", "bedtimeBefore", "graceMinutes",
            "unit", "metric", "mode");

    /** 실제 측정값 키. 나머지(내부 이월 상태·중복 제거 키)는 설명에 쓰지 않는다. */
    private static final List<String> ACTUAL_KEYS = List.of(
            "value", "distanceKm", "usageMinutes", "dwellMinutes", "sleepHours",
            "firstUnlockAt", "bedtime", "entered", "insideGeofence", "source");

    public static FailureEvidence of(String reasonCode, Map<String, Object> evidence) {
        Map<String, Object> expected = pick(evidence, EXPECTED_KEYS);
        Map<String, Object> actual = pick(evidence, ACTUAL_KEYS);
        return new FailureEvidence(summary(reasonCode, evidence), expected, actual);
    }

    /**
     * 한 줄 요약 — <b>사용자에게 그대로 보여줄 수 있는 문장</b>이다.
     *
     * <p>숫자를 넣을 수 있으면 넣고, 없으면 사유만으로 설명한다. 모르는 사유 코드도 문장을
     * 만들어야 한다 — 이 값은 NOT NULL 이고, 비면 「실패했는데 이유를 모르는」 고지가 남는다.
     */
    private static String summary(String reasonCode, Map<String, Object> ev) {
        String text = switch (reasonCode == null ? "" : reasonCode) {
            case "INSUFFICIENT_DWELL" ->
                    pair(ev, "체류", "dwellMinutes", "분", "goalMinutes", "분");
            case "INSUFFICIENT_DISTANCE" -> ev.containsKey("distanceKm")
                    ? pair(ev, "이동", "distanceKm", "km", "goalKm", "km")
                    : pair(ev, "이동", "value", unit(ev), "goal", unit(ev));
            case "INSUFFICIENT_STEPS" -> pair(ev, "걸음", "value", "걸음", "goal", "걸음");
            case "INSUFFICIENT_USAGE" -> pair(ev, "사용", "usageMinutes", "분", "goalMinutes", "분");
            case "USAGE_EXCEEDED" -> limit(ev, "사용", "usageMinutes", "goalMinutes");
            case "WOKE_UP_LATE" -> before(ev, "첫 잠금 해제", "firstUnlockAt", "beforeTime");
            case "SLEPT_LATE" -> before(ev, "취침", "bedtime", "bedtimeBefore");
            case "INSUFFICIENT_SLEEP" -> pair(ev, "수면", "sleepHours", "시간", "goal", "시간");
            case "ENTERED_AVOID_ZONE" -> "피해야 하는 장소에 들어간 기록이 있어요.";
            case "PERMISSION_MISSING" -> "인증에 필요한 권한이 꺼져 있어 측정하지 못했어요.";
            case "NO_SIGNAL_RECEIVED" -> "판정에 쓸 신호가 도착하지 않았어요.";
            case "UNTRUSTED_HEALTH_SOURCE" -> "직접 입력했거나 신뢰할 수 없는 출처의 기록만 있었어요.";
            default -> null;
        };
        if (text == null || text.isBlank()) {
            text = "이 날의 인증 조건을 충족하지 못했어요." + (reasonCode == null ? "" : " (" + reasonCode + ")");
        }
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX);
    }

    /** 「체류 42분 / 목표 60분」. 둘 중 하나라도 없으면 null 을 주고 일반 문장으로 넘어간다. */
    private static String pair(Map<String, Object> ev, String label, String actualKey,
                               String actualUnit, String goalKey, String goalUnit) {
        Object actual = value(ev, actualKey);
        Object goal = value(ev, goalKey);
        if (actual == null || goal == null) return null;
        return "%s %s%s / 목표 %s%s".formatted(label, actual, actualUnit, goal, goalUnit);
    }

    /** 「사용 95분 / 상한 60분」 — 최대형은 목표가 아니라 상한이다. */
    private static String limit(Map<String, Object> ev, String label, String actualKey, String goalKey) {
        Object actual = value(ev, actualKey);
        Object goal = value(ev, goalKey);
        if (actual == null || goal == null) return null;
        return "%s %s분 / 상한 %s분".formatted(label, actual, goal);
    }

    /** 「첫 잠금 해제 07:20 / 목표 07:00 이전」. */
    private static String before(Map<String, Object> ev, String label, String actualKey, String goalKey) {
        Object actual = value(ev, actualKey);
        Object goal = value(ev, goalKey);
        if (goal == null) return null;
        return (actual == null)
                ? "%s 기록이 없어요 / 목표 %s 이전".formatted(label, goal)
                : "%s %s / 목표 %s 이전".formatted(label, actual, goal);
    }

    private static String unit(Map<String, Object> ev) {
        Object unit = value(ev, "unit");
        return (unit == null) ? "" : unit.toString();
    }

    private static Object value(Map<String, Object> ev, String key) {
        return (ev == null) ? null : ev.get(key);
    }

    /** 선언된 키만 골라 담는다 — 내부 이월 상태(seenTransitions·open 등)는 설명이 아니다. */
    private static Map<String, Object> pick(Map<String, Object> ev, List<String> keys) {
        if (ev == null || ev.isEmpty()) return null;
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String key : keys) {
            Object v = ev.get(key);
            if (v != null) picked.put(key, v);
        }
        return picked.isEmpty() ? null : picked;
    }
}
