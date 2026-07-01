package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.llm.GeminiClient;
import com.ruleup.ruleup_backend.routine.match.RoutineCandidate;
import com.ruleup.ruleup_backend.routine.match.RoutineMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    private final GeminiClient gemini;

    public GeminiChallengeDraftClient(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public ChallengeDraftSuggestion suggest(String title, String description, List<RoutineCandidate> candidates) {
        // [0] 잘못된 입력 빠른 감지 — 명백한 쓰레기 입력은 LLM 호출 전에 걸러 토큰·지연을 아낀다.
        if (isObviouslyInvalid(title)) {
            log.debug("챌린지 초안 제안: 로컬 사전검사에서 무효 입력 감지 → LLM 우회, 폴백");
            return ChallengeDraftSuggestion.empty();
        }

        String content = gemini.generateText(buildPrompt(title, description, candidates));
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
        log.debug("챌린지 초안 제안: templateId={} participationType={} durationDays={}",
                d.templateId(), d.participationType(), d.durationDays());
        return new ChallengeDraftSuggestion(
                new RoutineMatch(d.templateId(), d.params()),
                new ChallengeSettings(d.participationType(), d.repeatDays(),
                        d.durationDays(), d.mannerDeduction(), d.mannerGain()));
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

    /** 후보 목록을 주고 [1]루틴 매칭 + [2]챌린지 설정을 하나의 JSON 으로 받게 한다. */
    private String buildPrompt(String title, String description, List<RoutineCandidate> candidates) {
        String list = candidates.stream()
                .map(c -> "%d. %s%s".formatted(
                        c.id(), c.name(),
                        c.paramKeys().isEmpty() ? "" : " (목표값: " + String.join(", ", c.paramKeys()) + ")"))
                .collect(Collectors.joining("\n"));

        return """
            너는 습관 챌린지 초안 도우미다. 사용자의 제목/설명을 보고 초안을 JSON 하나로 만든다.
            아래 STEP 을 순서대로 수행하되, 최종 출력은 JSON 객체 하나뿐이다(설명·주석·마크다운 금지).

            [입력]
            제목: %s
            설명: %s

            후보 루틴(id. 이름 (목표값 키)):
            %s

            ────────────────────────────────
            STEP 1. 입력 적합성 판정 (잘못된 입력 빠른 감지)
              먼저 이 입력이 "반복 실천 가능한 습관 챌린지"로 성립하는지 본다.
              아래 중 하나라도 해당하면 부적합 → STEP 2~4 를 건너뛰고 즉시 아래만 출력:
                {"usable": false, "rejectReason": "<사유 한 줄>"}
              부적합 예:
                - 의미 없는 문자열/무작위 키 입력(예: "asdf", "ㅁㄴㅇㄹ", "123")
                - 습관과 무관(단순 질문·잡담·욕설·광고·부적절/유해 내용)
                - 일회성이라 반복 불가(예: "여권 재발급", "이사하기")
              적합하면 {"usable": true, ...} 로 STEP 2 로 진행한다.

            STEP 2. 루틴 매칭
              - templateId: 후보 중 의미가 가장 가까운 루틴의 id(숫자). 적절한 게 없으면 null.
                후보에 없는 id 를 지어내지 말 것.
              - params: 사용자가 "명시한" 목표값만 채운다. 키는 매칭된 후보의 "목표값 키"만 사용.
                숫자는 숫자로(예: 5), 시간은 "HH:mm" 문자열로(예: "07:00").
                언급이 없으면 비운다({}). 임의로 추측해 새 키를 만들지 말 것.

            STEP 3. 챌린지 설정
              - participationType: "SOLO"(혼자) 또는 "GROUP"(같이 하는 게 자연스러움).
              - repeatDays: ["MON","TUE","WED","THU","FRI","SAT","SUN"] 중 골라 배열로. 매일이면 7개 전부.
              - durationDays: 챌린지 기간(일). 보통 7~30.
              - mannerDeduction: 실패 시 매너 차감(0 이상 숫자, 보통 0.5~3.0).
              - mannerGain: 성공 시 매너 가산(0 이상 숫자, 보통 0.5~3.0).

            STEP 4. 자체 점검 후 출력
              - templateId 가 후보에 실재하는 id 인지, params 키가 그 후보 키인지 다시 확인.
              - 모든 필드를 담아 JSON 하나로만 출력.

            ────────────────────────────────
            출력 스키마(적합):
            {"usable":true,"templateId":2,"params":{"distance_km":5},"participationType":"SOLO","repeatDays":["MON","TUE","WED","THU","FRI"],"durationDays":14,"mannerDeduction":1.0,"mannerGain":1.0}
            출력 스키마(부적합):
            {"usable":false,"rejectReason":"의미 없는 입력"}
            """.formatted(title, description == null ? "" : description, list);
    }

    /** 통합 응답(날것). 적합성 플래그 + 루틴 매칭분 + 설정분을 한 JSON 으로 받는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Draft(
            Boolean usable,             // STEP 1 판정. false 면 초안 폐기(폴백). null(누락)은 관대하게 usable 취급.
            String rejectReason,        // usable=false 일 때 사유(로깅용)
            Long templateId,
            Map<String, Object> params,
            String participationType,
            List<String> repeatDays,
            Integer durationDays,
            BigDecimal mannerDeduction,
            BigDecimal mannerGain
    ) {}
}
