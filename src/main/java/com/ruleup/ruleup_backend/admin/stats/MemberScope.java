package com.ruleup.ruleup_backend.admin.stats;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 통계가 「회원」으로 세는 범위 — users 를 {@code u} 로 부를 때 WHERE 에 그대로 붙이는 SQL 조각.
 *
 * <p>회원 구성({@link MemberProfileService})과 서비스 이용 지표
 * ({@link com.ruleup.ruleup_backend.observability.usage.UsageStatsService})가 같은 사람을 세도록 한 곳에 둔다.
 * <ul>
 *   <li>{@link #members()} — 탈퇴하지 않은 일반 회원. 운영자와 {@code app.admin.stats-excluded-user-ids}(개발자 시험 계정)는 뺀다.</li>
 *   <li>{@link #everyone()} — 운영자·제외 계정만 뺀다. 탈퇴자를 셀 때 쓴다.</li>
 * </ul>
 */
public record MemberScope(String everyone, String members) {

    public static MemberScope of(String excludedUserIds) {
        List<UUID> excluded = Arrays.stream(excludedUserIds == null ? new String[0] : excludedUserIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(UUID::fromString).toList();
        // 제외 ID 는 UUID 로 파싱한 값만 들어가므로 문자열로 이어 붙여도 안전하다.
        String everyone = "u.role = 'MEMBER'" + (excluded.isEmpty() ? "" : excluded.stream().map(id -> "'" + id + "'")
                .collect(Collectors.joining(", ", " AND BIN_TO_UUID(u.id) NOT IN (", ")")));
        return new MemberScope(everyone, everyone + " AND u.status <> 'WITHDRAWN' AND u.deleted_at IS NULL");
    }
}
