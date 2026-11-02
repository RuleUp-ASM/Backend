package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.sanction.ReviewAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 심사 계정 판정 — 코드 로그인·재발급·푸시 등록·종료 스케줄러가 모두 이것 하나만 부른다
 * (심사 계정 테크 스펙 5-1 · 5-3 · 5-5).
 *
 * <ul>
 *   <li><b>스위치</b> = {@code enabled} 이고 현재 시각이 {@code expiresAt} 전. 부를 때마다 시계 빈과 비교한다 —
 *       재배포 없이 자동으로 닫힌다. 스위치를 읽는 곳은 {@link #isSwitchOn()} 하나다</li>
 *   <li><b>코드 계정</b> = {@code codes} 의 모든 항목(폐기 포함). 더미 멤버·Play 심사 계정은 코드가 없다</li>
 *   <li><b>세션 종료</b> = 코드 계정이고 (스위치 꺼짐 또는 코드 폐기)</li>
 *   <li><b>회차</b> = 계정의 바뀌지 않는 {@code oauth_subject} {@code ruleup-review-{round}-NN} 에 적힌 값.
 *       이번 회차 계정만 코드 로그인 대상이다</li>
 * </ul>
 *
 * <p>설정 오류는 기동을 멈춘다 — 조용히 무시하면 심사 당일에야 알게 되거나, 일반 유저 계정에 코드 로그인이 열린다.
 * 매핑 검사는 스위치가 꺼져 있어도 한다.
 */
@Component
public class ReviewAccountPolicy {

    private static final Logger log = LoggerFactory.getLogger(ReviewAccountPolicy.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 한 회차의 최대 길이 — round 날짜(00:00 KST)부터 expiresAt 까지. */
    public static final Duration MAX_REVIEW_LENGTH = Duration.ofDays(60);
    private static final Pattern ROUND = Pattern.compile("(\\d{8})\\d{2}");
    private static final Pattern REVIEW_SUBJECT = Pattern.compile("ruleup-review-(\\d{10})-\\d{2}");
    private static final DateTimeFormatter ROUND_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Clock clock;
    private final boolean enabled;
    private final Instant expiresAt;
    private final String round;
    /** 살아 있는 코드만 — 해시 → 항목. */
    private final Map<String, ReviewLoginCode> liveByHash;
    /** 설정 순서를 지킨다 — 종료 스케줄러가 태스크마다 같은 순서로 계정을 잠근다. */
    private final Set<UUID> codeAccountIds;
    private final Set<UUID> revokedCodeAccountIds;

    public ReviewAccountPolicy(ReviewAccounts reviewAccounts, ReviewLoginProperties props, Clock clock) {
        this.clock = clock;
        this.enabled = props.enabled();
        List<ReviewLoginCode> codes = parseCodes(props.codes());
        this.expiresAt = parseExpiry(props.expiresAt());
        this.round = blankToNull(props.round());

        Map<String, ReviewLoginCode> byHash = new HashMap<>();
        Set<UUID> all = new LinkedHashSet<>();
        Set<UUID> revoked = new LinkedHashSet<>();
        Map<UUID, String> liveLabelByUser = new HashMap<>();
        Set<String> hashes = new java.util.HashSet<>();
        for (ReviewLoginCode code : codes) {
            if (!reviewAccounts.isExempt(code.userId()))
                throw new IllegalStateException("심사 코드 " + code.label() + " 의 userId 가 REVIEW_ACCOUNT_IDS 에 없다");
            if (!hashes.add(code.codeHash()))
                throw new IllegalStateException("심사 코드 " + code.label() + " 의 해시가 다른 항목과 같다");
            if (code.revoked()) {
                revoked.add(code.userId());
            } else {
                String other = liveLabelByUser.putIfAbsent(code.userId(), code.label());
                if (other != null)
                    throw new IllegalStateException("심사 코드 " + code.label() + " 와 " + other + " 가 같은 계정에 묶였다 — 1인 1계정");
                byHash.put(code.codeHash(), code);
            }
            all.add(code.userId());
        }
        for (ReviewLoginCode code : codes) {
            if (!code.revoked() && revoked.contains(code.userId()))
                throw new IllegalStateException("심사 코드 " + code.label()
                        + " 가 폐기된 항목의 계정에 묶였다 — 새 코드는 예비 계정에 묶는다");
        }
        // 켜 둔 채 만료가 빠지면 「꺼짐」으로 판정돼 종료 스케줄러가 기동 직후 코드 계정을 탈퇴시킨다 — 준비한 방에서
        // 나가고 방장이 봇으로 바뀐다. 설정 한 줄 누락이 되돌리기 어려운 결과가 되므로 기동을 멈춘다.
        // enabled=false 는 막지 않는다 — 긴급 차단 재배포가 기동 실패로 막히면 안 된다(그때의 탈퇴는 의도된 종료다).
        if (enabled && !codes.isEmpty() && this.expiresAt == null)
            throw new IllegalStateException("심사 코드 로그인을 켰는데(enabled=true) expiresAt 이 없다");
        if (!codes.isEmpty()) validateRound(this.round, this.expiresAt);
        else if (this.round != null) parseRoundDate(this.round);

        this.liveByHash = Map.copyOf(byHash);
        this.codeAccountIds = Collections.unmodifiableSet(all);
        this.revokedCodeAccountIds = Collections.unmodifiableSet(revoked);

        if (!codes.isEmpty()) {
            // 켜져 있다는 사실은 기동 로그에 남긴다 — 이런 예외는 잊히는 것이 가장 위험하다. 코드·해시는 남기지 않는다.
            log.warn("심사 코드 로그인 설정 enabled={} round={} expiresAt={} codes={} revoked={}",
                    enabled, round, expiresAt, codes.size(), revoked.size());
        }
    }

    // ===== 판정 =====

    /** 심사 기능이 켜져 있는가 — enabled 이고 expiresAt 전. 부를 때마다 현재 시각과 비교한다. */
    public boolean isSwitchOn() {
        return enabled && expiresAt != null && clock.instant().isBefore(expiresAt);
    }

    /** 입력 코드를 정규화·해시해 살아 있는 항목과 대조한다. 폐기된 코드·빈 값은 맞지 않는다. */
    public Optional<ReviewLoginCode> match(String rawCode) {
        String normalized = ReviewCodes.normalize(rawCode);
        if (normalized.isEmpty()) return Optional.empty();
        return Optional.ofNullable(liveByHash.get(ReviewCodes.sha256Hex(normalized)));
    }

    /** codes 의 모든 계정(폐기 포함) — 스위치가 꺼지면 종료 대상. */
    public Set<UUID> codeAccountIds() {
        return codeAccountIds;
    }

    /** 코드가 폐기된 계정 — 스위치가 켜져 있어도 세션을 끊는다. */
    public Set<UUID> revokedCodeAccountIds() {
        return revokedCodeAccountIds;
    }

    /** 이번 회차 — 시드 리셋이 대상 회차를 인자가 아니라 서버와 같은 설정에서 읽는다. 없으면 null. */
    public String round() {
        return round;
    }

    /** 심사 종료 시각(평가 마지막 날 다음 날 00:00 KST). 없으면 null. */
    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean isCodeAccount(UUID userId) {
        return userId != null && codeAccountIds.contains(userId);
    }

    /** 이 계정의 코드 세션이 끝났는가 — 코드 계정이고 (코드 폐기 또는 스위치 꺼짐). */
    public boolean isCodeSessionEnded(UUID userId) {
        if (!isCodeAccount(userId)) return false;
        return revokedCodeAccountIds.contains(userId) || !isSwitchOn();
    }

    /** subject 에 적힌 회차가 이번 round 와 같은가 — 지난 회차 계정은 탈퇴 여부와 무관하게 아니다. */
    public boolean isCurrentRoundAccount(String oauthSubject) {
        if (round == null || oauthSubject == null) return false;
        Matcher m = REVIEW_SUBJECT.matcher(oauthSubject);
        return m.matches() && m.group(1).equals(round);
    }

    // ===== 기동 검증 =====

    private static List<ReviewLoginCode> parseCodes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (RuntimeException e) {
            throw new IllegalStateException("심사 코드 설정(codes)을 JSON 으로 읽을 수 없다", e);
        }
        if (root == null || !root.isArray())
            throw new IllegalStateException("심사 코드 설정(codes)은 JSON 배열이어야 한다");
        Map<Integer, ReviewLoginCode> parsed = new LinkedHashMap<>();
        int i = 0;
        for (JsonNode node : root) {
            String label = text(node, "label");
            String where = label != null ? label : "#" + i;
            String hash = text(node, "codeHash");
            String userId = text(node, "userId");
            if (label == null || hash == null || userId == null)
                throw new IllegalStateException("심사 코드 " + where + " 에 label·codeHash·userId 가 모두 있어야 한다");
            if (!hash.matches("[0-9a-f]{64}"))
                throw new IllegalStateException("심사 코드 " + where + " 의 codeHash 는 SHA-256 소문자 hex 64자여야 한다");
            UUID id;
            try {
                id = UUID.fromString(userId);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("심사 코드 " + where + " 의 userId 를 읽을 수 없다", e);
            }
            JsonNode revoked = node.get("revoked");
            if (revoked != null && !revoked.isNull() && !revoked.isBoolean())
                throw new IllegalStateException("심사 코드 " + where + " 의 revoked 는 true/false 여야 한다");
            parsed.put(i++, new ReviewLoginCode(label, hash, id, revoked != null && revoked.asBoolean(false)));
        }
        return List.copyOf(parsed.values());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isString()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Instant parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("심사 종료 시각(expiresAt)을 읽을 수 없다: " + raw, e);
        }
    }

    /**
     * round 형식과 기간 — 날짜가 expiresAt 전이고, 거기서 {@link #MAX_REVIEW_LENGTH} 안이어야 한다.
     * 지난 설정을 복사해 expiresAt 만 늦추면 여기서 멈춘다.
     */
    private static void validateRound(String round, Instant expiresAt) {
        if (round == null) throw new IllegalStateException("심사 코드가 있는데 round 가 없다");
        LocalDate date = parseRoundDate(round);
        if (expiresAt == null) return;   // enabled=false(긴급 차단)일 때만 여기 온다 — 켤 때 다시 검사된다
        Instant start = date.atStartOfDay(KST).toInstant();
        if (!start.isBefore(expiresAt))
            throw new IllegalStateException("심사 round " + round + " 의 날짜가 expiresAt 보다 늦거나 같다");
        if (expiresAt.isAfter(start.plus(MAX_REVIEW_LENGTH)))
            throw new IllegalStateException("심사 round " + round + " 의 날짜에서 expiresAt 까지 "
                    + MAX_REVIEW_LENGTH.toDays() + "일을 넘는다 — 새 회차는 시드 생성 모드로 round 를 새로 받는다");
    }

    private static LocalDate parseRoundDate(String round) {
        Matcher m = ROUND.matcher(round);
        if (!m.matches())
            throw new IllegalStateException("심사 round 는 yyyyMMdd + 순번 2자리여야 한다: " + round);
        try {
            return LocalDate.parse(m.group(1), ROUND_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("심사 round 의 날짜가 없는 날짜다: " + round, e);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
