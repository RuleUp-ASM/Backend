package com.ruleup.ruleup_backend.challenge.guide;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 인증 방법 안내 문구의 재료 — 방의 인증 조건을 <b>서버가</b> 사람이 읽는 값으로 바꿔 둔 것.
 *
 * <p>숫자·시각은 LLM 이 만들지 않는다. 여기서 「10,000걸음」「2시간」「오전 7시」처럼 확정해 넘기고,
 * LLM 은 문장만 다듬는다. 응답에 이 값들이 그대로 들어 있지 않으면 버리고 {@link #fallback()} 을 쓴다
 * — 안내 문구가 실제 판정 기준과 다르면 사용자는 문구대로 하고도 실패한다.
 *
 * <p>{@code fallback} 은 LLM 이 없거나 응답이 기준을 어겼을 때 쓰는 문구다. 판정기가 실제로 보는
 * 조건만 말한다(예: 「매일 10,000걸음 이상 걸으면 자동 인증됩니다.」).
 *
 * @param method      판정기 태그(GPS_PRESENCE·HEALTH·…). 수동이면 SELF_CHECK
 * @param mustContain 응답에 그대로 들어 있어야 하는 값들
 * @param fingerprint 이 재료를 만든 인증 조건의 지문 — 생성 중에 조건이 바뀌었는지 가린다
 */
public record VerificationGuideFacts(
        boolean auto,
        String method,
        String routineName,
        String title,
        String frequency,
        List<String> conditions,
        List<String> mustContain,
        String fallback,
        String fingerprint
) {

    public static VerificationGuideFacts of(Challenge c, RoutineTemplate template) {
        boolean auto = c.getVerificationConfig() != null
                && c.getVerificationConfig().selectedMethod() == SelectedMethod.AUTO
                && template != null && template.getVerificationMethod() != null;
        String method = auto ? template.getVerificationMethod() : "SELF_CHECK";
        Map<String, Object> params = (c.getParams() != null) ? c.getParams() : Map.of();
        String freq = frequency(c.getWeeklyCount());

        // 조건(프롬프트용)과 기본 문구를 같은 값으로 함께 만든다 — 둘이 어긋나면 안 된다
        List<String> conditions = new ArrayList<>();
        List<String> must = new ArrayList<>();
        String fallback;
        switch (auto ? method : "SELF_CHECK") {
            case "GPS_PRESENCE" -> {
                String dur = minutes(params.get("duration_min"));
                conditions.add("등록한 장소에 " + (dur != null ? dur + " 이상 " : "") + "머물기");
                if (dur != null) must.add(dur);
                fallback = "장소를 등록한 뒤 " + freq + " 그곳에 " + (dur != null ? dur + " 이상 " : "")
                        + "머물면 자동 인증됩니다.";
            }
            case "GPS_AVOID" -> {
                conditions.add("등록한 장소에 머물지 않기(잠깐 스쳐 지나가는 것은 괜찮다)");
                fallback = "피하고 싶은 장소를 등록하고 그곳에 머물지 않으면 자동 인증됩니다. "
                        + "잠깐 스쳐 지나가는 것은 괜찮습니다.";
            }
            case "HEALTH" -> {
                String steps = steps(params.get("steps"));
                String km = km(params.get("distance_km"));
                List<String> parts = new ArrayList<>();
                if (steps != null) { conditions.add("하루 " + steps + " 이상 걷기"); must.add(steps); parts.add(steps + " 이상 걸으면"); }
                if (km != null) { conditions.add("하루 " + km + " 이상 이동하기"); must.add(km); parts.add(km + " 이상 이동하면"); }
                fallback = freq + " " + (parts.isEmpty() ? "목표 걸음을 채우면" : String.join(", ", parts))
                        + " 자동 인증됩니다.";
            }
            case "SCREEN_TIME_MAX", "SCREEN_TIME_MIN" -> {
                String dur = minutes(params.get("duration_min"));
                String bound = "SCREEN_TIME_MAX".equals(method) ? "이하로" : "이상";
                conditions.add("고른 앱을 하루 " + (dur != null ? dur + " " : "목표 시간 ") + bound + " 쓰기");
                if (dur != null) must.add(dur);
                fallback = freq + " 고른 앱을 " + (dur != null ? dur : "목표 시간") + " " + bound
                        + " 사용하면 자동 인증됩니다.";
            }
            case "WAKE" -> {
                String t = clock(params.get("target_time"));
                conditions.add((t != null ? t : "목표 시각") + " 전에 처음 휴대폰 잠금 해제하기");
                if (t != null) must.add(t);
                fallback = freq + " " + (t != null ? t : "목표 시각") + " 전에 처음 휴대폰 잠금을 해제하면 자동 인증됩니다.";
            }
            case "SLEEP" -> {
                String bed = clock(params.get("bedtime_before"));
                String hours = hours(params.get("sleep_hours"));
                List<String> parts = new ArrayList<>();
                if (bed != null) { conditions.add(bed + " 전에 잠들기(수면 기록 기준)"); must.add(bed); parts.add(bed + " 전에 잠들면"); }
                if (hours != null) { conditions.add(hours + " 이상 자기(수면 기록 기준)"); must.add(hours); parts.add(hours + " 이상 자면"); }
                fallback = freq + " " + (parts.isEmpty() ? "목표만큼 자면" : String.join(", ", parts))
                        + " 수면 기록으로 자동 인증됩니다.";
            }
            default -> {
                conditions.add("루틴을 한 날 그날 안에 앱에서 직접 인증하기");
                fallback = freq + " 루틴을 마친 뒤 그날 안에 앱에서 직접 인증해 주세요.";
            }
        }
        return new VerificationGuideFacts(auto, method,
                template != null ? template.getName() : null,
                c.publicTitle(), freq, List.copyOf(conditions), List.copyOf(must), fallback,
                fingerprint(method, c.getTemplateId(), c.getWeeklyCount(), params));
    }

    // ===== 값 표기 =====

    static String frequency(Integer weeklyCount) {
        if (weeklyCount == null || weeklyCount >= 7) return "매일";
        return "주 " + weeklyCount + "회";
    }

    static String minutes(Object raw) {
        BigDecimal v = decimal(raw);
        if (v == null) return null;
        int m = v.setScale(0, RoundingMode.HALF_UP).intValue();
        if (m < 60) return m + "분";
        int h = m / 60, rest = m % 60;
        return rest == 0 ? h + "시간" : h + "시간 " + rest + "분";
    }

    static String hours(Object raw) {
        BigDecimal v = decimal(raw);
        if (v == null) return null;
        return minutes(v.multiply(BigDecimal.valueOf(60)));
    }

    static String steps(Object raw) {
        BigDecimal v = decimal(raw);
        if (v == null) return null;
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(v.setScale(0, RoundingMode.HALF_UP)) + "걸음";
    }

    static String km(Object raw) {
        BigDecimal v = decimal(raw);
        if (v == null) return null;
        return v.stripTrailingZeros().toPlainString() + "km";
    }

    /** "07:00" → 「오전 7시」, "23:59" → 「자정」. 판정은 HH:mm 그대로지만 안내는 읽히는 말로. */
    static String clock(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (!s.matches("\\d{1,2}:\\d{2}")) return null;
        int h = Integer.parseInt(s.substring(0, s.indexOf(':')));
        int m = Integer.parseInt(s.substring(s.indexOf(':') + 1));
        if (h > 23 || m > 59) return null;
        if ((h == 23 && m == 59) || (h == 0 && m == 0)) return "자정";
        String period;
        int hh;
        if (h < 5) { period = "새벽"; hh = h == 0 ? 12 : h; }
        else if (h < 12) { period = "오전"; hh = h; }
        else if (h == 12) { period = "낮"; hh = 12; }
        else if (h < 18) { period = "오후"; hh = h - 12; }
        else { period = "밤"; hh = h - 12; }
        return period + " " + hh + "시" + (m == 0 ? "" : " " + m + "분");
    }

    private static BigDecimal decimal(Object raw) {
        if (raw == null) return null;
        try {
            BigDecimal v = (raw instanceof BigDecimal b) ? b : new BigDecimal(raw.toString().trim());
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fingerprint(String method, Long templateId, Integer weeklyCount, Map<String, Object> params) {
        Map<String, String> sorted = new TreeMap<>();
        params.forEach((k, v) -> sorted.put(k, String.valueOf(v)));
        return method + "|" + templateId + "|" + weeklyCount + "|" + sorted;
    }
}
