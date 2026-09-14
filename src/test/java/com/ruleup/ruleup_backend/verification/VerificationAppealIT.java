package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 인증 이의제기 — <b>판정하지 않는다</b> (인증 정책 §5 · API 명세 POST /verifications/{id}/appeals).
 *
 * <p>이의의 대다수인 "실제로 했는데 측정이 틀렸다"는 증빙의 진위를 봇도 사람도 검증할 수 없다.
 * 그래서 <b>결정적인 형식 요건만</b> 검사하고 통과하면 즉시 자동 인용한다.
 * <ul>
 *   <li>본인 · 실패로 확정됐거나 이대로면 실패 · 기한(확정 시각과 같은 귀속일+2일 00:00 KST) · 사유 10자 이상</li>
 *   <li>사진은 선택이며 진위 확인에 쓰지 않는다</li>
 *   <li>횟수 한도 없음 — 남용은 인용과 분리된 이상탐지가 본다</li>
 *   <li>LLM·방장·MANAGER 는 인용 여부를 판단하지 않는다</li>
 * </ul>
 *
 * <p>이의는 확정 <b>전에</b> 받는다. 확정이 귀속일 이틀 뒤이고 기한도 같은 시각이라, 실제 신청 창은
 * 귀속일이 끝난 뒤의 유예 하루다 — 유저는 "이대로면 실패"를 보고 신청한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationAppealIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;
    private static final String REASON = "지하철 구간에서 GPS가 끊겨 체류 기록이 누락됐어요";

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationFinalizeService finalizeService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    // ===== 시나리오 조립 =====

    /** 실패가 확정된 인증 1건을 만들고 그 verificationId 를 돌려준다. */
    private record FailedVerification(Member owner, UUID challengeId, UUID memberId, UUID verificationId) {}

    private FailedVerification failedVerification(String tag) throws Exception {
        Member me = member(uniq(tag));
        UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE",
                "{\"duration_min\":30,\"radius_m\":100}");
        UUID memberId = insertReadyMember(challengeId, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

        // 어제 10분만 머물러 목표 미달 → <b>실패 예정</b>. 이의는 바로 이 상태에서 받는다.
        //
        // 예전 픽스처는 오늘 날짜 행의 finalizeAfter 만 과거로 돌려 「확정된 FAILED + 이의 창
        // 열림」을 만들었는데, 그 조합은 <b>운영에서 생길 수 없다</b> — 이의 기한이 확정 시각과
        // 같아서, 확정되는 순간 창도 닫히기 때문이다(공통 5-1 「이의는 확정 전에 받는다」).
        // 그 불가능한 상태에 기대면 「확정 시각 전 확정 금지」 같은 불변식을 테스트가 우회한다.
        startedDaysAgo(challengeId, 5);
        MvcResult res = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                geofenceSignal(memberId, "ENTER", yesterdayAt(9, 0)),
                geofenceSignal(memberId, "EXIT", yesterdayAt(9, 10)))));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOn(memberId, appealableDate()))
                .as("귀속일이 끝났는데 미달이면 실패 예정이다 — 저장 상태는 PENDING")
                .isEqualTo("PENDING");

        return new FailedVerification(me, challengeId, memberId, verificationIdOf(memberId));
    }

    /**
     * 이의를 받을 수 있는 귀속일 — <b>어제</b>.
     *
     * <p>귀속일은 끝났고(더 채울 기회가 없다) 확정은 아직이다(D+2 00:00 전). 스펙이 말하는
     * 「실패 예정」이 정확히 이 구간이고, 이의 창도 여기서만 열린다.
     */
    private static LocalDate appealableDate() {
        return LocalDate.now(KST).minusDays(1);
    }

    /** 어제(KST) 시:분. */
    private static java.time.Instant yesterdayAt(int hour, int minute) {
        return todayAt(hour, minute).minusSeconds(86_400);
    }

    /** 챌린지 시작일을 당겨 과거 귀속일도 인증 대상이 되게 한다. */
    private void startedDaysAgo(UUID challengeId, int days) {
        jdbc().update("UPDATE challenges SET start_date = " +
                        " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY) " +
                        "WHERE id = ?", days, bytes(challengeId));
    }

    private String statusOn(UUID challengeMemberId, LocalDate date) {
        return jdbc().queryForObject(
                "SELECT status FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                String.class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
    }

    private UUID verificationIdOf(UUID challengeMemberId) {
        return verificationIdOn(challengeMemberId, appealableDate());
    }

    private UUID verificationIdOn(UUID challengeMemberId, LocalDate date) {
        byte[] raw = jdbc().queryForObject(
                "SELECT id FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                byte[].class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }

    private MvcResult appeal(String token, UUID verificationId, String reason, String imageUrl) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("imageUrl", imageUrl);
        return postJsonAuth("/api/v1/verifications/" + verificationId + "/appeals", token, body);
    }

    private int appealCountOf(UUID verificationId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM verification_appeals WHERE verificationDailyId = ?",
                Integer.class, bytes(verificationId));
        return n != null ? n : 0;
    }

    @Nested
    @DisplayName("자동 인용")
    class AutoAccept {

        @Test
        @DisplayName("형식 요건을 통과하면 즉시 인용되고 인증이 완료로 정정된다")
        void acceptedImmediately() throws Exception {
            FailedVerification f = failedVerification("appeal-ok");

            MvcResult res = appeal(f.owner().token(), f.verificationId(), REASON, null);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.result")).isEqualTo("ACCEPTED");
            assertThat((String) read(res, "$.data.appealId")).isNotBlank();
            assertThat((String) read(res, "$.data.restored.verification")).isEqualTo("DONE");
            assertThat((Integer) read(res, "$.data.restored.streak")).isNotNull();

            assertThat(statusOn(f.memberId(), appealableDate()))
                    .as("정상 성공과 같게 완료로 정정된다")
                    .isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("인용된 인증은 실패 공유 대상에서 빠진다")
        void acceptedAppealRemovesTheFailureFromTheFeed() throws Exception {
            FailedVerification f = failedVerification("appeal-share");
            appeal(f.owner().token(), f.verificationId(), REASON, null);

            String verifiedVia = jdbc().queryForObject(
                    "SELECT verifiedVia FROM VerificationDaily WHERE id = ?", String.class, bytes(f.verificationId()));
            assertThat(verifiedVia).isEqualTo("APPEAL");
            assertThat(jdbc().queryForObject(
                    "SELECT appealClosesAt FROM VerificationDaily WHERE id = ?", String.class, bytes(f.verificationId())))
                    .as("정정됐으므로 더는 이의 대상이 아니다")
                    .isNull();
        }

        @Test
        @DisplayName("사진 없이도 인용된다 — 사진은 선택이고 진위 확인에 쓰지 않는다")
        void photoIsOptional() throws Exception {
            FailedVerification f = failedVerification("appeal-nophoto");
            MvcResult res = appeal(f.owner().token(), f.verificationId(), REASON, null);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.result")).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("업로드한 사진을 첨부해도 결과는 같다 — 저장만 하고 판단에 쓰지 않는다")
        void photoIsStoredButNotJudged() throws Exception {
            FailedVerification f = failedVerification("appeal-photo");

            MvcResult upload = mvc.perform(multipart("/api/v1/appeals/images")
                            .file(new MockMultipartFile("file", "proof.png", "image/png", pngBytes()))
                            .header("Authorization", "Bearer " + f.owner().token()))
                    .andReturn();
            assertThat(upload.getResponse().getStatus()).isEqualTo(200);
            String imageUrl = read(upload, "$.data.imageUrl");
            assertThat(imageUrl).isNotBlank();

            MvcResult res = appeal(f.owner().token(), f.verificationId(), REASON, imageUrl);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.result")).isEqualTo("ACCEPTED");
            assertThat(jdbc().queryForObject(
                    "SELECT imageUrl FROM verification_appeals WHERE verificationDailyId = ?",
                    String.class, bytes(f.verificationId()))).isEqualTo(imageUrl);
        }

        @Test
        @DisplayName("횟수 한도가 없다 — 서로 다른 날짜의 실패를 계속 인용받을 수 있다")
        void noQuota() throws Exception {
            FailedVerification f = failedVerification("appeal-quota");

            // 과거 실패 2건을 더 심는다(같은 멤버, 다른 날짜).
            // 어제는 픽스처가 이미 쓰고 있다 — uq(challengeMemberId, targetDate) 와 부딪히지 않게 비킨다.
            for (int daysAgo = 2; daysAgo <= 3; daysAgo++) {
                UUID id = UUID.randomUUID();
                jdbc().update("INSERT INTO VerificationDaily " +
                                "(id, challengeMemberId, challengeId, userId, targetDate, status, method, " +
                                " failureReason, verifiedAt, appealClosesAt, shareableAt) " +
                                "VALUES (?, ?, ?, ?, DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY), 'FAILED', 'GPS_PRESENCE', " +
                                " 'INSUFFICIENT_DWELL', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 DAY, " +
                                " UTC_TIMESTAMP(6) + INTERVAL 1 DAY)",
                        bytes(id), bytes(f.memberId()), bytes(f.challengeId()), bytes(f.owner().id()), daysAgo);
                assertThat(appeal(f.owner().token(), id, REASON, null).getResponse().getStatus())
                        .as("%d일 전 실패".formatted(daysAgo)).isEqualTo(200);
            }
            assertThat(appeal(f.owner().token(), f.verificationId(), REASON, null)
                    .getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("실패 예정 구간 — 확정 전 신청")
    class BeforeConfirmation {

        /** 어제 귀속으로 목표 미달인 채 남은 건. 귀속일은 끝났고 확정은 아직 — 실제 이의 신청 창이다. */
        private UUID failExpectedYesterday(Member me, UUID challengeId, UUID memberId) {
            UUID verificationId = UUID.randomUUID();
            jdbc().update("INSERT INTO VerificationDaily " +
                            "(id, challengeMemberId, challengeId, userId, targetDate, status, method, " +
                            " finalizeAfter, appealClosesAt) " +
                            "VALUES (?, ?, ?, ?, DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 1 DAY), 'PENDING', 'GPS_PRESENCE', " +
                            " UTC_TIMESTAMP(6) + INTERVAL 1 DAY, UTC_TIMESTAMP(6) + INTERVAL 1 DAY)",
                    bytes(verificationId), bytes(memberId), bytes(challengeId), bytes(me.id()));
            return verificationId;
        }

        @Test
        @DisplayName("확정 전 '실패 예정' 상태에서 이의를 낼 수 있다 — 이게 실제 신청 창이다")
        void appealDuringGraceWindow() throws Exception {
            Member me = member(uniq("appeal-grace"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE",
                    "{\"duration_min\":30,\"radius_m\":100}");
            UUID memberId = insertReadyMember(challengeId, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            UUID verificationId = failExpectedYesterday(me, challengeId, memberId);

            MvcResult res = appeal(me.token(), verificationId, REASON, null);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.result")).isEqualTo("ACCEPTED");
            assertThat(jdbc().queryForObject(
                    "SELECT status FROM VerificationDaily WHERE id = ?", String.class, bytes(verificationId)))
                    .as("확정을 기다리지 않고 즉시 완료로 정정된다")
                    .isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("아직 채울 기회가 남은 오늘 건에는 신청할 수 없다")
        void todayStillInProgressIsNotAppealable() throws Exception {
            Member me = member(uniq("appeal-today"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE",
                    "{\"duration_min\":30,\"radius_m\":100}");
            UUID memberId = insertReadyMember(challengeId, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(9, 10)))));

            expectError(appeal(me.token(), verificationIdOn(memberId, LocalDate.now(KST)), REASON, null),
                    409, "NOT_FAILED");
        }
    }

    @Nested
    @DisplayName("형식 요건")
    class FormatRequirements {

        @Test
        @DisplayName("사유가 10자 미만이면 접수하지 않는다 — 이력도 남기지 않는다")
        void reasonMustBeAtLeastTenCharacters() throws Exception {
            FailedVerification f = failedVerification("appeal-short");

            MvcResult res = appeal(f.owner().token(), f.verificationId(), "짧은사유", null);

            expectError(res, 400, "INVALID_REASON");
            assertThat(statusOn(f.memberId(), appealableDate()))
                    .as("정정되지 않는다 — 실패 예정 그대로다").isEqualTo("PENDING");
            assertThat(appealCountOf(f.verificationId())).as("접수 이력이 없다").isZero();
        }

        @Test
        @DisplayName("사유가 비어 있어도 접수하지 않는다")
        void reasonIsRequired() throws Exception {
            FailedVerification f = failedVerification("appeal-empty");
            expectError(appeal(f.owner().token(), f.verificationId(), null, null), 400, "INVALID_REASON");
            expectError(appeal(f.owner().token(), f.verificationId(), "          ", null), 400, "INVALID_REASON");
        }

        @Test
        @DisplayName("실패로 확정되지 않은 인증에는 신청할 수 없다")
        void onlyFailedVerificationsAreAppealable() throws Exception {
            Member me = member(uniq("appeal-notfailed"));
            UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE",
                    "{\"duration_min\":30,\"radius_m\":100}");
            UUID memberId = insertReadyMember(challengeId, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                    geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                    geofenceSignal(memberId, "EXIT", todayAt(10, 0)))));
            assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");

            expectError(appeal(me.token(), verificationIdOn(memberId, LocalDate.now(KST)), REASON, null),
                    409, "NOT_FAILED");
        }

        @Test
        @DisplayName("기한이 지나면 신청할 수 없다 — 확정 시각과 같은 자정 경계로 끊는다")
        void windowClosesAtMidnightAfterConfirmation() throws Exception {
            FailedVerification f = failedVerification("appeal-late");
            jdbc().update("UPDATE VerificationDaily SET appealClosesAt = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE id = ?",
                    bytes(f.verificationId()));

            expectError(appeal(f.owner().token(), f.verificationId(), REASON, null), 409, "APPEAL_WINDOW_CLOSED");
            assertThat(statusOn(f.memberId(), appealableDate())).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("남의 인증에는 신청할 수 없다")
        void onlyTheOwnerCanAppeal() throws Exception {
            FailedVerification f = failedVerification("appeal-owner");
            Member other = member(uniq("appeal-other"));

            expectError(appeal(other.token(), f.verificationId(), REASON, null), 404, "VERIFICATION_NOT_FOUND");
            assertThat(statusOn(f.memberId(), appealableDate())).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("없는 인증이면 404")
        void unknownVerification() throws Exception {
            Member me = member(uniq("appeal-404"));
            expectError(appeal(me.token(), UUID.randomUUID(), REASON, null), 404, "VERIFICATION_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("멱등")
    class Idempotency {

        @Test
        @DisplayName("같은 인증에 두 번 신청해도 정정과 이력이 한 번만 남는다")
        void secondAppealOnTheSameVerificationIsRejected() throws Exception {
            FailedVerification f = failedVerification("appeal-idem");

            assertThat(appeal(f.owner().token(), f.verificationId(), REASON, null)
                    .getResponse().getStatus()).isEqualTo(200);

            // 이미 완료로 정정됐으므로 두 번째 요청은 "실패 상태가 아니다".
            expectError(appeal(f.owner().token(), f.verificationId(), REASON, null), 409, "NOT_FAILED");

            assertThat(appealCountOf(f.verificationId())).isEqualTo(1);
            assertThat(statusOn(f.memberId(), appealableDate())).isEqualTo("SUCCESS");
        }
    }

    @Nested
    @DisplayName("폐기된 경로")
    class RemovedPaths {

        @Test
        @DisplayName("방장·공동 관리자의 이의 승인·기각 경로는 사라졌다 — 방장은 인증을 판정하지 않는다")
        void adminDecisionEndpointsAreGone() throws Exception {
            FailedVerification f = failedVerification("appeal-noadmin");

            MvcResult decision = postJsonAuth(
                    "/api/v1/challenges/" + f.challengeId() + "/objections/" + UUID.randomUUID() + "/decision",
                    f.owner().token(), Map.of("decision", "REJECT"));
            assertThat(decision.getResponse().getStatus()).isEqualTo(404);

            MvcResult pending = getAuth(
                    "/api/v1/challenges/" + f.challengeId() + "/pending-reviews", f.owner().token());
            assertThat(pending.getResponse().getStatus()).isEqualTo(404);
        }
    }

    /** 매직넘버 검사를 통과하는 최소 PNG. */
    private static byte[] pngBytes() {
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] out = new byte[64];
        System.arraycopy(header, 0, out, 0, header.length);
        return out;
    }
}
