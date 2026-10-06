package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 챌린지 참여 서비스 지표(biz.challenge.*)가 실제 경로에서 세어지는지 — 생성 경로·초안 수정 여부·가입 경로.
 * 카운터는 전역 레지스트리에 누적되므로 전후 차이로 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ParticipationMetricsIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ChallengeMemberService memberService;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static final long TEMPLATE = 9601L;
    private static boolean fixtures;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        if (!fixtures) {
            insertAutoTemplate(TEMPLATE, "헬스장 가기", "퇴근 후 운동 습관", "EXERCISE",
                    "{\"duration_min\":{\"default\":60,\"unit\":\"min\",\"min\":10,\"max\":480}}",
                    "GPS_PRESENCE", "[\"ACCESS_FINE_LOCATION\"]");
            fixtures = true;
        }
    }

    private double count(String name, String... tags) {
        var search = meterRegistry.find(name).tags(tags).counter();
        return search == null ? 0 : search.count();
    }

    /** 추천 탭(템플릿) 초안으로 공개 그룹 방을 만든다. title 이 null 이면 초안 제목 그대로. */
    private String createFromTemplate(String token, String title) throws Exception {
        MvcResult draft = postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                Map.of("templateId", TEMPLATE));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", (String) read(draft, "$.data.draftId"));
        body.put("title", title != null ? title : (String) read(draft, "$.data.draft.title"));
        body.put("description", (String) read(draft, "$.data.draft.description"));
        body.put("category", "EXERCISE");
        body.put("mode", "GROUP");
        body.put("visibility", "PUBLIC");
        body.put("capacity", 30);
        body.put("minTier", "BRONZE");
        body.put("period", Map.of(
                "start", (String) read(draft, "$.data.draft.period.start"),
                "end", (String) read(draft, "$.data.draft.period.end")));
        body.put("weeklyCount", (Integer) read(draft, "$.data.draft.weeklyCount"));
        body.put("params", List.of());
        body.put("verification", Map.of("type", "AUTO", "method", "GPS_PRESENCE"));
        body.put("penalties", Map.of("watcher", false));
        MvcResult res = mvc.perform(post("/api/v1/challenges")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(OM.writeValueAsString(body)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return read(res, "$.data.challengeId");
    }

    @Test
    @DisplayName("추천 탭 초안 → 생성: 초안 그대로면 edited=no, 제목을 고치면 edited=yes. 초안 생성도 template 으로 센다")
    void creationCountsOriginAndEdit() throws Exception {
        Member owner = member(uniq("pm-create"));
        double drafts = count("biz.challenge.draft", "origin", "template", "result", "success");
        double untouched = count("biz.challenge.created", "origin", "template", "edited", "no");
        double edited = count("biz.challenge.created", "origin", "template", "edited", "yes");

        createFromTemplate(owner.token(), null);
        createFromTemplate(owner.token(), "내가 고친 제목");

        assertThat(count("biz.challenge.draft", "origin", "template", "result", "success")).isEqualTo(drafts + 2);
        assertThat(count("biz.challenge.created", "origin", "template", "edited", "no")).isEqualTo(untouched + 1);
        assertThat(count("biz.challenge.created", "origin", "template", "edited", "yes")).isEqualTo(edited + 1);
    }

    @Test
    @DisplayName("가입 — 탐색(직접 가입)과 초대 링크를 나눠 센다. 거절된 가입은 세지 않는다")
    void joinCountsBySource() throws Exception {
        Member owner = member(uniq("pm-owner"));
        String id = createFromTemplate(owner.token(), null);
        Member viaExplore = member(uniq("pm-explore"));
        Member viaInvite = member(uniq("pm-invite"));
        double explore = count("biz.challenge.joined", "source", "explore");
        double invite = count("biz.challenge.joined", "source", "invite");

        assertThat(postJsonAuth("/api/v1/challenges/" + id + "/members", viaExplore.token(), Map.of())
                .getResponse().getStatus()).isEqualTo(200);
        memberService.join(viaInvite.id(), UUID.fromString(id), true);
        // 이미 참여 중인 사람의 재요청은 거절된다 — 가입으로 세지 않는다
        postJsonAuth("/api/v1/challenges/" + id + "/members", viaExplore.token(), Map.of());

        assertThat(count("biz.challenge.joined", "source", "explore")).isEqualTo(explore + 1);
        assertThat(count("biz.challenge.joined", "source", "invite")).isEqualTo(invite + 1);
    }
}
