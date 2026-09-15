package com.ruleup.ruleup_backend.verification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ruleup.ruleup_backend.user.domain.User;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Priority ordered runtime policy. Missing device facts never satisfy a condition. */
@Service
@DependsOnDatabaseInitialization
public class DeviceSyncPolicyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final Cache<String, List<Policy>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5)).build();

    public DeviceSyncPolicyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void validateFallback() { policies(); }

    public int forUser(User user) {
        Map<String, Object> facts = new HashMap<>();
        if (user != null) {
            facts.put("platform", user.getPlatform() == null ? null : user.getPlatform().name());
            facts.put("sdkInt", user.getSdkInt());
            facts.put("ramMb", user.getRamMb());
            facts.put("lowRam", user.getLowRam());
            facts.put("model", user.getDeviceModel());
        }
        return resolve(facts);
    }

    public int resolve(Map<String, ?> facts) {
        return policies().stream().filter(p -> matches(p.conditions(), facts)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No device sync fallback policy")).interval();
    }

    private List<Policy> policies() {
        return cache.get("active", key -> {
            List<Policy> rows = jdbc.query("SELECT priority,match_condition,flush_interval_sec FROM device_sync_policies " +
                    "WHERE is_active=TRUE ORDER BY priority DESC", (rs, row) -> {
                try {
                    return new Policy(rs.getInt(1), json.readValue(rs.getString(2), Map.class), rs.getInt(3));
                } catch (Exception e) { throw new IllegalStateException("Invalid device sync policy", e); }
            });
            if (rows.stream().noneMatch(p -> p.priority() == 0 && p.conditions().isEmpty()))
                throw new IllegalStateException("Active priority 0 fallback is required");
            return rows;
        });
    }

    static boolean matches(Map<String, ?> conditions, Map<String, ?> facts) {
        for (var entry : conditions.entrySet()) {
            String key = entry.getKey();
            Object actual = facts == null ? null : facts.get(switch (key) {
                case "maxSdk" -> "sdkInt";
                case "minRamMb" -> "ramMb";
                case "modelPattern" -> "model";
                default -> key;
            });
            if (actual == null) return false;
            Object expected = entry.getValue();
            boolean match = switch (key) {
                case "maxSdk" -> actual instanceof Number a && expected instanceof Number n && a.intValue() <= n.intValue();
                case "minRamMb" -> actual instanceof Number a && expected instanceof Number n && a.intValue() >= n.intValue();
                case "modelPattern" -> Pattern.matches(expected.toString(), actual.toString());
                case "platform", "lowRam" -> expected.equals(actual);
                default -> false;
            };
            if (!match) return false;
        }
        return true;
    }

    private record Policy(int priority, Map<String, ?> conditions, int interval) {}
}
