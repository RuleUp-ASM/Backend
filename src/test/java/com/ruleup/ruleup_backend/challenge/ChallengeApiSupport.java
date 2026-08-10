package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 챌린지 통합 테스트 공통 헬퍼.
 *  - 가입 → accessToken 발급 (AuthApiSupport 재사용)
 *  - 루틴 템플릿 픽스처 insert (루틴 테이블은 시드 없이 스키마만 — 테스트가 직접 채운다)
 *  - challenges/challenge_members 픽스처 insert (배치·게이트 상황 재현용)
 */
public abstract class ChallengeApiSupport extends AuthApiSupport {

    protected abstract JdbcTemplate jdbc();

    /** 가입 완료 회원(accessToken + userId). */
    protected record Member(String token, UUID id) {}

    /** 가입까지 완료하고 accessToken·userId 를 돌려준다. */
    protected Member member(String tag) throws Exception {
        MvcResult res = signup(tag, "닉" + uniq("").substring(0, 8));
        String token = read(res, "$.data.accessToken");
        String id = read(res, "$.data.user.id");
        return new Member(token, UUID.fromString(id));
    }

    protected String memberToken(String tag) throws Exception {
        return member(tag).token();
    }

    // ===== 픽스처 =====

    /** 자동 인증 가능 루틴 템플릿(+인증 정의) 1건 insert. 카테고리는 12종 코드. */
    protected void insertAutoTemplate(long id, String name, String description, String category,
                                      String paramSchemaJson, String method, String permsJson) {
        jdbc().update("INSERT INTO RoutineTemplate (id, name, description, category, paramSchema, rationale) " +
                "VALUES (?, ?, ?, ?, ?, ?)", id, name, description, category, paramSchemaJson, "테스트 근거");
        jdbc().update("INSERT INTO RoutineVerification " +
                        "(templateId, autoVerificationType, autoSignalSource, autoWearableReq, " +
                        " autoRequiredPermissions, manualSignalSource, verificationMethod) " +
                        "VALUES (?, 'PHONE', 'USAGE', 'NONE', ?, 'SELF_CHECK', ?)",
                id, permsJson, method);
    }

    /** 챌린지 1건 직접 insert(간이) — 상태·카테고리만 관심 있을 때. 반환: challengeId */
    protected UUID insertChallenge(UUID ownerId, String category, String status, String mode) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO challenges " +
                        "(id, owner_id, title, ai_title, description, category, mode, capacity, repeat_days, " +
                        " duration_days, start_date, end_date, verification_config, params, " +
                        " penalty_config, reward_config, anonymity, status, moderation_status, ai_assisted, participant_count) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 50, '[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\",\"SAT\",\"SUN\"]', " +
                        " 14, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 14 DAY), " +
                        " '{\"selectedMethod\":\"MANUAL\",\"verificationType\":\"MANUAL\",\"signalSource\":\"SELF_CHECK\",\"wearableReq\":\"NONE\",\"requiredPermissions\":[]}', " +
                        " '{}', '{\"mannerDeduction\":1.0}', '{\"mannerGain\":1.0}', 'REAL', ?, 'NONE', 1, 1)",
                bytes(id), bytes(ownerId), "테스트 챌린지", "테스트 챌린지", "설명", category, mode, status);
        return id;
    }

    protected void insertActiveMembership(UUID challengeId, UUID userId, String role) {
        jdbc().update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE')",
                bytes(UUID.randomUUID()), bytes(challengeId), bytes(userId), role);
    }

    // ===== 호출 =====

    protected MvcResult getAuth(String url, String accessToken) throws Exception {
        return mvc().perform(get(url).header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    protected MvcResult patchJsonAuth(String url, String accessToken, Map<String, Object> body) throws Exception {
        return mvc().perform(patch(url).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    protected static byte[] bytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
