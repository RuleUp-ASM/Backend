package com.ruleup.ruleup_backend.report;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReportReviewServiceIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReportReviewService service;
    @MockitoBean LlmClient llm;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        when(llm.isConfigured()).thenReturn(true);
        when(llm.generateStructured(anyString(), anyString())).thenReturn("{}");
        when(llm.parseJson(anyString(), eq(ReportReviewService.Verdict.class)))
                .thenReturn(new ReportReviewService.Verdict(true, true));
    }

    @Test
    @DisplayName("행동 위반이 아닌 유효 신고와 중복 신고는 자동 퇴장 5명 임계치에 포함되지 않는다")
    void onlyDistinctBehaviorViolationsTriggerAutoKick() throws Exception {
        Member owner = member(uniq("review-owner"));
        Member target = member(uniq("review-target"));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        insertActiveMembership(challengeId, target.id(), "MEMBER");

        List<Member> reporters = new ArrayList<>();
        for (int i = 0; i < 6; i++) reporters.add(member(uniq("review-reporter-" + i)));

        // 유효하지만 행동 위반이 아닌 네 건은 임계치와 무관하다.
        for (int i = 0; i < 4; i++)
            insertReviewed(reporters.get(i).id(), target.id(), challengeId, false, false);
        // 다른 신고자의 중복 건 역시 유효 판정이어도 임계치에서 빠진다.
        insertReviewed(reporters.get(5).id(), target.id(), challengeId, true, true);

        UUID firstBehavior = insertPending(reporters.get(0).id(), target.id(), challengeId);
        service.review(firstBehavior);
        assertThat(memberStatus(challengeId, target.id())).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT behavior_violation FROM reports WHERE id=?",
                Boolean.class, bytes(firstBehavior))).isTrue();

        // 서로 다른 신고자 다섯 명의 실제 행동 위반이 모였을 때에만 자동 퇴장한다.
        for (int i = 1; i < 5; i++) service.review(insertPending(reporters.get(i).id(), target.id(), challengeId));
        assertThat(memberStatus(challengeId, target.id())).isEqualTo("REMOVED");
        assertThat(jdbcTemplate.queryForObject("SELECT participant_count FROM challenges WHERE id=?",
                Integer.class, bytes(challengeId))).isEqualTo(0);
    }

    private UUID insertPending(UUID reporter, UUID target, UUID challenge) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reports " +
                        "(id,reporter_id,target_type,target_user_id,target_challenge_id,context_type,reason,detail) " +
                        "VALUES (?,?,'USER',?,?,'ROOM','ABUSE','구체적인 반복 행동 위반 신고입니다.')",
                bytes(id), bytes(reporter), bytes(target), bytes(challenge));
        return id;
    }

    private void insertReviewed(UUID reporter, UUID target, UUID challenge, boolean behavior, boolean duplicate) {
        jdbcTemplate.update("INSERT INTO reports " +
                        "(id,reporter_id,target_type,target_user_id,target_challenge_id,context_type,reason,detail," +
                        "review_status,behavior_violation,duplicate_report) " +
                        "VALUES (?,?,'USER',?,?,'ROOM','ABUSE','이미 검토된 신고입니다.','VALID',?,?)",
                bytes(UUID.randomUUID()), bytes(reporter), bytes(target), bytes(challenge), behavior, duplicate);
    }

    private String memberStatus(UUID challenge, UUID user) {
        return jdbcTemplate.queryForObject("SELECT status FROM challenge_members WHERE challenge_id=? AND user_id=?",
                String.class, bytes(challenge), bytes(user));
    }
}
