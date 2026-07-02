package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.llm.GeminiClient;
import com.ruleup.ruleup_backend.llm.PromptLibrary;
import com.ruleup.ruleup_backend.routine.match.RoutineCandidate;
import com.ruleup.ruleup_backend.routine.match.RoutineMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gemini 로 "루틴 매칭 + 챌린지 기본 설정"을 한 번에 받아오는 클라이언트(왕복 1회).
 *
 * 예전 분리 구조(GeminiRoutineMatchClient + GeminiChallengeSettingsClient)는 같은 입력으로
 * Gemini 를 직렬 두 번 불러 지연이 두 배였다. 둘 다 입력이 (제목, 설명)이고 서로 의존이 없어
 * 한 프롬프트로 합쳤다. LLM 의 책임 경계는 그대로다(인증·권한 값은 만들지 않는다).
 *
 * 실패/미설정/파싱오류는 ChallengeDraftSuggestion.empty() → 호출 측이 전부 폴백.
 */
@Component
public class GeminiChallengeDraftClient implements ChallengeDraftClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChallengeDraftClient.class);

    private static final String PROMPT = "challenge-draft";

    /**
     * 응답 스키마(Gemini OpenAPI 서브셋). enum(participationType·repeatDays)·타입·usable 필수를
     * API 레벨에서 강제한다. params 는 free-form 맵이라 스키마로 못 박기 어려워 공식 권장 우회인
     * key/value 문자열 객체 배열로 받는다(값은 서버 ParamSpec 이 숫자/시간으로 강제).
     *
     * participationType·repeatDays·params 는 required(non-null)로 둔다: optional 로 두면 flash-lite 가
     * 최소 출력으로 이들을 통째로 생략한다. 실측 결과 —
     *   - participationType/repeatDays 누락 → 사용자 의도(예: "주말만")가 서버 기본 '매일'로 덮여 사라짐.
     *   - params 누락 → 명시한 목표("5km")가 버려지고 템플릿 기본값으로 대체됨(추출 기능이 무력화).
     * required 는 정상 경로에서만 강제되고(부적합 판정 시엔 모델이 생략, 어차피 버리므로 무해),
     * params 는 배열이라 미언급 시 []({@code 언급 없으면 빈 배열} 프롬프트 지시)로 채워 hallucination 을 막는다.
     * rejectReason·templateId 는 optional(부적합/미매칭 때 null).
     */
    private static final String RESPONSE_SCHEMA = """
        {
          "type": "OBJECT",
          "properties": {
            "usable":       {"type": "BOOLEAN"},
            "rejectReason": {"type": "STRING", "nullable": true},
            "templateId":   {"type": "INTEGER", "nullable": true},
            "params": {
              "type": "ARRAY",
              "items": {
                "type": "OBJECT",
                "properties": {
                  "key":   {"type": "STRING"},
                  "value": {"type": "STRING"}
                },
                "required": ["key", "value"]
              }
            },
            "participationType": {"type": "STRING", "enum": ["SOLO", "GROUP"]},
            "repeatDays": {
              "type": "ARRAY",
              "items": {"type": "STRING", "enum": ["MON","TUE","WED","THU","FRI","SAT","SUN"]}
            }
          },
          "required": ["usable", "participationType", "repeatDays", "params"]
        }
        """;

    private final GeminiClient gemini;
    private final PromptLibrary prompts;

    public GeminiChallengeDraftClient(GeminiClient gemini, PromptLibrary prompts) {
        this.gemini = gemini;
        this.prompts = prompts;
    }

    @Override
    public ChallengeDraftSuggestion suggest(String title, String description, List<RoutineCandidate> candidates) {
        // [0] 잘못된 입력 빠른 감지 — 명백한 쓰레기 입력은 LLM 호출 전에 걸러 토큰·지연을 아낀다.
        if (isObviouslyInvalid(title)) {
            log.debug("챌린지 초안 제안: 로컬 사전검사에서 무효 입력 감지 → LLM 우회, 폴백");
            return ChallengeDraftSuggestion.empty();
        }

        String content = gemini.generateStructured(buildPrompt(title, description, candidates), RESPONSE_SCHEMA);
        if (content == null) {
            log.debug("챌린지 초안 제안: Gemini 응답 없음 → 폴백 (후보 {}개)", candidates.size());
            return ChallengeDraftSuggestion.empty();
        }
        Draft d = gemini.parseJson(content, Draft.class);
        if (d == null) {
            log.debug("챌린지 초안 제안: JSON 파싱 실패 → 폴백");
            return ChallengeDraftSuggestion.empty();
        }
        // [1-단계 결과] LLM 이 스스로 "습관 챌린지로 부적합"이라 판단(usable=false)하면 초안을 만들지 않고 폴백.
        if (Boolean.FALSE.equals(d.usable())) {
            log.debug("챌린지 초안 제안: LLM 이 부적합 입력으로 판정(reason={}) → 폴백", d.rejectReason());
            return ChallengeDraftSuggestion.empty();
        }
        log.debug("챌린지 초안 제안: templateId={} participationType={}",
                d.templateId(), d.participationType());
        return new ChallengeDraftSuggestion(
                new RoutineMatch(d.templateId(), toParamsMap(d.params())),
                new ChallengeSettings(d.participationType(), d.repeatDays()));
    }

    /** LLM 이 key/value 배열로 준 목표값을 서버가 쓰는 Map 으로 변환(값은 문자열 그대로, 검증은 ParamSpec). */
    private Map<String, Object> toParamsMap(List<ParamKV> list) {
        if (list == null || list.isEmpty()) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        for (ParamKV kv : list) {
            if (kv != null && kv.key() != null && !kv.key().isBlank()) {
                m.put(kv.key().trim(), kv.value());
            }
        }
        return m;
    }

    /**
     * LLM 호출 전에 도는 값싼 사전검사(§ 잘못된 입력 빠른 감지).
     * 의미 판단은 하지 않고, "습관 제목이 될 수 없는 형태"만 싸게 걸러낸다.
     *  - null/blank
     *  - 공백 제거 후 2자 미만
     *  - 글자(문자)가 하나도 없음(숫자·기호·이모지만)
     * 애매하면 통과시키고 판단은 LLM/서버 sanitize 로 미룬다(과잉 차단 방지).
     */
    private boolean isObviouslyInvalid(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.length() < 2) return true;
        // 유니코드 문자(한글/영문 등)가 하나라도 있어야 유효 후보로 본다.
        boolean hasLetter = t.codePoints().anyMatch(Character::isLetter);
        return !hasLetter;
    }

    /** 후보 목록을 주고 [1]루틴 매칭 + [2]챌린지 설정을 하나의 JSON 으로 받게 한다(프롬프트 본문은 resources/prompts). */
    private String buildPrompt(String title, String description, List<RoutineCandidate> candidates) {
        String list = candidates.stream()
                .map(c -> "%d. %s%s".formatted(
                        c.id(), c.name(),
                        c.paramKeys().isEmpty() ? "" : " (목표값: " + String.join(", ", c.paramKeys()) + ")"))
                .collect(Collectors.joining("\n"));

        return prompts.render(PROMPT, Map.of(
                "title", title == null ? "" : title,
                "description", description == null ? "" : description,
                "candidates", list));
    }

    /** 통합 응답(날것). 적합성 플래그 + 루틴 매칭분 + 설정분을 한 JSON 으로 받는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Draft(
            Boolean usable,             // STEP 1 판정. false 면 초안 폐기(폴백). null(누락)은 관대하게 usable 취급.
            String rejectReason,        // usable=false 일 때 사유(로깅용)
            Long templateId,
            List<ParamKV> params,       // 목표값 key/value 배열(스키마 강제). toParamsMap 으로 Map 변환.
            String participationType,
            List<String> repeatDays
    ) {}

    /** 목표값 한 쌍(스키마상 value 는 문자열; 숫자/시간 강제는 서버 ParamSpec 이 한다). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParamKV(String key, Object value) {}
}
