package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 한도를 명시적으로 비활성화한 특수 환경({@code app.challenge.concurrent-limit.enabled=false})에서
 * 동시 참여 한도가 실제로 걸리지 않는지 고정한다.
 *
 * <p>스위치를 내려둔 채 배포할 것이므로, "꺼진 상태"도 계약이다 — 켜진 상태만 테스트하면
 * 정작 출시 기간 동안의 동작이 검증되지 않은 채로 나간다. 켜진 상태는 나머지 IT 들이
 * (테스트 프로파일에서 enabled=true) 검증한다.
 */
@SpringBootTest(properties = "app.challenge.concurrent-limit.enabled=false")
@Import(TestcontainersConfiguration.class)
class ConcurrentLimitDisabledIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("한도가 꺼져 있으면 슬롯을 다 채운 사용자도 가입할 수 있다")
    void joinNotBlockedWhenDisabled() throws Exception {
        Member owner = member(uniq("off-owner"));
        Member joiner = member(uniq("off-joiner"));
        occupySlots(joiner.id(), 5);   // 한도(3)를 넘겨 둔다

        UUID target = insertChallenge(owner.id(), "STUDY", "ACTIVE", "GROUP");
        insertActiveMembership(target, owner.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(target));

        MvcResult res = mvc.perform(post("/api/v1/challenges/" + target + "/members")
                .header("Authorization", "Bearer " + joiner.token())).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("한도가 꺼져 있으면 공개 상세도 FREE_LIMIT 을 미리 띄우지 않는다")
    void detailDoesNotPreviewFreeLimitWhenDisabled() throws Exception {
        Member owner = member(uniq("off-d-owner"));
        Member viewer = member(uniq("off-d-viewer"));
        occupySlots(viewer.id(), 5);

        UUID target = insertChallenge(owner.id(), "STUDY", "ACTIVE", "GROUP");
        insertActiveMembership(target, owner.id(), "OWNER");
        jdbcTemplate.update("UPDATE challenges SET visibility = 'PUBLIC' WHERE id = ?", (Object) bytes(target));

        MvcResult res = getAuth("/api/v1/challenges/" + target, viewer.token());

        assertThat((String) read(res, "$.data.joinBlockReason")).isNotEqualTo("FREE_LIMIT");
    }
}
