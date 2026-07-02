package com.ruleup.ruleup_backend.routine.match;

import com.ruleup.ruleup_backend.llm.GeminiClient;
import com.ruleup.ruleup_backend.llm.PromptLibrary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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

    private static final String PROMPT = "routine-match";

    private final GeminiClient gemini;
    private final PromptLibrary prompts;

    public GeminiRoutineMatchClient(GeminiClient gemini, PromptLibrary prompts) {
        this.gemini = gemini;
        this.prompts = prompts;
    }

    @Override
    public RoutineMatch match(String title, String description, List<RoutineCandidate> candidates) {
        String content = gemini.generateText(buildPrompt(title, description, candidates));
        if (content == null) return RoutineMatch.none();
        RoutineMatch match = gemini.parseJson(content, RoutineMatch.class);
        return (match != null) ? match : RoutineMatch.none();
    }

    /** 후보 목록(id·이름·목표값 키)을 주고 그 중 하나만 고르게 한다(프롬프트 본문은 resources/prompts). */
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
}
