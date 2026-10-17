package com.ruleup.ruleup_backend.perf;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** 멤버 목록 화면(방 홈·랭킹·멤버·스레드)이 쓰는 사용자 일괄 조회가 사용자 수와 상관없이 한 문장인지. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, SqlRecorder.Config.class})
class UserBulkLoadIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository users;
    @MockitoBean ContentModerationClient moderationClient;
    private MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }

    @Test
    @DisplayName("사용자 5명을 개인정보·관심사까지 한 문장으로 읽는다")
    void findAllByIdIsOneStatement() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var res = signup(uniq("bulk"), "일괄" + seq());
            ids.add(UUID.fromString(read(res, "$.data.user.id")));
        }

        SqlRecorder.start();
        List<User> loaded = users.findAllById(ids);
        var sql = SqlRecorder.stop();

        assertThat(loaded).hasSize(5);
        assertThat(loaded).allSatisfy(u -> {
            assertThat(u.getInformation()).isNotNull();
            assertThat(u.getInterestCategories()).isNotEmpty();
        });
        assertThat(sql).as(sql.toString()).hasSize(1);
    }
}
