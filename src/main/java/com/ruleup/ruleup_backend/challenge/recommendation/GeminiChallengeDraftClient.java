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
        log.debug("챌린지 초안 제안: templateId={} participationType={} durationDays={}",
                d.templateId(), d.participationType(), d.durationDays());
        return new ChallengeDraftSuggestion(
                new RoutineMatch(d.templateId(), d.params()),
                new ChallengeSettings(d.participationType(), d.repeatDays(),
                        d.durationDays(), d.mannerDeduction(), d.mannerGain()));
    }

    /** 후보 목록을 주고 [1]루틴 매칭 + [2]챌린지 설정을 하나의 JSON 으로 받게 한다. */
    private String buildPrompt(String title, String description, List<RoutineCandidate> candidates) {
        String list = candidates.stream()
                .map(c -> "%d. %s%s".formatted(
                        c.id(), c.name(),
                        c.paramKeys().isEmpty() ? "" : " (목표값: " + String.join(", ", c.paramKeys()) + ")"))
                .collect(Collectors.joining("\n"));

        return """
            너는 습관 챌린지 초안 도우미다. 사용자가 입력한 제목/설명을 보고 아래 [1][2]를 한 번에 정한다.
            반드시 JSON 으로만 답하라(설명 금지).

            제목: %s
            설명: %s

            후보 루틴(id. 이름 (목표값 키)):
            %s

            [1] 루틴 매칭
            - templateId: 위 후보 중 의미가 가장 가까운 루틴의 id(숫자). 적절한 게 없으면 null.
            - params: 사용자가 명시한 목표값만 채운다. 키는 해당 후보의 "목표값 키"만 사용.
              숫자는 숫자로(예: 5), 시간은 "HH:mm" 문자열로(예: "07:00"). 언급 없으면 비운다({}).
              추측해서 새 키를 만들지 말 것. 후보에 없는 id 를 쓰지 말 것.

            [2] 챌린지 기본 설정
            - participationType: "SOLO" 또는 "GROUP" (혼자 할 습관이면 SOLO, 같이 하는 게 자연스러우면 GROUP)
            - repeatDays: 요일 배열. ["MON","TUE","WED","THU","FRI","SAT","SUN"] 중 적절히. 매일이면 7개 전부.
            - durationDays: 챌린지 기간(일). 보통 7~30.
            - mannerDeduction: 실패 시 매너 차감(0 이상 숫자, 보통 0.5~3.0)
            - mannerGain: 성공 시 매너 가산(0 이상 숫자, 보통 0.5~3.0)

            출력 JSON 예:
            {"templateId":2,"params":{"distance_km":5},"participationType":"SOLO","repeatDays":["MON","TUE","WED","THU","FRI"],"durationDays":14,"mannerDeduction":1.0,"mannerGain":1.0}
            """.formatted(title, description == null ? "" : description, list);
    }

    /** 통합 응답(날것). 루틴 매칭분 + 설정분을 한 JSON 으로 받는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Draft(
            Long templateId,
            Map<String, Object> params,
            String participationType,
            List<String> repeatDays,
            Integer durationDays,
            BigDecimal mannerDeduction,
            BigDecimal mannerGain
    ) {}
}
