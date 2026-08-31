package com.ruleup.ruleup_backend.watcher;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.watcher.domain.*;
import com.ruleup.ruleup_backend.watcher.repository.*;
import com.ruleup.ruleup_backend.watcher.service.WatcherBatch;
import com.ruleup.ruleup_backend.watcher.service.WatcherNoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 감시자 — 패널티 감시자 공통 5-2·5-3·5-5, 백엔드 4-1·4-2.
 *
 * <p>설계 전체가 <b>두 개의 절대 가드레일</b>에서 나온다.
 * <ol>
 *   <li><b>PENDING 상태에 발송 0건</b> — 동의하지 않은 사람에게 보내면 위법이다</li>
 *   <li><b>이의 기간 종료 전 발송 0건</b> — 인용될 수 있는 실패로 망신을 주면 복구가 안 된다</li>
 * </ol>
 *
 * <p>세 번째 축은 <b>연락처를 수집하지 않는다</b>는 것이다. 마스킹이나 암호화가 아니라
 * <b>스키마에 자리를 두지 않는 방식</b>으로 막으므로, 컬럼 부재 자체를 테스트한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WatcherRelationIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WatcherRelationRepository relationRepository;
    @Autowired WatcherInvitationRepository invitationRepository;
    @Autowired WatcherNoticeRepository noticeRepository;
    @Autowired WatcherReactionRepository reactionRepository;
    @Autowired WatcherConsentLogRepository consentLogRepository;
    @Autowired WatcherNoticeService noticeService;
    @Autowired WatcherBatch batch;
    @Autowired NotificationRepository notificationRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    @Override
    protected JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    // ===== 헬퍼 =====

    /** 챌린지를 가진 피감시자. */
    private record Target(Member owner, UUID challengeId) {}

    private Target target(String tag) throws Exception {
        Member owner = member(uniq(tag));
        UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "SOLO");
        insertActiveMembership(challengeId, owner.id(), "OWNER");
        return new Target(owner, challengeId);
    }

    private MvcResult postAuth(String url, String token, Object body) throws Exception {
        var req = post(url).header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body));
        return mvc.perform(req).andReturn();
    }

    private MvcResult patchAuth(String url, String token, Map<String, Object> body) throws Exception {
        return patchJsonAuth(url, token, body);
    }

    /** 초대 발급 → 원본 토큰. */
    private String invite(Target t) throws Exception {
        MvcResult res = postAuth("/api/v1/challenges/" + t.challengeId() + "/watchers/invitations",
                t.owner().token(), null);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.token");
    }

    /** 초대 발급 → 수락까지. 반환은 성립된 관계. */
    private WatcherRelation accept(Target t, Member watcher) throws Exception {
        String token = invite(t);
        MvcResult res = postAuth("/api/v1/watchers/invitations/" + token + "/accept",
                watcher.token(), null);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return relationRepository.findById(UUID.fromString(read(res, "$.data.watcherId"))).orElseThrow();
    }

    /** 실패 확정 이벤트가 오는 상황을 만든다 — 인증 모듈이 발행하는 것과 같은 입력. */
    private WatcherNotice confirmFailure(Target t, UUID verificationId) {
        noticeService.onFailureConfirmed(t.challengeId(), t.owner().id(), verificationId,
                LocalDate.now(), Instant.now());
        return noticeRepository.findByVerificationId(verificationId).stream().findFirst().orElse(null);
    }

    /** 해당 챌린지의 초대를 만료시킨다. 시각 계산을 DB 안에서 해 타임존 변환을 피한다. */
    private void expireInvitations(UUID challengeId, String interval) {
        jdbcTemplate.update("UPDATE watcher_invitations SET expires_at = DATE_SUB(NOW(3), "
                + interval + ") WHERE challenge_id = ?", bytes(challengeId));
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    private boolean tableExists(String table) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
        return n != null && n > 0;
    }

    // =====================================================================
    @Nested
    @DisplayName("연락처를 수집하지 않는다 — 스키마에 자리를 두지 않는 방식")
    class NoContact {

        @Test
        @DisplayName("어느 감시자 테이블에도 연락처 컬럼이 없다")
        void no_contact_column_anywhere() {
            for (String table : List.of("watcher_relations", "watcher_invitations",
                    "watcher_notices", "watcher_reactions", "watcher_consent_logs")) {
                for (String column : List.of("contact", "contact_enc", "contact_masked",
                        "phone", "phone_enc", "phone_hash", "email")) {
                    assertThat(columnExists(table, column))
                            .as("%s.%s — 스키마에 자리가 없으면 실수로도 수집할 수 없다", table, column)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("SMS·OTP·비유저 감시자 테이블이 남아 있지 않다")
        void legacy_tables_are_gone() {
            assertThat(tableExists("WatcherOtp")).as("SMS OTP — 채널 자체가 폐지됐다").isFalse();
            assertThat(tableExists("Watcher")).as("구 관계 테이블").isFalse();
            assertThat(tableExists("WatcherNotification")).isFalse();
        }

        @Test
        @DisplayName("초대 토큰은 해시만 보관한다 — 원본은 저장하지 않는다")
        void token_is_hashed() throws Exception {
            Target t = target("hash");
            String token = invite(t);

            Integer raw = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM watcher_invitations WHERE HEX(token_hash) = ?",
                    Integer.class, token);
            assertThat(raw).as("원본 토큰이 그대로 들어가 있으면 안 된다").isZero();
            assertThat(invitationRepository.count()).isPositive();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("초대와 동의 성립")
    class Consent {

        @Test
        @DisplayName("초대를 발급하면 관계가 PENDING 으로 생기고 아직 발송 대상이 아니다")
        void invitation_creates_pending() throws Exception {
            Target t = target("pending");
            invite(t);

            // 초대 시점에는 누가 수락할지 모르므로 관계는 수락 때 생긴다.
            assertThat(relationRepository.findDispatchTargets(t.challengeId(), t.owner().id()))
                    .as("PENDING 발송 0건 — 수락 전에는 대상이 없다").isEmpty();
        }

        @Test
        @DisplayName("인앱 수락으로만 동의가 성립하고 그 시각이 남는다")
        void accept_establishes_consent() throws Exception {
            Target t = target("accept");
            Member watcher = member(uniq("w"));

            WatcherRelation relation = accept(t, watcher);

            assertThat(relation.getStatus()).isEqualTo(WatcherRelationStatus.ACTIVE);
            assertThat(relation.getAcceptedAt())
                    .as("동의 시각이 입증 책임의 근거다").isNotNull();
            assertThat(consentLogRepository.findByRelationIdOrderByOccurredAtAsc(relation.getId()))
                    .extracting(WatcherConsentLog::getEvent)
                    .containsExactly(ConsentEvent.ACCEPTED);
        }

        @Test
        @DisplayName("로그인하지 않으면 수락할 수 없다 — 웹 수락을 동의로 인정하지 않는다")
        void accept_requires_login() throws Exception {
            Target t = target("login");
            String token = invite(t);

            MvcResult res = mvc.perform(post("/api/v1/watchers/invitations/" + token + "/accept"))
                    .andReturn();
            expectError(res, 401, "LOGIN_REQUIRED");
        }

        @Test
        @DisplayName("만료된 초대는 410 INVITATION_EXPIRED")
        void expired_invitation() throws Exception {
            Target t = target("expire");
            Member watcher = member(uniq("w"));
            String token = invite(t);

            // Timestamp 로 넘기면 JVM 기본 타임존으로 변환돼 DB 세션 타임존과 어긋난다 — SQL 안에서 계산한다.
            expireInvitations(t.challengeId(), "INTERVAL 1 MINUTE");

            expectError(postAuth("/api/v1/watchers/invitations/" + token + "/accept",
                    watcher.token(), null), 410, "INVITATION_EXPIRED");
        }

        @Test
        @DisplayName("위조 토큰은 400 INVITATION_INVALID")
        void forged_token() throws Exception {
            Member watcher = member(uniq("w"));
            expectError(postAuth("/api/v1/watchers/invitations/inv_forged/accept",
                    watcher.token(), null), 400, "INVITATION_INVALID");
        }

        @Test
        @DisplayName("본인을 감시자로 수락할 수 없다 — 400 CANNOT_WATCH_SELF")
        void cannot_watch_self() throws Exception {
            Target t = target("self");
            String token = invite(t);

            expectError(postAuth("/api/v1/watchers/invitations/" + token + "/accept",
                    t.owner().token(), null), 400, "CANNOT_WATCH_SELF");
        }

        @Test
        @DisplayName("같은 사람이 같은 방을 두 번 수락하면 409 ALREADY_WATCHER")
        void already_watcher() throws Exception {
            Target t = target("dup");
            Member watcher = member(uniq("w"));
            accept(t, watcher);

            String token = invite(t);
            expectError(postAuth("/api/v1/watchers/invitations/" + token + "/accept",
                    watcher.token(), null), 409, "ALREADY_WATCHER");
        }

        @Test
        @DisplayName("같은 사람을 여러 챌린지에서 감시자로 둘 수 있다 — 관계는 서로 독립이다")
        void same_watcher_across_challenges() throws Exception {
            Member watcher = member(uniq("w"));
            Target first = target("multi1");
            Target second = target("multi2");

            accept(first, watcher);
            accept(second, watcher);

            assertThat(relationRepository.findByWatcherUserIdAndRemovedAtIsNull(watcher.id())).hasSize(2);
        }

        @Test
        @DisplayName("감시자 인원에 상한이 없다 — 무료 3명 한도는 폐지됐다")
        void no_watcher_limit() throws Exception {
            Target t = target("nolimit");
            for (int i = 0; i < 4; i++) accept(t, member(uniq("w" + i)));

            assertThat(relationRepository.findDispatchTargets(t.challengeId(), t.owner().id()))
                    .hasSize(4);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("실패 통지 — 두 개의 절대 가드레일")
    class Notice {

        @Test
        @DisplayName("ACTIVE 감시자에게만 통지한다 — PENDING 에는 발송 0건")
        void only_active_receives() throws Exception {
            Target t = target("dispatch");
            Member accepted = member(uniq("ok"));
            accept(t, accepted);
            invite(t);   // 수락하지 않은 초대 — 발송 대상이 아니다

            UUID verificationId = UUID.randomUUID();
            WatcherNotice notice = confirmFailure(t, verificationId);

            assertThat(notice).isNotNull();
            assertThat(noticeRepository.findByVerificationId(verificationId))
                    .as("ACTIVE 1명에게만 나간다").hasSize(1);
            assertThat(relationRepository.findById(notice.getRelationId()).orElseThrow()
                    .getWatcherUserId()).isEqualTo(accepted.id());
        }

        @Test
        @DisplayName("통지 시각과 근거 인증 건을 남긴다 — 조기 발송 감사의 조인 키다")
        void notice_keeps_audit_join_key() throws Exception {
            Target t = target("audit");
            accept(t, member(uniq("w")));
            UUID verificationId = UUID.randomUUID();

            WatcherNotice notice = confirmFailure(t, verificationId);

            assertThat(notice.getVerificationId()).isEqualTo(verificationId);
            assertThat(notice.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("같은 실패 건이 재전송돼도 통지는 1회만 나간다")
        void notice_is_idempotent() throws Exception {
            Target t = target("idem");
            accept(t, member(uniq("w")));
            UUID verificationId = UUID.randomUUID();

            confirmFailure(t, verificationId);
            confirmFailure(t, verificationId);   // 이벤트 재전송

            assertThat(noticeRepository.findByVerificationId(verificationId))
                    .as("(relation_id, verification_id) UNIQUE 로 막는다").hasSize(1);
        }

        @Test
        @DisplayName("통지에는 실패자 닉네임·챌린지명·루틴명 3개만 담고 방 진입점을 주지 않는다")
        void notice_contains_three_fields_only() throws Exception {
            Target t = target("payload");
            Member watcher = member(uniq("w"));
            accept(t, watcher);

            confirmFailure(t, UUID.randomUUID());

            var inbox = notificationRepository.findInbox(watcher.id(), null, null, Limit.unlimited());
            assertThat(inbox).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.PENALTY_FAILURE_SHARED.name());
                assertThat(n.getDeeplink())
                        .as("감시자는 방 멤버가 아니다 — 방 상세·랭킹·멤버로 보내지 않는다")
                        .doesNotContain("/challenges/")
                        .startsWith("ruleup://watching/");
            });
        }

        @Test
        @DisplayName("수신 토글을 끄면 통지가 나가지 않지만 관계는 살아 있다")
        void toggle_off_stops_notice() throws Exception {
            Target t = target("toggle");
            Member watcher = member(uniq("w"));
            WatcherRelation relation = accept(t, watcher);

            patchAuth("/api/v1/users/me/watching/" + relation.getId(), watcher.token(),
                    Map.of("pushEnabled", (Object) false));

            UUID verificationId = UUID.randomUUID();
            confirmFailure(t, verificationId);

            assertThat(noticeRepository.findByVerificationId(verificationId))
                    .as("발송 대상에서 빠진다").isEmpty();
            assertThat(relationRepository.findById(relation.getId()).orElseThrow().getStatus())
                    .as("관계를 끊는 것이 아니라 통지만 닫는다").isEqualTo(WatcherRelationStatus.ACTIVE);
        }

        @Test
        @DisplayName("루틴이 끝나 관계가 제거되면 통지 대상에서 빠진다")
        void removed_relation_stops_notice() throws Exception {
            Target t = target("removed");
            Member watcher = member(uniq("w"));
            WatcherRelation relation = accept(t, watcher);

            relationRepository.save(relation);
            jdbcTemplate.update("UPDATE watcher_relations SET removed_at = NOW(3) WHERE id = ?",
                    bytes(relation.getId()));

            UUID verificationId = UUID.randomUUID();
            confirmFailure(t, verificationId);
            assertThat(noticeRepository.findByVerificationId(verificationId)).isEmpty();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("수신 관리 — 조회와 토글")
    class Watching {

        @Test
        @DisplayName("내가 감시자로 등록된 관계를 조회한다")
        void list_my_watching() throws Exception {
            Target t = target("list");
            Member watcher = member(uniq("w"));
            accept(t, watcher);

            MvcResult res = getAuth("/api/v1/users/me/watching", watcher.token());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            List<Map<String, Object>> items = read(res, "$.data.items");
            assertThat(items).singleElement().satisfies(item -> {
                assertThat(item.get("status")).isEqualTo("ACTIVE");
                assertThat(item.get("pushEnabled")).isEqualTo(true);
                assertThat(item.get("acceptedAt")).isNotNull();
                assertThat(item.get("challengeTitle")).isNotNull();
            });
        }

        @Test
        @DisplayName("해제 엔드포인트를 두지 않는다 — 관계를 끊는 경로가 없다")
        void no_revoke_endpoint() throws Exception {
            Target t = target("norevoke");
            Member watcher = member(uniq("w"));
            WatcherRelation relation = accept(t, watcher);

            // 구 계약의 감시자 해제 — 경로 자체가 사라졌다.
            MvcResult res = mvc.perform(delete("/api/v1/challenges/" + t.challengeId()
                    + "/watchers/" + relation.getId())
                    .header("Authorization", "Bearer " + t.owner().token())).andReturn();
            // 매핑된 패턴이 아예 없으므로 405(메서드 불가)가 아니라 404 다 —
            // 핸들러를 남겨 두고 막는 것이 아니라 경로 자체를 지웠다는 뜻이다.
            assertThat(res.getResponse().getStatus())
                    .as("경로를 두지 않는 것이 정책과 구현을 일치시키는 방법이다").isEqualTo(404);
        }

        @Test
        @DisplayName("토글 OFF 시각이 동의 이력에 남는다")
        void toggle_off_is_logged() throws Exception {
            Target t = target("togglelog");
            Member watcher = member(uniq("w"));
            WatcherRelation relation = accept(t, watcher);

            patchAuth("/api/v1/users/me/watching/" + relation.getId(), watcher.token(),
                    Map.of("pushEnabled", (Object) false));

            assertThat(consentLogRepository.findByRelationIdOrderByOccurredAtAsc(relation.getId()))
                    .extracting(WatcherConsentLog::getEvent)
                    .containsExactly(ConsentEvent.ACCEPTED, ConsentEvent.TOGGLE_OFF);
        }

        @Test
        @DisplayName("남의 관계는 토글할 수 없다 — 404 로 존재를 숨긴다")
        void cannot_toggle_others() throws Exception {
            Target t = target("othertoggle");
            Member watcher = member(uniq("w"));
            Member stranger = member(uniq("s"));
            WatcherRelation relation = accept(t, watcher);

            expectError(patchAuth("/api/v1/users/me/watching/" + relation.getId(), stranger.token(),
                    Map.of("pushEnabled", false)), 404, "WATCHER_NOT_FOUND");
        }

        @Test
        @DisplayName("피감시자는 자기가 지정한 감시자 목록을 본다")
        void owner_lists_watchers() throws Exception {
            Target t = target("ownerlist");
            accept(t, member(uniq("w")));

            MvcResult res = getAuth("/api/v1/challenges/" + t.challengeId() + "/watchers",
                    t.owner().token());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<?>) read(res, "$.data.items")).hasSize(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("응원·놀림 — 실패 건당 1회")
    class Reaction {

        @Test
        @DisplayName("반응을 보내면 실패 당사자에게 알림이 가고 닉네임이 공개된다")
        void reaction_notifies_target() throws Exception {
            Target t = target("react");
            Member watcher = member(uniq("w"));
            accept(t, watcher);
            WatcherNotice notice = confirmFailure(t, UUID.randomUUID());

            MvcResult res = postAuth("/api/v1/watcher-notices/" + notice.getId() + "/reactions",
                    watcher.token(), Map.of("reaction", "CHEER"));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.reaction")).isEqualTo("CHEER");
            assertThat((String) read(res, "$.data.reactorNickname")).isNotBlank();

            assertThat(notificationRepository.findInbox(t.owner().id(), null, null, Limit.unlimited()))
                    .anyMatch(n -> NotificationType.WATCHER_REACTION.name().equals(n.getType()));
        }

        @Test
        @DisplayName("같은 통지에 두 번째 반응은 409 REACTION_ALREADY_SENT")
        void second_reaction_rejected() throws Exception {
            Target t = target("react2");
            Member watcher = member(uniq("w"));
            accept(t, watcher);
            WatcherNotice notice = confirmFailure(t, UUID.randomUUID());

            postAuth("/api/v1/watcher-notices/" + notice.getId() + "/reactions",
                    watcher.token(), Map.of("reaction", "CHEER"));

            // 응원과 놀림을 둘 다 보낼 수 없다 — 하나를 보내면 그 통지에 대한 반응은 끝난다.
            expectError(postAuth("/api/v1/watcher-notices/" + notice.getId() + "/reactions",
                    watcher.token(), Map.of("reaction", "TEASE")), 409, "REACTION_ALREADY_SENT");
            assertThat(reactionRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("그 통지의 수신 감시자가 아니면 403 NOT_WATCHER")
        void stranger_cannot_react() throws Exception {
            Target t = target("react3");
            accept(t, member(uniq("w")));
            Member stranger = member(uniq("s"));
            WatcherNotice notice = confirmFailure(t, UUID.randomUUID());

            expectError(postAuth("/api/v1/watcher-notices/" + notice.getId() + "/reactions",
                    stranger.token(), Map.of("reaction", "CHEER")), 403, "NOT_WATCHER");
        }

        @Test
        @DisplayName("CHEER·TEASE 외의 값은 400 INVALID_REQUEST")
        void invalid_reaction_value() throws Exception {
            Target t = target("react4");
            Member watcher = member(uniq("w"));
            accept(t, watcher);
            WatcherNotice notice = confirmFailure(t, UUID.randomUUID());

            expectError(postAuth("/api/v1/watcher-notices/" + notice.getId() + "/reactions",
                    watcher.token(), Map.of("reaction", "ANGRY")), 400, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("없는 통지는 404 NOTICE_NOT_FOUND")
        void unknown_notice() throws Exception {
            Member watcher = member(uniq("w"));
            expectError(postAuth("/api/v1/watcher-notices/" + UUID.randomUUID() + "/reactions",
                    watcher.token(), Map.of("reaction", "CHEER")), 404, "NOTICE_NOT_FOUND");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("배치 — 자동 제거 · 만료 · 보정")
    class Batch {

        @Test
        @DisplayName("루틴이 끝난 챌린지의 관계를 자동 제거한다 — 유저가 끊는 경로가 없으니 이게 수신거부권이다")
        void removes_relations_of_finished_challenges() throws Exception {
            Target t = target("cleanup");
            Member watcher = member(uniq("w"));
            WatcherRelation relation = accept(t, watcher);

            jdbcTemplate.update("UPDATE challenges SET status = 'COMPLETED' WHERE id = ?",
                    bytes(t.challengeId()));
            batch.removeFinishedRelations();

            assertThat(relationRepository.findById(relation.getId()).orElseThrow().getRemovedAt())
                    .isNotNull();
        }

        @Test
        @DisplayName("진행 중인 챌린지의 관계는 건드리지 않는다")
        void keeps_ongoing_relations() throws Exception {
            Target t = target("keep");
            WatcherRelation relation = accept(t, member(uniq("w")));

            batch.removeFinishedRelations();

            assertThat(relationRepository.findById(relation.getId()).orElseThrow().getRemovedAt())
                    .isNull();
        }

        @Test
        @DisplayName("만료된 초대는 생성자에게만 알린다 — 감시자 후보는 아직 외부인이다")
        void expiry_notifies_inviter_only() throws Exception {
            Target t = target("expirynoti");
            invite(t);
            expireInvitations(t.challengeId(), "INTERVAL 1 DAY");

            batch.notifyExpiredInvitations();

            assertThat(notificationRepository.findInbox(t.owner().id(), null, null, Limit.unlimited()))
                    .anyMatch(n -> NotificationType.WATCHER_INVITATION_EXPIRED.name().equals(n.getType()));
        }

        @Test
        @DisplayName("만료 알림은 한 번만 보낸다")
        void expiry_notice_is_idempotent() throws Exception {
            Target t = target("expiryidem");
            invite(t);
            expireInvitations(t.challengeId(), "INTERVAL 1 DAY");

            batch.notifyExpiredInvitations();
            batch.notifyExpiredInvitations();

            long count = notificationRepository.findInbox(t.owner().id(), null, null, Limit.unlimited())
                    .stream()
                    .filter(n -> NotificationType.WATCHER_INVITATION_EXPIRED.name().equals(n.getType()))
                    .count();
            assertThat(count).isEqualTo(1);
        }
    }
}
