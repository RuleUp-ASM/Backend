package com.ruleup.ruleup_backend.challenge.recommendation;

import com.ruleup.ruleup_backend.llm.GeminiClient;
import org.springframework.stereotype.Component;

/**
 * Gemini로 챌린지 기본 설정을 제안받는 클라이언트.
 * 실패/미설정은 전부 ChallengeSettings.empty() 로 폴백(서버가 정적 기본값 사용).
 */
@Component
public class GeminiChallengeSettingsClient implements ChallengeSettingsClient {

    private final GeminiClient gemini;

    public GeminiChallengeSettingsClient(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public ChallengeSettings suggest(String title, String description) {
        String content = gemini.generateText(buildPrompt(title, description));
        if (content == null) return ChallengeSettings.empty();
        ChallengeSettings settings = gemini.parseJson(content, ChallengeSettings.class);
        return (settings != null) ? settings : ChallengeSettings.empty();
    }

    private String buildPrompt(String title, String description) {
        return """
            너는 습관 챌린지 설정 도우미다. 사용자가 만들려는 챌린지 제목/설명을 보고
            합리적인 기본 설정을 제안하라. 반드시 JSON 으로만 답하라(설명 금지).

            제목: %s
            설명: %s

            출력 키:
            - participationType: "SOLO" 또는 "GROUP" (혼자 할 습관이면 SOLO, 같이 하는 게 자연스러우면 GROUP)
            - repeatDays: 요일 배열. ["MON","TUE","WED","THU","FRI","SAT","SUN"] 중 적절히. 매일이면 7개 전부.
            - durationDays: 챌린지 기간(일). 보통 7~30.
            - mannerDeduction: 실패 시 매너 차감(0 이상 숫자, 보통 0.5~3.0)
            - mannerGain: 성공 시 매너 가산(0 이상 숫자, 보통 0.5~3.0)
            - anonymity: "REAL" 또는 "ANONYMOUS"

            출력 JSON 예: {"participationType":"SOLO","repeatDays":["MON","TUE","WED","THU","FRI"],"durationDays":14,"mannerDeduction":1.0,"mannerGain":1.0,"anonymity":"REAL"}
            """.formatted(title, description == null ? "" : description);
    }
}
