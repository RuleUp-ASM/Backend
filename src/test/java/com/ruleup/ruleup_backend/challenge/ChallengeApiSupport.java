package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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

    @Autowired
    protected RoutineCatalog routineCatalog;

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
        routineCatalog.evict();   // 메모리 카탈로그 캐시 무효화 — 공유 컨텍스트에서도 새 픽스처가 보이게
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

    // ===== 동시 참여 슬롯(user_challenge_counters) =====

    /** 저장 카운터를 직접 심는다. 원천(멤버십)과 어긋난 상태를 만들 때만 쓸 것 — 게이트는 원천을 본다. */
    protected void setCounter(UUID userId, int count) {
        jdbc().update("INSERT INTO user_challenge_counters (user_id, active_join_count) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE active_join_count = VALUES(active_join_count)", bytes(userId), count);
    }

    protected int counterOf(UUID userId) {
        Integer v = jdbc().queryForObject(
                "SELECT active_join_count FROM user_challenge_counters WHERE user_id = ?",
                Integer.class, bytes(userId));
        return v != null ? v : 0;
    }

    /**
     * 슬롯 n개를 <b>실제로</b> 점유시킨다 — 진행 중인 방 n개 + ACTIVE 멤버십 + 카운터 동기화.
     * 가입 게이트가 저장값이 아니라 원천을 세므로, 카운터만 심어서는 FREE_LIMIT 이 재현되지 않는다.
     * 만든 방 id 들을 반환한다(종료·삭제시켜 슬롯 회수를 검증할 때 쓴다).
     */
    protected List<UUID> occupySlots(UUID userId, int slots) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            UUID id = insertChallenge(userId, "EXERCISE", "ACTIVE", "GROUP");
            insertActiveMembership(id, userId, "OWNER");
            ids.add(id);
        }
        setCounter(userId, slots);
        return ids;
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
