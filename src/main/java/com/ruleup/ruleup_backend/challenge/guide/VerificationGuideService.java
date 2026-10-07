package com.ruleup.ruleup_backend.challenge.guide;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.llm.LlmClient;
import com.ruleup.ruleup_backend.llm.PromptLibrary;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 인증 방법 안내 문구({@code challenges.verification_guide})를 만든다.
 *
 * <p><b>요청 경로에서 부르지 않는다.</b> LLM 은 최악 수십 초가 걸리므로 생성·수정 커밋 뒤
 * {@link VerificationGuideEventListener} 가 비동기로 부르고, 그 사이 상세 조회는 null 을 내려
 * 앱이 「아직 입력중입니다.」를 보여 준다. 이벤트가 유실돼도(재배포 등) 5분 보정 스캔이 채운다.
 *
 * <p>LLM 이 실패하거나 응답이 기준(확정 값 포함·끝맺음·길이)을 어기면 서버 기본 문구로 채운다 —
 * 안내가 영영 「입력중」에 머무는 일은 없다.
 *
 * <p>쓰기는 조건부 UPDATE 하나다. 엔티티로 저장하면 PATCH 낙관 잠금과 엮이고, 생성하는 동안
 * 방장이 목표값을 바꿨다면 옛 조건의 안내가 덮어쓰게 된다 — 지문이 그대로일 때만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class VerificationGuideService {

    private static final Logger log = LoggerFactory.getLogger(VerificationGuideService.class);
    private static final String PROMPT = "verification-guide";
    private static final String SCHEMA =
            "{\"type\":\"OBJECT\",\"properties\":{\"guide\":{\"type\":\"STRING\"}},\"required\":[\"guide\"]}";
    static final int MAX_LENGTH = 120;

    private final ChallengeRepository challengeRepository;
    private final RoutineCatalog catalog;
    private final LlmClient llm;
    private final PromptLibrary prompts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /** 비어 있으면 채운다. 이미 있으면(다른 태스크가 먼저 채움) 아무것도 하지 않는다. */
    public void generate(UUID challengeId) {
        Optional<VerificationGuideFacts> facts = tx.execute(s -> load(challengeId));
        if (facts == null || facts.isEmpty()) return;

        VerificationGuideFacts f = facts.get();
        String guide = polish(f);
        boolean fromLlm = guide != null;
        if (guide == null) guide = f.fallback();

        String text = guide;
        Boolean written = tx.execute(s -> {
            // 생성하는 동안 조건이 바뀌었으면 버린다 — 바꾼 쪽이 새 요청을 이미 냈다
            Optional<VerificationGuideFacts> now = load(challengeId);
            if (now.isEmpty() || !now.get().fingerprint().equals(f.fingerprint())) return false;
            return jdbc.update("UPDATE challenges SET verification_guide = ? "
                    + "WHERE id = ? AND verification_guide IS NULL", text, bytes(challengeId)) == 1;
        });
        log.info("verification_guide_filled challengeId={} method={} source={} written={}",
                challengeId, f.method(), fromLlm ? "LLM" : "FALLBACK", written);
    }

    /** 인증 조건이 바뀌었다 — 옛 안내를 비우고 커밋 뒤 다시 만든다. 호출자의 트랜잭션 안에서 부른다. */
    public void reset(UUID challengeId) {
        jdbc.update("UPDATE challenges SET verification_guide = NULL WHERE id = ?", bytes(challengeId));
    }

    private Optional<VerificationGuideFacts> load(UUID challengeId) {
        return challengeRepository.findById(challengeId)
                .filter(c -> c.getDeletedAt() == null)
                .filter(c -> currentGuide(challengeId) == null)
                .map(c -> VerificationGuideFacts.of(c, template(c)));
    }

    /** 엔티티의 열은 읽기 전용 매핑이라 1차 캐시가 옛 값을 줄 수 있다 — 행에서 바로 읽는다. */
    private String currentGuide(UUID challengeId) {
        return jdbc.query("SELECT verification_guide FROM challenges WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, bytes(challengeId));
    }

    private RoutineTemplate template(Challenge c) {
        return c.getTemplateId() != null ? catalog.findById(c.getTemplateId()).orElse(null) : null;
    }

    /** LLM 으로 문장을 다듬는다. 확정 값이 빠졌거나 형식이 어긋나면 null(→ 기본 문구). */
    String polish(VerificationGuideFacts f) {
        if (!llm.isConfigured()) return null;
        String prompt = prompts.render(PROMPT, Map.of(
                "mode", f.auto() ? "자동 인증" : "수동 인증(사용자가 앱에서 직접 인증)",
                "routineName", f.routineName() != null ? f.routineName() : "(없음)",
                "frequency", f.frequency(),
                "conditions", f.conditions().stream().map(c -> "  - " + c).collect(Collectors.joining("\n")),
                "title", f.title() != null ? f.title() : ""));
        String raw = llm.generateStructured(prompt, SCHEMA);
        if (raw == null) return null;
        Response r = llm.parseJson(raw, Response.class);
        String guide = (r != null && r.guide() != null) ? r.guide().strip() : null;
        if (!acceptable(f, guide)) {
            log.info("verification_guide_rejected method={} reason=rule_violation", f.method());
            return null;
        }
        return guide;
    }

    static boolean acceptable(VerificationGuideFacts f, String guide) {
        if (guide == null || guide.isBlank() || guide.length() > MAX_LENGTH) return false;
        if (guide.contains("\n") || guide.contains("\"")) return false;
        for (String must : f.mustContain()) {
            if (!guide.contains(must)) return false;   // 판정 기준 값이 바뀌거나 빠졌다
        }
        // 「매일」은 장소 피하기처럼 문장에서 자연스럽게 빠질 수 있다. 「주 3회」는 빠지면 조건이 바뀐다
        if (!"매일".equals(f.frequency()) && !guide.contains(f.frequency())) return false;
        return f.auto() ? guide.contains("자동 인증") : guide.contains("직접 인증");
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    record Response(String guide) {}
}
