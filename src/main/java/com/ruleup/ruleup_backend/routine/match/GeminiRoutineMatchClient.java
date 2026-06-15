package com.ruleup.ruleup_backend.routine.match;

import com.ruleup.ruleup_backend.llm.GeminiClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Gemini로 "제목 → 템플릿 매칭 + 목표값 추출"을 받아오는 클라이언트.
 *
 * LLM의 책임은 작다: 후보 목록에서 templateId 하나 고르고 목표값(숫자/시간)만 뽑는다.
 * 인증 방식·권한 같은 값은 절대 LLM이 만들지 않는다(서버 템플릿이 진실).
 * 실패/미설정은 RoutineMatch.none() → 서비스가 수동 인증으로 폴백.
 */
@Component
public class GeminiRoutineMatchClient implements RoutineMatchClient {

    private final GeminiClient gemini;

    public GeminiRoutineMatchClient(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public RoutineMatch match(String title, String description, List<RoutineCandidate> candidates) {
        String content = gemini.generateText(buildPrompt(title, description, candidates));
        if (content == null) return RoutineMatch.none();
        RoutineMatch match = gemini.parseJson(content, RoutineMatch.class);
        return (match != null) ? match : RoutineMatch.none();
    }

    /** 후보 목록(id·이름·목표값 키)을 주고 그 중 하나만 고르게 한다. */
    private String buildPrompt(String title, String description, List<RoutineCandidate> candidates) {
        String list = candidates.stream()
                .map(c -> "%d. %s%s".formatted(
                        c.id(), c.name(),
                        c.paramKeys().isEmpty() ? "" : " (목표값: " + String.join(", ", c.paramKeys()) + ")"))
                .collect(Collectors.joining("\n"));

        return """
            너는 습관 루틴 매칭기다. 사용자가 입력한 제목/설명을 아래 후보 루틴 중 의미가 가장 가까운
            하나에 매칭하고, 사용자가 말한 목표 수치가 있으면 뽑아라. 반드시 JSON 으로만 답하라(설명 금지).

            제목: %s
            설명: %s

            후보 루틴(id. 이름 (목표값 키)):
            %s

            규칙:
            - templateId: 위 후보 중 가장 가까운 루틴의 id(숫자). 적절한 게 없으면 null.
            - params: 사용자가 명시한 목표값만 채운다. 키는 해당 후보의 "목표값 키"만 사용.
              숫자는 숫자로(예: 5), 시간은 "HH:mm" 문자열로(예: "07:00"). 언급 없으면 비운다({}).
            - 추측해서 새 키를 만들지 말 것. 후보에 없는 id 를 쓰지 말 것.

            출력 JSON 예: {"templateId": 2, "params": {"distance_km": 5}}
            """.formatted(title, description == null ? "" : description, list);
    }
}
