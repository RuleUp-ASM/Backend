package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.llm.LlmClient;
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
 * LLM 으로 "제목 생성 + 설명 교정 + 카테고리 분류 + 루틴 매칭 + 기본 설정"을 한 번에 받아오는
 * 클라이언트(왕복 1회, 경로 B Step 1~4). 입력은 설명 한 줄뿐이다(API 명세 — 구 제목 입력 폐기).
 * LLM 의 책임 경계는 그대로다(인증·권한·기간·정원·티어 값은 만들지 않는다).
 *
 * 실패/미설정/파싱오류는 ChallengeDraftSuggestion.empty() → 호출 측이 전부 폴백.
 */
@Component
public class GeminiChallengeDraftClient implements ChallengeDraftClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChallengeDraftClient.class);

    private static final String PROMPT = "challenge-draft";

    // 응답 형식은 프롬프트(STEP 5)가 강제한다 — 네이티브 responseSchema 를 쓰지 않는다.
    // 이유: flash-lite 계열은 responseSchema 의 정수 필드(templateId:INTEGER)에서 그리디 디코딩이
    // 폭주해("templateId": 2000000...) MAX_TOKENS 로 잘려 JSON 파싱이 실패한다(매칭되는 정상 입력마다
    // 재현). generateText(=application/json, 스키마 없음)로 부르면 폭주가 사라지고, 100케이스×5모델
    // 평가에서 프롬프트 강제만으로 필드 누락 없이 정확도가 더 높았다. 그래서 스키마를 제거했다.

    private final LlmClient llm;
    private final PromptLibrary prompts;

    public GeminiChallengeDraftClient(LlmClient llm, PromptLibrary prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public ChallengeDraftSuggestion suggest(String description, List<RoutineCandidate> candidates) {
        // [Step1] 입력 적합성 사전검사 — 명백히 부적합한 입력은 LLM 호출 전에 차단(fallback:true).
        if (isObviouslyInvalid(description)) {
            log.debug("챌린지 초안 제안: 로컬 사전검사에서 무효 입력 감지 → Step1 차단(fallback)");
            return ChallengeDraftSuggestion.block();
        }

        String content = llm.generateText(buildPrompt(description, candidates));
        if (content == null) {
            log.debug("챌린지 초안 제안: LLM 응답 없음 → 기본 템플릿 폴백 (후보 {}개)", candidates.size());
            return ChallengeDraftSuggestion.empty();
        }
        Draft d = llm.parseJson(content, Draft.class);
        if (d == null) {
            log.debug("챌린지 초안 제안: JSON 파싱 실패 → 기본 템플릿 폴백");
            return ChallengeDraftSuggestion.empty();
        }
        // [Step1·2] LLM 이 부적합/유해 입력으로 판정(usable=false)하면 초안을 만들지 않고 차단(fallback:true).
        if (Boolean.FALSE.equals(d.usable())) {
            log.debug("챌린지 초안 제안: LLM 이 부적합 입력으로 판정(reason={}) → Step1·2 차단(fallback)", d.rejectReason());
            return ChallengeDraftSuggestion.block();
        }
        log.debug("챌린지 초안 제안: templateId={} category={} participationType={}",
                d.templateId(), d.category(), d.participationType());
        return new ChallengeDraftSuggestion(
                new RoutineMatch(d.templateId(), toParamsMap(d.params())),
                new ChallengeSettings(d.title(), d.description(), d.category(),
                        d.participationType(), d.repeatDays()),
                false);
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
     * 의미 판단은 하지 않고, "습관 설명이 될 수 없는 형태"만 싸게 걸러낸다.
     *  - null/blank
     *  - 공백 제거 후 2자 미만
     *  - 글자(문자)가 하나도 없음(숫자·기호·이모지만)
     * 애매하면 통과시키고 판단은 LLM/서버 sanitize 로 미룬다(과잉 차단 방지).
     */
    private boolean isObviouslyInvalid(String description) {
        if (description == null) return true;
        String t = description.trim();
        if (t.length() < 2) return true;
        // 유니코드 문자(한글/영문 등)가 하나라도 있어야 유효 후보로 본다.
        boolean hasLetter = t.codePoints().anyMatch(Character::isLetter);
        return !hasLetter;
    }

    /**
     * 자동 인증 가능 루틴 전체(<100개)를 목업 룩업 테이블로 렌더링해 프롬프트에 싣는다.
     * 컬럼: id | 이름 | 카테고리 | 목표값키 | 설명. 카탈로그 순서가 고정이라 이 블록은 요청마다 동일 →
     * 고정 프리픽스로 캐시된다(민감한 인증/권한 필드는 넣지 않는다).
     */
    private String buildPrompt(String description, List<RoutineCandidate> candidates) {
        StringBuilder table = new StringBuilder("id | 이름 | 카테고리 | 목표값키 | 설명");
        for (RoutineCandidate c : candidates) {
            String keys = c.paramKeys().isEmpty() ? "-" : String.join(",", c.paramKeys());
            String desc = (c.description() == null || c.description().isBlank()) ? "-" : c.description();
            table.append("\n%d | %s | %s | %s | %s".formatted(c.id(), c.name(), c.category(), keys, desc));
        }

        return prompts.render(PROMPT, Map.of(
                "description", description == null ? "" : description,
                "candidates", table.toString()));
    }

    /** 통합 응답(날것). 적합성 플래그 + 루틴 매칭분 + 설정분을 한 JSON 으로 받는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Draft(
            Boolean usable,             // STEP 1·2 판정. false 면 초안 폐기(폴백). null(누락)은 관대하게 usable 취급.
            String rejectReason,        // usable=false 일 때 사유(로깅용)
            String title,               // AI 생성 제목(STEP 4)
            String description,         // AI 교정 설명(STEP 4)
            String category,            // 12종 코드(STEP 4, 분류 불가 시 ETC)
            Long templateId,
            List<ParamKV> params,       // 목표값 key/value 배열(스키마 강제). toParamsMap 으로 Map 변환.
            String participationType,
            List<String> repeatDays
    ) {}

    /** 목표값 한 쌍(스키마상 value 는 문자열; 숫자/시간 강제는 서버 ParamSpec 이 한다). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParamKV(String key, Object value) {}
}
