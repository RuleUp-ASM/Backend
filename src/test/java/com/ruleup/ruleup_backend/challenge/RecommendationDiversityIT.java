package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 생성 화면 추천 3건의 <b>다양성·분산</b> 계약.
 *
 * <p>고치기 전에는 3건이 거의 항상 한 카테고리로 몰렸다. 원인이 둘이었다:
 * <ul>
 *   <li>관심사 보너스가 평평한 가산점이라 한 카테고리 전체가 동점이 된다.</li>
 *   <li>동점을 {@code templateId} 오름차순으로 깨서, 그 카테고리가 세 자리를 다 먹고
 *       모든 사용자에게 늘 같은 3건이 나갔다(시드가 판정 모델별 id 블록으로 묶여 있어 더 심했다).</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecommendationDiversityIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    /** 카테고리 4종 × 3개 = 12개. 한 카테고리가 3건을 다 먹을 수 있는 상황을 일부러 만든다. */
    private static final String[][] FIXTURES = {
            {"7001", "EXERCISE"}, {"7002", "EXERCISE"}, {"7003", "EXERCISE"},
            {"7004", "STUDY"}, {"7005", "STUDY"}, {"7006", "STUDY"},
            {"7007", "DETOX"}, {"7008", "DETOX"}, {"7009", "DETOX"},
            {"7010", "READING"}, {"7011", "READING"}, {"7012", "READING"},
    };
    private static boolean fixtures;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        if (!fixtures) {
            for (String[] f : FIXTURES) {
                insertAutoTemplate(Long.parseLong(f[0]), "루틴" + f[0], "설명", f[1],
                        "{\"duration_min\":{\"default\":60,\"unit\":\"min\",\"min\":10,\"max\":480}}",
                        "GPS_PRESENCE", "[\"ACCESS_FINE_LOCATION\"]");
            }
            fixtures = true;
        }
    }

    private void setInterests(UUID userId, String... categories) {
        jdbcTemplate.update("DELETE FROM user_interests WHERE user_id = ?", (Object) bytes(userId));
        for (String c : categories) {
            jdbcTemplate.update("INSERT INTO user_interests (user_id, category) VALUES (?, ?)",
                    bytes(userId), c);
        }
    }

    private List<String> categoriesOf(String token) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges/recommendations", token);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        List<Map<String, Object>> items = read(res, "$.data.items");
        assertThat(items).hasSize(3);
        return items.stream().map(i -> (String) i.get("category")).toList();
    }

    private List<Integer> templateIdsOf(String token) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges/recommendations", token);
        List<Map<String, Object>> items = read(res, "$.data.items");
        return items.stream().map(i -> (Integer) i.get("templateId")).toList();
    }

    // =====================================================================
    @Nested
    @DisplayName("카테고리 다양성")
    class Diversity {

        @Test
        @DisplayName("관심사가 없어도 3건이 서로 다른 카테고리로 나온다")
        void distinctCategoriesWithoutInterests() throws Exception {
            Member m = member(uniq("div-none"));

            List<String> categories = categoriesOf(m.token());

            assertThat(new HashSet<>(categories)).hasSize(3);
        }

        @Test
        @DisplayName("관심사가 한 개여도 3건이 그 카테고리로만 채워지지 않는다")
        void singleInterestDoesNotMonopolize() throws Exception {
            Member m = member(uniq("div-one"));
            setInterests(m.id(), "EXERCISE");

            List<String> categories = categoriesOf(m.token());

            // 고치기 전에는 EXERCISE 3건이었다(관심사 보너스 동점 → id 순).
            assertThat(categories).contains("EXERCISE");
            assertThat(new HashSet<>(categories)).hasSize(3);
        }

        @Test
        @DisplayName("진행 중 카테고리를 빼고 나면 카테고리가 모자라도 3건은 보장한다")
        void guaranteesThreeEvenWhenCategoriesRunOut() throws Exception {
            Member m = member(uniq("div-guarantee"));
            // 진행 중인 방으로 세 카테고리를 막아 남는 카테고리를 하나로 줄인다.
            for (String category : List.of("EXERCISE", "STUDY", "DETOX")) {
                UUID id = insertChallenge(m.id(), category, "ACTIVE", "SOLO");
                insertActiveMembership(id, m.id(), "OWNER");
            }

            List<String> categories = categoriesOf(m.token());

            assertThat(categories).hasSize(3);   // 다양성보다 3건 보장이 우선
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("노출 분산 — 사용자마다 다르게, 같은 사용자에겐 항상 같게")
    class Spread {

        @Test
        @DisplayName("같은 사용자가 다시 불러도 결과가 흔들리지 않는다")
        void stableForSameUser() throws Exception {
            Member m = member(uniq("spread-stable"));

            assertThat(templateIdsOf(m.token())).isEqualTo(templateIdsOf(m.token()));
        }

        @Test
        @DisplayName("사용자마다 서로 다른 루틴이 노출된다 — 전원 같은 3건이 나가지 않는다")
        void variesAcrossUsers() throws Exception {
            Set<List<Integer>> distinct = new HashSet<>();
            for (int i = 0; i < 8; i++) {
                distinct.add(templateIdsOf(member(uniq("spread-" + i)).token()));
            }

            // 고치기 전에는 templateId 오름차순이라 8명 전원이 같은 3건을 받았다.
            assertThat(distinct).hasSizeGreaterThan(1);
        }
    }
}
