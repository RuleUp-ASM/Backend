package com.ruleup.ruleup_backend.observability.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 보안상 이상한 일을 운영 Slack {@code #ruleup-alert} 에 맥락과 함께 알린다. 경로는 {@link OpsSlackChannel}.
 *
 * <p>CloudWatch 경보(SEC-05)는 「몇 건」만 알려 로그·DB 를 뒤져야 판단할 수 있었다. 여기서는 누가, 회전 뒤 얼마 만에,
 * 왜 이상한지를 한 메시지에 싣는다. 개인정보는 싣지 않는다 — 사용자 ID(관리자 콘솔 조회용)만.
 *
 * <p>요청을 흔들지 않는다 — 비동기로 보내고 실패는 로그만 남긴다. 공격자가 같은 일을 반복해 채널을 덮지 못하게
 * 태스크마다 {@link #MAX_PER_WINDOW}건/{@link #WINDOW} 로 자른다(넘친 건은 로그·지표에만 남는다).
 */
@Component
public class SecuritySlackAlert {

    private static final Logger log = LoggerFactory.getLogger(SecuritySlackAlert.class);
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    static final int MAX_PER_WINDOW = 10;
    static final Duration WINDOW = Duration.ofHours(1);
    /** 노션 런북 RB-05(보안 이벤트) — 경보 설명과 같은 링크. */
    private static final String RUNBOOK = "https://app.notion.com/p/3f5ad4145fb6811da317e294cf339757";

    /** 회전 유예에 들지 못한 refresh 토큰 재사용 — 왜 유예하지 않았는지. */
    public enum RefreshReuse {
        /** 후속 토큰이 이미 다시 회전됐다 — 두 곳이 같은 세션을 쓰고 있다. 탈취를 가장 강하게 시사한다. */
        SUCCESSOR_ROTATED(":rotating_light:", "탈취 의심",
                "새 토큰이 이미 갱신에 쓰인 뒤 옛 토큰이 왔다 — 두 곳이 같은 세션을 쓰고 있다"),
        /** 로그아웃·다른 기기 로그인·정지·탈퇴·이전 재사용 감지로 이미 끝난 세션의 토큰. 옛 기기의 재시도일 때가 많다. */
        SESSION_ENDED(":warning:", "확인 필요",
                "로그아웃·다른 기기 로그인·정지 등으로 이미 끝난 세션의 토큰이 왔다"),
        /** 후속 토큰은 안 쓰였는데 유예 창(또는 토큰 만료)을 넘겼다 — 앱이 갱신 응답을 잃었을 가능성이 크다. */
        GRACE_EXPIRED(":warning:", "확인 필요",
                "회전 뒤 유예 창(7일)이 지나 옛 토큰이 왔다 — 앱이 갱신 응답을 잃었을 가능성이 크다");

        final String emoji;
        final String label;
        final String reason;

        RefreshReuse(String emoji, String label, String reason) {
            this.emoji = emoji;
            this.label = label;
            this.reason = reason;
        }
    }

    private final OpsSlackChannel slack;
    private Instant windowStart = Instant.EPOCH;
    private int sentInWindow;

    public SecuritySlackAlert(OpsSlackChannel slack) {
        this.slack = slack;
    }

    /** refresh 토큰 재사용으로 세션을 끊었다. 같은 토큰의 첫 감지에서만 부른다. */
    @Async
    public void refreshReuse(String userId, RefreshReuse kind, Duration sinceRevoked) {
        if (!slack.enabled() || !admit(Instant.now())) return;
        try {
            slack.post(title(slack.env(), kind), description(kind, sinceRevoked, Instant.now(), userId));
        } catch (RuntimeException e) {
            log.warn("security_slack_alert_failed kind={} cause={}", kind, e.toString());
        }
    }

    /** 창마다 상한까지만 통과시킨다. 상한에 처음 닿을 때 한 번 로그를 남긴다. */
    synchronized boolean admit(Instant now) {
        if (now.isAfter(windowStart.plus(WINDOW))) {
            windowStart = now;
            sentInWindow = 0;
        }
        if (sentInWindow >= MAX_PER_WINDOW) return false;
        if (++sentInWindow == MAX_PER_WINDOW) {
            log.warn("security_slack_alert_capped max={} window={} — 이 창의 나머지는 로그·지표에만 남는다", MAX_PER_WINDOW, WINDOW);
        }
        return true;
    }

    static String title(String env, RefreshReuse kind) {
        return "%s [%s] refresh 토큰 재사용 — %s".formatted(kind.emoji, env, kind.label);
    }

    static String description(RefreshReuse kind, Duration sinceRevoked, Instant at, String userId) {
        return "%s\n옛 토큰 폐기 뒤 %s · 세션 전체를 끊었다(재로그인 필요)\n%s KST · `%s`\n런북 RB-05: %s".formatted(
                kind.reason, human(sinceRevoked), AT.format(at), userId, RUNBOOK);
    }

    static String human(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 60) return s + "초";
        if (s < 3600) return (s / 60) + "분";
        if (s < 86400) return (s / 3600) + "시간 " + (s % 3600 / 60) + "분";
        return (s / 86400) + "일 " + (s % 86400 / 3600) + "시간";
    }
}
