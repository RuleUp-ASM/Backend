package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 인증 도메인 통합 테스트 공통 헬퍼.
 *  - 자동 인증 챌린지(+템플릿) 픽스처 insert — 판정 경로를 태우려면 READY 멤버가 필요하다.
 *  - sync 요청 봉투·신호 조립 (지오펜스 전환 / 측위 포인트 / 앱 사용 이벤트)
 *  - VerificationDaily 상태 조회
 */
public abstract class VerificationApiSupport extends ChallengeApiSupport {

    protected static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 템플릿 id 충돌을 피하려고 테스트마다 다른 값을 쓴다(공유 컨텍스트라 테이블이 누적된다). */
    private static final java.util.concurrent.atomic.AtomicLong TEMPLATE_SEQ =
            new java.util.concurrent.atomic.AtomicLong(900_000);

    protected static long nextTemplateId() {
        return TEMPLATE_SEQ.incrementAndGet();
    }

    // ===== 챌린지 픽스처 =====

    /**
     * 자동 인증 챌린지 1건. verificationMethod 로 평가기가 갈린다(GPS_PRESENCE / GPS_AVOID / SCREEN_TIME_MIN ...).
     *
     * @param paramsJson 챌린지 params — 평가 기준값(dwell 분·목표 시간 등)
     * @return challengeId
     */
    protected UUID insertAutoChallenge(UUID ownerId, String verificationMethod,
                                       String signalSource, String paramsJson) {
        long templateId = nextTemplateId();
        insertAutoTemplate(templateId, "자동인증-" + templateId, "설명", "EXERCISE",
                "{}", verificationMethod, "[]");

        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO challenges " +
                        "(id, owner_id, title, ai_title, description, category, mode, capacity, repeat_days, " +
                        " weekly_count, duration_days, start_date, end_date, template_id, verification_config, params, " +
                        " penalty_config, reward_config, anonymity, status, moderation_status, ai_assisted, participant_count) " +
                        "VALUES (?, ?, ?, ?, ?, 'EXERCISE', 'GROUP', 50, " +
                        " '[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\",\"SAT\",\"SUN\"]', 7, " +
                        " 14, DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 14 DAY), ?, ?, ?, " +
                        " '{\"mannerDeduction\":1.0}', '{\"mannerGain\":1.0}', 'REAL', 'ACTIVE', 'NONE', 1, 1)",
                bytes(id), bytes(ownerId), "자동 " + verificationMethod, "자동 " + verificationMethod, "설명",
                templateId,
                "{\"selectedMethod\":\"AUTO\",\"verificationType\":\"PHONE\",\"signalSource\":\"" + signalSource
                        + "\",\"wearableReq\":\"NONE\",\"requiredPermissions\":[]}",
                paramsJson);
        return id;
    }

    /**
     * 평가 대상(READY) 멤버 1명. 셋업 전(PENDING_SETUP)이면 sync 가 평가를 건너뛰므로 READY 로 넣는다.
     *
     * @param anchorsJson  challenge_members.anchors — GPS 평가의 멤버 바인딩 앵커(null 가능)
     * @param screenAppsJson challenge_members.screen_apps — SCREEN_TIME 대상 앱(null 가능)
     * @return challengeMemberId
     */
    protected UUID insertReadyMember(UUID challengeId, UUID userId, String anchorsJson, String screenAppsJson) {
        UUID memberId = UUID.randomUUID();
        jdbc().update("INSERT INTO challenge_members " +
                        "(id, challenge_id, user_id, role, status, schedule_type, target_days, setup_status, " +
                        " anchors, screen_apps, screen_apps_applied_from) " +
                        "VALUES (?, ?, ?, 'OWNER', 'ACTIVE', 'FIXED_DAYS', 14, 'READY', ?, ?, ?)",
                bytes(memberId), bytes(challengeId), bytes(userId), anchorsJson, screenAppsJson,
                (screenAppsJson != null) ? java.sql.Timestamp.from(Instant.now().minusSeconds(86_400)) : null);
        return memberId;
    }

    /** 앵커 1개짜리 JSON. */
    protected static String anchor(double lat, double lng, int radiusM, String label) {
        return "[{\"lat\":" + lat + ",\"lng\":" + lng + ",\"radiusM\":" + radiusM
                + ",\"label\":\"" + label + "\"}]";
    }

    /** 대상 앱 1개짜리 JSON(ScreenApp 계약: packageName + appName). */
    protected static String screenApps(String packageName) {
        return "[{\"packageName\":\"" + packageName + "\",\"appName\":\"" + packageName + "\"}]";
    }

    // ===== sync 봉투·신호 =====

    /** 오늘(KST) 자정~지금을 커버 구간으로 선언하는 sync 봉투. */
    protected Map<String, Object> syncBody(List<Map<String, Object>> signals) {
        long from = LocalDate.now(KST).atStartOfDay(KST).toInstant().toEpochMilli();
        long until = Instant.now().toEpochMilli();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceTimeMillis", until);
        m.put("elapsedRealtimeMillis", 1_000_000L);
        m.put("bootSessionId", "boot-1");
        m.put("timeZone", "Asia/Seoul");
        m.put("coveredFrom", from);
        m.put("coveredUntil", until);
        m.put("signals", signals);
        return m;
    }

    /** 오늘(KST) 시:분의 Instant — 신호 발생 시각을 만들 때 쓴다. */
    protected static Instant todayAt(int hour, int minute) {
        return LocalDate.now(KST).atTime(LocalTime.of(hour, minute)).atZone(KST).toInstant();
    }

    /**
     * 지오펜스 전환 신호. geofenceId 는 <b>challengeMemberId</b> 계약이라, 이 값이 곧 "어느 챌린지의 장소인지"다.
     */
    protected static Map<String, Object> geofenceSignal(UUID memberId, String transition, Instant at) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("geofenceId", memberId.toString());
        event.put("transition", transition);
        event.put("at", at.toString());
        event.put("isMock", false);
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "GEOFENCE");
        signal.put("observedAt", at.toString());
        signal.put("events", List.of(event));
        return signal;
    }

    /** 측위 포인트 신호(지오펜스 전환이 없을 때의 fallback 경로 입력). */
    protected static Map<String, Object> locationSignal(double lat, double lng, List<Instant> times) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Instant at : times) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("lat", lat);
            p.put("lng", lng);
            p.put("accuracy", 10.0);
            p.put("at", at.toString());
            p.put("isMock", false);
            points.add(p);
        }
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "LOCATION");
        signal.put("observedAt", times.get(times.size() - 1).toString());
        signal.put("points", points);
        return signal;
    }

    /** 앱 사용 구간 신호(RESUMED→PAUSED 한 쌍). */
    protected static Map<String, Object> usageSignal(String packageName, Instant from, Instant to) {
        Map<String, Object> resumed = new LinkedHashMap<>();
        resumed.put("packageName", packageName);
        resumed.put("type", "RESUMED");
        resumed.put("at", from.toString());
        Map<String, Object> paused = new LinkedHashMap<>();
        paused.put("packageName", packageName);
        paused.put("type", "PAUSED");
        paused.put("at", to.toString());
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", "SCREEN_TIME");
        signal.put("observedAt", to.toString());
        signal.put("date", LocalDate.now(KST).toString());
        signal.put("appEvents", List.of(resumed, paused));
        return signal;
    }

    // ===== 판정 결과 조회 =====

    /** 그 멤버의 오늘 판정 상태(행이 없으면 null). */
    protected String todayStatusOf(UUID challengeMemberId) {
        List<String> rows = jdbc().queryForList(
                "SELECT status FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(LocalDate.now(KST)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 그 멤버의 오늘 누적 체류 시간(분). 방식 평가 행이 없으면 null.
     * "다른 챌린지 장소를 다녀왔는데 내 체류가 늘어났는지"를 상태값보다 정확하게 본다.
     */
    protected Long dwellMinutesOf(UUID challengeMemberId) {
        List<Long> rows = jdbc().queryForList(
                "SELECT JSON_EXTRACT(r.evidence, '$.dwellMinutes') FROM VerificationMethodResult r " +
                        "JOIN VerificationDaily d ON d.id = r.verificationDailyId " +
                        "WHERE d.challengeMemberId = ? AND d.targetDate = ?",
                Long.class, bytes(challengeMemberId), java.sql.Date.valueOf(LocalDate.now(KST)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 그 멤버의 오늘 방식별 evidence(요약 JSON 문자열). 없으면 null. */
    protected String todayEvidenceOf(UUID challengeMemberId) {
        List<String> rows = jdbc().queryForList(
                "SELECT r.evidence FROM VerificationMethodResult r " +
                        "JOIN VerificationDaily d ON d.id = r.verificationDailyId " +
                        "WHERE d.challengeMemberId = ? AND d.targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(LocalDate.now(KST)));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
