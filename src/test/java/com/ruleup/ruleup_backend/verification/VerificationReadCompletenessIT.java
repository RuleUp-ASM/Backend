package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.service.VerificationSignalReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 원본을 <b>정말 전량</b> 읽는지 (백엔드 4-3 「현재 상태 평가」).
 *
 * <p>판정이 전량 재평가로 바뀐 뒤에는, 한 페이지만 읽고 자르는 것이 곧 <b>틀린 판정</b>이다.
 * 잘린 쪽에 위반 신호가 있으면 규칙 지키기형이 잘못 성공하고, 사용 구간이 잘리면 목표
 * 달성형이 실제보다 모자라 보인다. 페이지 크기(5,000)를 넘는 하루가 그대로 합산되는지 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationReadCompletenessIT extends VerificationApiSupport {

    /** 리더의 한 페이지 크기보다 확실히 큰 수. */
    private static final int ROWS = 5_200;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationSignalReader reader;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("[P1] 페이지 크기를 넘는 하루도 빠짐없이 읽힌다")
    void aDayLargerThanOnePageIsReadInFull() throws Exception {
        Member me = member(uniq("read-full"));
        LocalDate today = LocalDate.now(KST);

        // 앱 사용 이벤트를 페이지 크기 이상으로 직접 적재한다(sync 한 번의 신호 상한과 무관하게).
        List<Object[]> rows = new java.util.ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            String at = today.atTime(0, 0).plusSeconds(i).atZone(KST).toInstant().toString();
            String payload = "{\"type\":\"SCREEN_TIME\",\"observedAt\":\"" + at + "\","
                    + "\"screenEvents\":[{\"event\":\"UNLOCK\",\"at\":\"" + at + "\"}]}";
            rows.add(new Object[]{bytes(UUID.randomUUID()), java.sql.Date.valueOf(today), bytes(me.id()),
                    "SCREEN_TIME", java.sql.Timestamp.from(java.time.Instant.now()),
                    java.sql.Timestamp.from(java.time.Instant.now()), payload, "read-full-" + i});
        }
        jdbc().batchUpdate("INSERT INTO verification_device_usage_signals "
                + "(id, observedDate, userId, signalType, occurredAt, receivedAt, payload, dedupKey) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", rows);

        List<com.ruleup.ruleup_backend.verification.signal.SyncSignal> read = reader.forDay(me.id(), today);

        assertThat(read)
                .as("한 페이지만 읽고 자르면 그 뒤의 신호가 판정에서 통째로 빠진다")
                .hasSize(ROWS);
    }
}
