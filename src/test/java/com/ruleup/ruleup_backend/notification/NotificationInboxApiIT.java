package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 알림함 API 계약 — 공통 6절 #1·#2, API 명세 「알림 센터 목록 조회」·「알림 센터 전체 읽음」.
 *
 * <p>재설계로 세 가지가 바뀌었다.
 * <ul>
 *   <li><b>페이지 크기가 서버 고정 50</b>이다. 카운터 상한이 {@code 99+} 라 클라이언트는 최대
 *       2페이지만 읽으면 되므로 크기를 협상할 이유가 없다.
 *   <li><b>커서가 base64(id) 단일값</b>이다. UUIDv7 이라 id 순서가 곧 시간 순서이고,
 *       00시 배치가 같은 밀리초에 수만 행을 넣어도 {@code created_at} 동점 문제가 없다.
 *   <li><b>미읽음을 서버가 세지 않는다.</b> 읽음 지점 id 하나만 내리고 클라이언트가 계산한다.
 *       개별 읽음·전체 읽음·개별 삭제 API 는 전부 폐지됐다.
 * </ul>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class NotificationInboxApiIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired NotificationPublisher publisher;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }


    // ===== 헬퍼 =====

    private record Account(String accessToken, UUID userId) {}

    private Account join(String nickname) throws Exception {
        MvcResult res = signup(uniq("ib"), nickname + seq());
        return new Account(read(res, "$.data.accessToken"),
                UUID.fromString(read(res, "$.data.user.id")));
    }

    private void store(UUID userId, NotificationType type, String key) {
        txTemplate.executeWithoutResult(t -> publisher.publish(NotificationEvent.of(
                userId, type, "제목-" + key, "본문-" + key,
                Map.of(NotificationParams.EVENT_KEY, key,
                        NotificationParams.APPEAL_ID, key,
                        NotificationParams.ANNOUNCEMENT_ID, key))));
    }

    private MvcResult list(String at, String query) throws Exception {
        return mvc.perform(get("/api/v1/notifications" + query)
                .header("Authorization", "Bearer " + at)).andReturn();
    }

    /**
     * 보관 기간 밖으로 밀어낸다. SQL 안에서 상대 계산을 하는 이유는 {@code created_at} 이
     * DATETIME(3) 이라 자바에서 Instant 를 넣으면 JVM 시간대 해석이 끼어들기 때문이다.
     */
    private void backdate(UUID userId, int days) {
        jdbc.update("UPDATE notifications SET created_at = DATE_SUB(created_at, INTERVAL ? DAY)"
                + " WHERE user_id = ?", days, bytes(userId));
    }

    private static byte[] bytes(UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private MvcResult markRead(String at, String tab, String lastId) throws Exception {
        Map<String, Object> body = (tab == null)
                ? Map.of("lastNotificationId", lastId)
                : Map.of("tab", tab, "lastNotificationId", lastId);
        return mvc.perform(put("/api/v1/notifications/read")
                .header("Authorization", "Bearer " + at)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    // =====================================================================
    @Nested
    @DisplayName("목록 조회")
    class Listing {

        @Test
        @DisplayName("최신순으로 내려오고 항목이 계약된 필드만 담는다")
        void newestFirst() throws Exception {
            Account a = join("목록");
            store(a.userId(), NotificationType.APPEAL_RESULT, "k1");
            store(a.userId(), NotificationType.APPEAL_RESULT, "k2");

            MvcResult res = list(a.accessToken(), "");

            assertThat(this_(res, "$.data.items[0].title")).isEqualTo("제목-k2");
            assertThat(this_(res, "$.data.items[1].title")).isEqualTo("제목-k1");
            assertThat(this_(res, "$.data.items[0].type")).isEqualTo("APPEAL_RESULT");
            assertThat(this_(res, "$.data.items[0].deeplink")).isEqualTo("ruleup://me/appeals");
            assertThat((Integer) read(res, "$.data.retentionDays")).isEqualTo(180);
        }

        @Test
        @DisplayName("보관 기간이 지난 알림은 내려가지 않는다 — 파기가 밀려도 다시 보이면 안 된다")
        void retentionBoundaryHidesExpiredRows() throws Exception {
            Account a = join("보관");
            store(a.userId(), NotificationType.APPEAL_RESULT, "old" + seq());
            backdate(a.userId(), 200);                     // 보관 180일을 넘긴다
            store(a.userId(), NotificationType.APPEAL_RESULT, "fresh" + seq());

            List<String> ids = read(list(a.accessToken(), ""), "$.data.items[*].id");

            assertThat(ids).as("경계 밖 한 건은 빠지고 최근 한 건만 남는다").hasSize(1);
        }

        @Test
        @DisplayName("기본 탭에 공지가 섞이지 않는다 — 미지정 조회는 알림 탭이다")
        void announcementsAreNotMixedIn() throws Exception {
            Account a = join("탭");
            store(a.userId(), NotificationType.APPEAL_RESULT, "n1");
            store(a.userId(), NotificationType.ANNOUNCEMENT, "an1");

            List<String> types = read(list(a.accessToken(), ""), "$.data.items[*].type");
            assertThat(types).containsExactly("APPEAL_RESULT");
        }

        @Test
        @DisplayName("tab=ANNOUNCEMENT 는 공지만 내린다 — 구 GET /announcements 를 흡수했다")
        void announcementTab() throws Exception {
            Account a = join("공지탭");
            store(a.userId(), NotificationType.APPEAL_RESULT, "n2");
            store(a.userId(), NotificationType.ANNOUNCEMENT, "an2");

            List<String> types = read(list(a.accessToken(), "?tab=ANNOUNCEMENT"),
                    "$.data.items[*].type");
            assertThat(types).containsExactly("ANNOUNCEMENT");
        }

        @Test
        @DisplayName("페이지 크기는 서버 고정 50이며 size 파라미터를 받지 않는다")
        void pageSizeIsFixed() throws Exception {
            Account a = join("크기");
            for (int i = 0; i < 55; i++) store(a.userId(), NotificationType.APPEAL_RESULT, "p" + i);

            List<String> ids = read(list(a.accessToken(), "?size=5"), "$.data.items[*].id");
            assertThat(ids).as("size 를 보내도 무시하고 50건을 준다").hasSize(50);
        }

        @Test
        @DisplayName("커서로 이어 읽어도 경계에서 겹치거나 빠지지 않는다")
        void cursorHasNoGapOrOverlap() throws Exception {
            Account a = join("커서");
            for (int i = 0; i < 55; i++) store(a.userId(), NotificationType.APPEAL_RESULT, "c" + i);

            MvcResult first = list(a.accessToken(), "");
            List<String> page1 = read(first, "$.data.items[*].id");
            String cursor = read(first, "$.data.nextCursor");
            assertThat(cursor).isNotNull();

            List<String> page2 = read(list(a.accessToken(), "?cursor=" + cursor),
                    "$.data.items[*].id");

            assertThat(page2).hasSize(5).doesNotContainAnyElementsOf(page1);
            assertThat((String) read(list(a.accessToken(), "?cursor=" + cursor),
                    "$.data.nextCursor")).as("마지막 페이지면 커서가 없다").isNull();
        }

        @Test
        @DisplayName("커서가 깨졌으면 400 CURSOR_INVALID — 클라이언트는 첫 페이지부터 다시 읽는다")
        void brokenCursor() throws Exception {
            Account a = join("깨진커서");
            expectError(list(a.accessToken(), "?cursor=!!!not-base64!!!"), 400, "CURSOR_INVALID");
        }

        @Test
        @DisplayName("남의 알림은 보이지 않는다")
        void scopedToOwner() throws Exception {
            Account mine = join("내것");
            Account other = join("남것");
            store(other.userId(), NotificationType.APPEAL_RESULT, "x1");

            assertThat((List<?>) read(list(mine.accessToken(), ""), "$.data.items[*].id")).isEmpty();
        }

        @Test
        @DisplayName("잠금 계정도 열람할 수 있다 — 제재 고지가 여기 쌓인다")
        void lockedAccountCanRead() throws Exception {
            Account a = join("잠금");
            store(a.userId(), NotificationType.ACCOUNT_SANCTION, "s1");
            lock(a.userId());

            assertThat(list(a.accessToken(), "").getResponse().getStatus()).isEqualTo(200);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("읽음 커서")
    class ReadCursor {

        @Test
        @DisplayName("설정한 적이 없으면 null 이다 — 그 페이지는 전부 미읽음이다")
        void nullWhenNeverRead() throws Exception {
            Account a = join("첫읽음");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r1");

            assertThat((String) read(list(a.accessToken(), ""), "$.data.lastReadNotificationId"))
                    .isNull();
        }

        @Test
        @DisplayName("보낸 id 로 갱신하고 204 를 준다 — 설정 행이 없으면 만든다")
        void updatesToGivenId() throws Exception {
            Account a = join("읽음갱신");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r2");
            String id = read(list(a.accessToken(), ""), "$.data.items[0].id");

            assertThat(markRead(a.accessToken(), "NOTIFICATION", id).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat((String) read(list(a.accessToken(), ""), "$.data.lastReadNotificationId"))
                    .isEqualTo(id);
        }

        @Test
        @DisplayName("현재 값보다 과거인 id 는 무시하고 그대로 204 — 2페이지에서 호출해도 뒤로 밀리지 않는다")
        void neverMovesBackwards() throws Exception {
            Account a = join("역행");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r3");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r4");
            List<String> ids = read(list(a.accessToken(), ""), "$.data.items[*].id");
            String newest = ids.get(0);
            String older = ids.get(1);

            markRead(a.accessToken(), "NOTIFICATION", newest);
            assertThat(markRead(a.accessToken(), "NOTIFICATION", older).getResponse().getStatus())
                    .isEqualTo(204);

            assertThat((String) read(list(a.accessToken(), ""), "$.data.lastReadNotificationId"))
                    .isEqualTo(newest);
        }

        @Test
        @DisplayName("멱등이다 — 같은 값을 두 번 보내도 결과가 같다")
        void idempotent() throws Exception {
            Account a = join("멱등읽음");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r5");
            String id = read(list(a.accessToken(), ""), "$.data.items[0].id");

            markRead(a.accessToken(), "NOTIFICATION", id);
            assertThat(markRead(a.accessToken(), "NOTIFICATION", id).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("탭별로 따로 보관한다 — 공지를 읽어도 알림 탭의 레드닷은 남는다")
        void perTabCursors() throws Exception {
            Account a = join("탭별읽음");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r6");
            store(a.userId(), NotificationType.ANNOUNCEMENT, "an6");
            String announcementId = read(list(a.accessToken(), "?tab=ANNOUNCEMENT"),
                    "$.data.items[0].id");

            markRead(a.accessToken(), "ANNOUNCEMENT", announcementId);

            assertThat((String) read(list(a.accessToken(), "?tab=ANNOUNCEMENT"),
                    "$.data.lastReadNotificationId")).isEqualTo(announcementId);
            assertThat((String) read(list(a.accessToken(), ""),
                    "$.data.lastReadNotificationId")).isNull();
        }

        @Test
        @DisplayName("탭을 생략해도 된다 — 알림 자신의 탭으로 움직인다")
        void tabDefaults() throws Exception {
            Account a = join("탭생략");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r7");
            String id = read(list(a.accessToken(), ""), "$.data.items[0].id");

            markRead(a.accessToken(), null, id);

            assertThat((String) read(list(a.accessToken(), ""), "$.data.lastReadNotificationId"))
                    .isEqualTo(id);
        }

        @Test
        @DisplayName("알림의 실제 탭과 어긋나면 400 — 무시하면 클라이언트가 오해한 채 남는다")
        void rejectsMismatchedTab() throws Exception {
            Account a = join("탭불일치");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r9");
            String id = read(list(a.accessToken(), ""), "$.data.items[0].id");

            expectError(markRead(a.accessToken(), "ANNOUNCEMENT", id), 400, "INVALID_REQUEST");

            assertThat((String) read(list(a.accessToken(), ""), "$.data.lastReadNotificationId"))
                    .as("거절했으면 커서도 움직이지 않았다").isNull();
        }

        @Test
        @DisplayName("정의되지 않은 탭 값도 400 이다")
        void rejectsUnknownTab() throws Exception {
            Account a = join("탭이상");
            store(a.userId(), NotificationType.APPEAL_RESULT, "r10");
            String id = read(list(a.accessToken(), ""), "$.data.items[0].id");

            expectError(markRead(a.accessToken(), "INBOX", id), 400, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("남의 알림 id 로는 갱신되지 않는다 — 404")
        void rejectsForeignId() throws Exception {
            Account mine = join("내읽음");
            Account other = join("남읽음");
            store(other.userId(), NotificationType.APPEAL_RESULT, "r8");
            String foreign = read(list(other.accessToken(), ""), "$.data.items[0].id");

            expectError(markRead(mine.accessToken(), "NOTIFICATION", foreign),
                    404, "NOTIFICATION_NOT_FOUND");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("폐지된 API")
    class RemovedEndpoints {

        @Test
        @DisplayName("개별 읽음·전체 읽음·개별 삭제는 더 이상 없다 — 읽음 커서 하나로 대체됐다")
        void goneForGood() throws Exception {
            Account a = join("폐지");
            store(a.userId(), NotificationType.APPEAL_RESULT, "d1");
            String id = read(list(a.accessToken(), ""), "$.data.items[0].id");
            String bearer = "Bearer " + a.accessToken();

            assertThat(mvc.perform(post("/api/v1/notifications/" + id + "/read")
                    .header("Authorization", bearer)).andReturn().getResponse().getStatus())
                    .isIn(404, 405);
            assertThat(mvc.perform(post("/api/v1/notifications/read-all")
                    .header("Authorization", bearer)).andReturn().getResponse().getStatus())
                    .isIn(404, 405);
            assertThat(mvc.perform(delete("/api/v1/notifications/" + id)
                    .header("Authorization", bearer)).andReturn().getResponse().getStatus())
                    .isIn(404, 405);
        }
    }

    /** jsonPath 헬퍼 — {@code read} 의 제네릭 추론이 String 으로 좁혀지지 않는 자리에서 쓴다. */
    private String this_(MvcResult res, String path) throws Exception {
        return read(res, path);
    }
}
