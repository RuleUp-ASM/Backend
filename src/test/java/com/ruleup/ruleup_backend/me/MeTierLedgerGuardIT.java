package com.ruleup.ruleup_backend.me;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 손상된 원장 1행이 <b>내 티어 화면 전체</b>를 무너뜨리지 않는다 — QA 제보(2026-09-16)에서 나온 계약.
 *
 * <p>제보는 「{@code balance_after} 가 직전 잔액+{@code applied_delta} 와 어긋나면 500」이었으나
 * 재현해 보면 그건 아니었다. <b>이 화면은 원장 정합성을 검증하지 않는다</b> — 요약 테이블과 최근
 * 변동을 읽어 조립만 한다. 잔액 연속성 검증은 매일 05:00 KST 감사({@code ScoreHealth})의 몫이고,
 * 그 자리를 요청 경로로 옮기면 조회가 사용자 잠금을 잡게 된다.
 *
 * <p>실제 500 은 {@code incident_type} 이 비어 있는 {@code INCIDENT} 행에서 났다 —
 * {@code ScoreChangeView.displayReason} 의 switch 가 NPE 로 터지면서 나머지 9건까지 같이 죽었다.
 * 정상 쓰기 경로로는 만들 수 없는 행이지만(검증이 강제한다), 손으로 만진 행·레거시 행은 존재한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MeTierLedgerGuardIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean ContentModerationClient moderationClient;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        when(moderationClient.moderateNickname(anyString())).thenReturn(ModerationResult.UNAVAILABLE);
        when(moderationClient.moderateImage(anyString())).thenReturn(ModerationResult.UNAVAILABLE);
        when(moderationClient.moderateImageBytes(any(), anyString()))
                .thenReturn(ModerationResult.UNAVAILABLE);
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("사건 종류가 비어 있는 원장 행은 그 줄만 빠지고 화면은 그대로 뜬다")
    void incident_without_type_is_skipped_not_fatal() throws Exception {
        Member me = member("guard-incident");
        insertRow(me.id(), "DAILY_SUCCESS", null, 8, 18);
        insertRow(me.id(), "INCIDENT", null, -5, 13);   // 손상: 무슨 사건인지 알 수 없다

        assertThat(status("/api/v1/me/tier", me)).isEqualTo(200);
        // 멀쩡한 행은 남고 손상된 행만 빠진다 — 화면 전체가 비지 않는다.
        assertThat(reasons("/api/v1/me/tier", me)).containsExactly("CYCLE_SUCCESS");

        // 「전체 보기」도 같은 조립기를 쓴다 — 한쪽만 고치면 두 화면이 갈린다.
        assertThat(status("/api/v1/me/tier/changes", me)).isEqualTo(200);
        assertThat(reasons("/api/v1/me/tier/changes", me)).containsExactly("CYCLE_SUCCESS");
    }

    @Test
    @DisplayName("사건 종류가 채워진 행은 종류대로 표기된다")
    void incident_with_type_is_rendered() throws Exception {
        Member me = member("guard-incident-ok");
        insertRow(me.id(), "INCIDENT", "VOLUNTARY_LEAVE", -5, 5);

        assertThat(reasons("/api/v1/me/tier", me)).containsExactly("LEAVE");
    }

    @Test
    @DisplayName("잔액 연속성이 깨진 원장으로는 500 이 나지 않는다 — 이 화면은 원장을 검증하지 않는다")
    void broken_balance_continuity_is_not_validated_on_read() throws Exception {
        Member me = member("guard-balance");
        insertRow(me.id(), "DAILY_SUCCESS", null, 8, 18);
        insertRow(me.id(), "SIGNUP", null, 10, 10);          // 제보된 복제 행(화면에는 안 나온다)
        insertRow(me.id(), "CONFIRMED_MISS", null, -5, 999);  // 18 - 5 != 999

        assertThat(status("/api/v1/me/tier", me)).isEqualTo(200);
        assertThat(status("/api/v1/me/tier/changes", me)).isEqualTo(200);
    }

    // ===== 도구 =====

    private int status(String url, Member me) throws Exception {
        return getAuth(url, me.token()).getResponse().getStatus();
    }

    /** 응답의 {@code recentChanges[].reason} / {@code items[].reason} 목록. */
    private List<String> reasons(String url, Member me) throws Exception {
        MvcResult res = getAuth(url, me.token());
        List<Map<String, Object>> rows = read(res, url.endsWith("/changes")
                ? "$.data.items" : "$.data.recentChanges");
        return rows.stream().map(r -> (String) r.get("reason")).toList();
    }

    /** 원장 1행. {@code MyPageContractIT#insertScoreEvent} 와 같은 형태다. */
    private void insertRow(UUID userId, String reason, String incidentType, long delta, long balanceAfter) {
        String tier = com.ruleup.ruleup_backend.score.domain.TierBands.of(balanceAfter).name();
        jdbc().update("INSERT INTO score_transactions " +
                        "(id, user_id, raw_delta, limited_delta, applied_delta, balance_after, reason, " +
                        " challenge_id, incident_type, idempotency_key, created_at, entry_kind, source_type, " +
                        " processing_key, effective_at, effective_order, actual_tier_after, display_tier_after, payload_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, UTC_TIMESTAMP(3), 'RESULT', 'TEST', ?, " +
                        " UTC_TIMESTAMP(3), X'00', ?, ?, '{}')",
                bytes(UUID.randomUUID()), bytes(userId), delta, delta, delta, balanceAfter, reason,
                incidentType, uniq("idem"), uniq("processing"), tier, tier);
    }
}
