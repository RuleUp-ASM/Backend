package com.ruleup.ruleup_backend.observability.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 새 CS 문의를 운영 Slack {@code #ruleup-alert} 에 알린다(2026-11-11). 경로는 {@link OpsSlackChannel} — 가입 알림과 같다.
 *
 * <p><b>문의 접수를 흔들지 않는다.</b> 접수 트랜잭션이 커밋된 뒤 비동기로 보내고, 실패는 로그만 남긴다.
 * 알림 한두 건 유실은 수용한다 — 원본은 관리자 콘솔 문의 큐다.
 *
 * <p><b>본문은 싣지 않는다.</b> 문의에는 연락처·계정 사정 같은 개인 사연이 들어오고, Slack 은 콘솔보다 넓게 읽힌다.
 * 분류·미답 건수·시각·문의 ID 만 보내고 내용은 콘솔에서 연다(콘솔 열람은 감사 로그에 남는다).
 */
@Component
public class InquirySlackAlert {

    private static final Logger log = LoggerFactory.getLogger(InquirySlackAlert.class);
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    /** 콘솔 문의 화면 — `?inquiry=` 로 상세가 열린다. 콘솔은 prod 를 본다(stg 는 사이드바 「보는 서버」). */
    private static final String CONSOLE = "https://admin.ruleup.co.kr/inquiries?inquiry=";

    private final OpsSlackChannel slack;
    private final JdbcTemplate jdbc;

    public InquirySlackAlert(OpsSlackChannel slack, JdbcTemplate jdbc) {
        this.slack = slack;
        this.jdbc = jdbc;
    }

    /** 문의 접수가 커밋된 뒤 부른다. */
    @Async
    public void received(String inquiryId, String category, int imageCount, Instant at) {
        if (!slack.enabled()) return;
        try {
            Long open = jdbc.queryForObject("SELECT COUNT(*) FROM inquiries WHERE status = 'RECEIVED'", Long.class);
            slack.post(title(slack.env()), description(category, imageCount, open, at, inquiryId));
        } catch (RuntimeException e) {
            log.warn("inquiry_slack_alert_failed inquiry={} cause={}", inquiryId, e.toString());
        }
    }

    static String title(String env) {
        return ":speech_balloon: [" + env + "] 새 문의";
    }

    static String description(String category, int imageCount, Long open, Instant at, String inquiryId) {
        // 링크는 맨 URL 로 둔다 — Slack 이 알아서 링크로 만들고, Amazon Q 마크다운의 <url|text> 해석에 기대지 않는다.
        return "%s%s · 답변 대기 %s건\n%s KST · %s%s".formatted(
                categoryLabel(category), imageCount > 0 ? " · 사진 " + imageCount + "장" : "",
                open == null ? "?" : "%,d".formatted(open), AT.format(at), CONSOLE, inquiryId);
    }

    /** 콘솔(AdminPage INQUIRY_CATEGORY_LABEL)과 같은 이름. */
    static String categoryLabel(String category) {
        if (category == null) return "분류 없음";
        return switch (category) {
            case "VERIFICATION" -> "인증 · 판정";
            case "DEVICE_PERMISSION" -> "권한 · 기기 연동";
            case "CHALLENGE_GROUP" -> "챌린지 · 그룹";
            case "ACCOUNT_LOGIN" -> "계정 · 로그인";
            case "REPORT_SANCTION" -> "신고 · 제재";
            case "ERROR_ETC" -> "오류 · 제안 · 기타";
            default -> category;
        };
    }
}
