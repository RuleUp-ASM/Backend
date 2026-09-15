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

import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 탐색 테크 스펙 개정에 따른 가입 경로 정합 (공통 5-5-2 · 백엔드 5-1 · 12 체크리스트).
 *
 * <p>개정으로 <b>바뀐 것</b>만 여기서 잠근다. 바뀌지 않은 게이트(비공개·티어·재입장 대기 등)는
 * {@code ChallengeJoinGateIT} 가 계속 지킨다.
 *
 * <ul>
 *   <li><b>동시 참여 개수 상한이 사라졌다</b> — "동시 참여 개수에는 정책 상한을 두지 않습니다"(5-1).
 *       상한이 없어지면 그것을 직렬화하려고 잡던 사용자 행 락도 함께 사라진다.</li>
 *   <li><b>정원 없는 방은 락도 COUNT 도 하지 않는다</b> — "무제한 방은 정원 판정을 위한 행 락과
 *       COUNT 를 생략합니다"(5-1). 셀 이유가 없는 값을 세느라 같은 방 가입이 줄을 서면 안 된다.</li>
 *   <li><b>가입 트랜잭션은 {@code participant_count} 를 갱신하지 않는다</b>(12 체크리스트).
 *       표시용 수는 원천에서 다시 계산해 파생값으로 내보낸다.</li>
 *   <li><b>정원은 9종뿐이고 300 이 최대</b> — "정원은 5·10·20·30·50·100·200·300·무제한 9종 중
 *       하나이며 300 초과는 무제한만 가능"(5-3).</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeJoinSpecAlignmentIT extends ChallengeApiSupport {

    /** 이 IT 전용 템플릿. 다른 클래스의 id 와 겹치지 않게 따로 둔다. */
    private static final long GYM_TEMPLATE = 9411L;

    private static boolean fixtures;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * 「잠그지 않는다」를 밖에서 확인할 방법은 호출을 보는 것뿐이다. 다른 커넥션이 그 행을
     * 잠가 두고 대기를 관찰하는 방법은 쓸 수 없다 — FK 가 있는 자식 행을 넣을 때 InnoDB 가
     * 부모 행에 공유 락을 잡으므로, 가입이 그 행을 잠그든 말든 멤버 INSERT 가 대기한다.
     */
    @MockitoSpyBean ChallengeRepository challengeRepository;
    @MockitoSpyBean ChallengeMemberRepository memberRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        if (!fixtures) {
            insertAutoTemplate(GYM_TEMPLATE, "헬스장 가기", "퇴근 후 운동 습관", "EXERCISE",
                    "{\"duration_min\":{\"default\":60,\"unit\":\"min\",\"min\":10,\"max\":480}}",
                    "GPS_PRESENCE", "[\"ACCESS_FINE_LOCATION\",\"ACCESS_BACKGROUND_LOCATION\"]");
            fixtures = true;
        }
    }

    private MvcResult templateDraftFor(String token) throws Exception {
        MvcResult res = postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                Map.of("templateId", GYM_TEMPLATE));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    private Map<String, Object> createBodyFrom(MvcResult draftRes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", (String) read(draftRes, "$.data.draftId"));
        body.put("title", (String) read(draftRes, "$.data.draft.title"));
        body.put("description", (String) read(draftRes, "$.data.draft.description"));
        body.put("category", (String) read(draftRes, "$.data.draft.category"));
        body.put("mode", (String) read(draftRes, "$.data.draft.mode"));
        body.put("visibility", (Object) read(draftRes, "$.data.draft.visibility"));
        body.put("rankingVisible", (Object) read(draftRes, "$.data.draft.rankingVisible"));
        body.put("capacity", (Integer) read(draftRes, "$.data.draft.capacity"));
        body.put("minTier", (String) read(draftRes, "$.data.draft.minTier"));
        body.put("period", Map.of(
                "start", (String) read(draftRes, "$.data.draft.period.start"),
                "end", (String) read(draftRes, "$.data.draft.period.end")));
        body.put("weeklyCount", (Integer) read(draftRes, "$.data.draft.weeklyCount"));
        body.put("params", List.of(Map.of("key", "duration_min", "value", "60")));
        body.put("verification", Map.of(
                "type", (String) read(draftRes, "$.data.draft.verification.type"),
                "method", (String) read(draftRes, "$.data.draft.verification.method")));
        body.put("penalties", Map.of("watcher", false));
        body.put("imageUrl", null);
        return body;
    }

    private MvcResult create(String token, String idempotencyKey, Map<String, Object> body) throws Exception {
        var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/challenges")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(OM.writeValueAsString(body));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return mvc.perform(builder).andReturn();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private MvcResult join(String token, UUID challengeId) throws Exception {
        return postJsonAuth("/api/v1/challenges/" + challengeId + "/members", token, Map.of());
    }

    /** 남이 만든 공개 그룹 방. owner_id 는 FK 라 실제 사용자여야 한다. */
    private UUID someoneElsesRoom(String tag) throws Exception {
        return insertChallenge(member(uniq(tag)).id(), "EXERCISE", "ACTIVE", "GROUP");
    }

    /** 그 방의 ACTIVE 멤버 수 — 원천이다. */
    private int activeMembersOf(UUID challengeId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE'",
                Integer.class, bytes(challengeId));
        return n != null ? n : 0;
    }

    /** 비정규화된 표시 수 — 스펙 개정 뒤로는 가입 트랜잭션이 건드리지 않는다. */
    private int storedParticipantCountOf(UUID challengeId) {
        Integer n = jdbc().queryForObject(
                "SELECT participant_count FROM challenges WHERE id = ?", Integer.class, bytes(challengeId));
        return n != null ? n : 0;
    }

    private void setCapacity(UUID challengeId, Integer capacity) {
        jdbc().update("UPDATE challenges SET capacity = ? WHERE id = ?", capacity, bytes(challengeId));
    }

    // =====================================================================
    @Nested
    @DisplayName("동시 참여 개수 상한")
    class NoConcurrentLimit {

        @Test
        @DisplayName("[P0] 이미 세 방에 들어가 있어도 네 번째 방에 가입된다 — 상한이 없다")
        void afourthJoinIsAllowed() throws Exception {
            Member me = member(uniq("nolimit-join"));
            occupySlots(me.id(), 3);

            UUID fourth = someoneElsesRoom("nolimit-owner");

            assertThat(join(me.token(), fourth).getResponse().getStatus())
                    .as("상한은 개정으로 사라졌다 — 서버가 만든 제약을 사용자는 이유를 알 수 없다")
                    .isEqualTo(200);
            assertThat(activeMembersOf(fourth)).isEqualTo(1);
        }

        @Test
        @DisplayName("[P0] 이미 세 방에 들어가 있어도 새 방을 만들 수 있다")
        void afourthCreationIsAllowed() throws Exception {
            Member me = member(uniq("nolimit-create"));
            occupySlots(me.id(), 3);

            MvcResult draft = templateDraftFor(me.token());
            Map<String, Object> body = createBodyFrom(draft);
            MvcResult res = create(me.token(), UUID.randomUUID().toString(), body);

            assertThat(res.getResponse().getStatus())
                    .as("가입만 열고 생성을 막으면 「방은 못 만드는데 남의 방엔 들어가지는」 비대칭이 남는다")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("[P0] 공개 상세도 더는 FREE_LIMIT 을 미리 띄우지 않는다")
        void detailNoLongerPreviewsFreeLimit() throws Exception {
            Member me = member(uniq("nolimit-detail"));
            occupySlots(me.id(), 3);
            UUID other = someoneElsesRoom("nolimit-detail-owner");

            MvcResult res = getAuth("/api/v1/challenges/" + other, me.token());

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.joinBlockReason"))
                    .as("버튼을 미리 잠그는 이유도 함께 사라졌다")
                    .isNotEqualTo("FREE_LIMIT");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("정원 판정")
    class Capacity {

        @Test
        @DisplayName("[P1] 정원이 없는 방은 가입해도 잠금 읽기도 정원 COUNT 도 하지 않는다")
        void unlimitedRoomLocksVersionButSkipsCapacityCount() throws Exception {
            Member me = member(uniq("cap-unlimited"));
            UUID room = someoneElsesRoom("cap-unlimited-owner");
            setCapacity(room, null);
            clearInvocations(challengeRepository, memberRepository);

            assertThat(join(me.token(), room).getResponse().getStatus()).isEqualTo(200);

            verify(challengeRepository).findByIdForUpdate(room);
            verify(memberRepository, never())
                    .countByChallengeIdAndStatus(room, MemberStatus.ACTIVE);
        }

        @Test
        @DisplayName("[P1] 무제한 방 가입은 challenges 행을 쓰지 않는다 — 쓰면 결국 그 행에 락이 걸린다")
        void joiningAnUnlimitedRoomBumpsSettingsVersion() throws Exception {
            Member me = member(uniq("cap-nowrite"));
            UUID room = someoneElsesRoom("cap-nowrite-owner");
            setCapacity(room, null);
            Object versionBefore = jdbc().queryForMap(
                    "SELECT version FROM challenges WHERE id = ?", bytes(room)).get("version");

            assertThat(join(me.token(), room).getResponse().getStatus()).isEqualTo(200);

            assertThat(jdbc().queryForMap("SELECT version FROM challenges WHERE id = ?", bytes(room)).get("version"))
                    .as("버전을 올리면 커밋 시 그 행에 쓰기 락이 잡혀, 잠금 읽기를 걷어낸 의미가 사라진다")
                    .isEqualTo(((Number)versionBefore).intValue()+1);
        }

        @Test
        @DisplayName("[P1] 정원이 있는 방은 잠금 읽기가 먼저고 그 뒤에 센다 — 회귀")
        void limitedRoomStillLocksThenCounts() throws Exception {
            Member me = member(uniq("cap-limited"));
            UUID room = someoneElsesRoom("cap-limited-owner");
            setCapacity(room, 50);
            clearInvocations(challengeRepository, memberRepository);

            assertThat(join(me.token(), room).getResponse().getStatus()).isEqualTo(200);

            verify(challengeRepository).findByIdForUpdate(room);
            verify(memberRepository).countByChallengeIdAndStatus(room, MemberStatus.ACTIVE);
        }

        @Test
        @DisplayName("[P1] 정원이 있는 방은 마지막 한 자리를 여전히 지킨다 — 회귀")
        void limitedRoomStillEnforcesTheCap() throws Exception {
            UUID owner = member(uniq("cap-full-owner")).id();
            UUID room = insertChallenge(owner, "EXERCISE", "ACTIVE", "GROUP");
            setCapacity(room, 1);
            insertActiveMembership(room, owner, "OWNER");

            Member me = member(uniq("cap-full"));
            MvcResult res = join(me.token(), room);

            assertThat(res.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) read(res, "$.error.reason")).isEqualTo("FULL");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("표시용 참여자 수")
    class ParticipantCount {

        @Test
        @DisplayName("[P1] 가입 트랜잭션은 challenges.participant_count 를 건드리지 않는다")
        void joinDoesNotWriteTheDenormalizedCount() throws Exception {
            Member me = member(uniq("pc-join"));
            UUID room = someoneElsesRoom("pc-join-owner");
            int before = storedParticipantCountOf(room);

            assertThat(join(me.token(), room).getResponse().getStatus()).isEqualTo(200);

            assertThat(activeMembersOf(room))
                    .as("원천은 늘어야 한다 — 가입 자체는 정상이다").isEqualTo(1);
            assertThat(storedParticipantCountOf(room))
                    .as("표시값을 가입 트랜잭션 안에서 올리면 같은 방 가입이 그 행에서 직렬화된다 — "
                            + "정확해야 하는 값은 멤버십 행이고, 표시값은 원천에서 다시 계산한다")
                    .isEqualTo(before);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("정원 허용값")
    class CapacityChoices {

        private MvcResult createWithCapacity(String token, Integer capacity) throws Exception {
            Map<String, Object> body = createBodyFrom(templateDraftFor(token));
            body.put("mode", "GROUP");
            body.put("capacity", capacity);
            return create(token, UUID.randomUUID().toString(), body);
        }

        @Test
        @DisplayName("[P1] 선택지에 없는 값은 거절한다 — 정원은 고른 값이지 적어 넣는 값이 아니다")
        void capacityOutsideRangeIsRejected() throws Exception {
            String token = memberToken(uniq("cap-choice"));

            // 47·50 이 핵심이다. 범위(1~300) 안이지만 선택지가 아니므로 거절해야 한다 —
            // 범위만 보던 옛 검증은 이 둘을 통과시켰다.
            for (int notAllowed : List.of(0, -1, 47, 50, 301, 1000)) {
                MvcResult res = createWithCapacity(token, notAllowed);
                assertThat(res.getResponse().getStatus())
                        .as("허용값 밖의 정원 %d 가 통과했다", notAllowed)
                        .isEqualTo(400);
                assertThat((String) read(res, "$.error.code")).isEqualTo("CAPACITY_OUT_OF_RANGE");
            }
        }

        @Test
        @DisplayName("[P1] 무제한은 capacity 를 비워서 만든다 — 선택지를 좁히면서 막아 두면 안 된다")
        void unlimitedIsCreatedWithNoCapacity() throws Exception {
            String token = memberToken(uniq("cap-unlimited-create"));

            MvcResult res = createWithCapacity(token, null);

            assertThat(res.getResponse().getStatus())
                    .as("300 초과는 무제한만 가능한데 무제한을 만들 길이 없으면 300 이 사실상 상한이 된다")
                    .isEqualTo(201);
            String id = read(res, "$.data.challengeId");
            assertThat(jdbc().queryForMap(
                    "SELECT capacity FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))", id).get("capacity"))
                    .as("무제한은 null 한 가지로만 표현한다 — 큰 수로 흉내 내면 그 방은 계속 락과 COUNT 를 지불한다")
                    .isNull();
        }

        @Test
        @DisplayName("[P1] 300 까지는 고를 수 있고, 그보다 크면 무제한뿐이다")
        void threeHundredIsTheLargestFiniteChoice() throws Exception {
            assertThat(createWithCapacity(memberToken(uniq("cap-300")), 300).getResponse().getStatus())
                    .as("300 은 선택지 중 하나다").isEqualTo(201);
        }
    }
}
