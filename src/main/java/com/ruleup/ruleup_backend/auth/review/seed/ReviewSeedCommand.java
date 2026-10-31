package com.ruleup.ruleup_backend.auth.review.seed;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 시드 일회성 태스크의 인자 (심사 계정 테크 스펙 5-8).
 *
 * <ul>
 *   <li>{@code --mode=create --accounts=N --evaluation-end=YYYY-MM-DD} — 새 회차. round 는 생성 모드가 발급한다</li>
 *   <li>{@code --mode=reset [--confirm-round=R]} — 같은 회차 원상 복구. round 는 서버와 같은 설정에서 읽는다</li>
 * </ul>
 * 어느 모드도 round 를 고르는 인자를 받지 않는다 — 지난 회차를 실수로 고르는 길을 처음부터 두지 않는다.
 * 모르는 인자나 다른 모드의 인자가 섞이면 거절한다(오타가 기본값으로 흘러가지 않게).
 */
public sealed interface ReviewSeedCommand {

    int MAX_ACCOUNTS = 10;

    record Create(int accounts, LocalDate evaluationEnd) implements ReviewSeedCommand {}

    record Reset(String confirmRound) implements ReviewSeedCommand {}

    static ReviewSeedCommand parse(String... args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--") || !arg.contains("="))
                throw new IllegalArgumentException("인자는 --이름=값 형식이어야 한다: " + arg);
            String key = arg.substring(2, arg.indexOf('='));
            String value = arg.substring(arg.indexOf('=') + 1).trim();
            if (opts.put(key, value) != null) throw new IllegalArgumentException("같은 인자가 두 번 왔다: --" + key);
        }
        String mode = opts.remove("mode");
        if (mode == null) throw new IllegalArgumentException("--mode=create 또는 --mode=reset 이 필요하다");
        return switch (mode) {
            case "create" -> {
                only(opts, Set.of("accounts", "evaluation-end"), "create");
                yield new Create(accounts(opts.get("accounts")), date(opts.get("evaluation-end")));
            }
            case "reset" -> {
                only(opts, Set.of("confirm-round"), "reset");
                String confirm = opts.get("confirm-round");
                yield new Reset(confirm == null || confirm.isBlank() ? null : confirm);
            }
            default -> throw new IllegalArgumentException("모르는 모드: " + mode);
        };
    }

    private static void only(Map<String, String> opts, Set<String> allowed, String mode) {
        for (String key : opts.keySet()) {
            if (!allowed.contains(key))
                throw new IllegalArgumentException(mode + " 모드가 받지 않는 인자: --" + key);
        }
    }

    private static int accounts(String raw) {
        if (raw == null) throw new IllegalArgumentException("--accounts 가 필요하다");
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--accounts 는 숫자여야 한다: " + raw);
        }
        if (n < 1 || n > MAX_ACCOUNTS)
            throw new IllegalArgumentException("--accounts 는 1~" + MAX_ACCOUNTS + " 이어야 한다: " + n);
        return n;
    }

    private static LocalDate date(String raw) {
        if (raw == null) throw new IllegalArgumentException("--evaluation-end 가 필요하다");
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--evaluation-end 는 YYYY-MM-DD 여야 한다: " + raw);
        }
    }
}
