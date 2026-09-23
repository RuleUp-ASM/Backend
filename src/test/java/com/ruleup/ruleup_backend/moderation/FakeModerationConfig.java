package com.ruleup.ruleup_backend.moderation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.Set;

/**
 * 테스트 전용 모더레이션 fake — 실제 LLM(Gemini/Bedrock)을 호출하지 않는다.
 *
 * <p>실 클라이언트는 실패해도 {@code UNAVAILABLE} 로 폴백하므로 테스트가 "통과는" 하지만,
 * 그 과정에서 외부 429/403 응답을 기다리며 CI가 느려지고 비용·불안정이 생긴다.
 * 여기서는 결정적으로 판정한다.
 *  · 금칙어(아래 목록) 포함 → REJECTED — 거절 경로(임시 닉네임 노출·알림) 검증용
 *  · 그 외 → APPROVED
 * 보류(UNAVAILABLE) 경로가 필요한 테스트는 이 빈을 @MockitoBean 으로 덮어쓰면 된다.
 */
@Profile("test")
@Configuration
public class FakeModerationConfig {

    /** 테스트에서 "거절"을 유도할 때 쓰는 표식. */
    public static final String REJECT_MARKER = "비속어";

    private static final Set<String> BANNED = Set.of(REJECT_MARKER, "욕설", "fuck");

    @Bean
    @Primary
    public ContentModerationClient fakeModerationClient() {
        return new ContentModerationClient() {

            @Override
            public ModerationResult moderateNickname(String nickname) {
                if (nickname == null || nickname.isBlank()) return ModerationResult.APPROVED;
                String lower = nickname.toLowerCase();
                return BANNED.stream().anyMatch(lower::contains)
                        ? ModerationResult.REJECTED : ModerationResult.APPROVED;
            }

            @Override
            public ModerationResult moderateImage(String imageUrl) {
                if (imageUrl == null || imageUrl.isBlank()) return ModerationResult.APPROVED;
                return imageUrl.contains(REJECT_MARKER) || imageUrl.contains("reject")
                        ? ModerationResult.REJECTED : ModerationResult.APPROVED;
            }

            @Override
            public ModerationResult moderateImageBytes(byte[] bytes, String mimeType) {
                return (bytes == null || bytes.length == 0)
                        ? ModerationResult.UNAVAILABLE : ModerationResult.APPROVED;
            }

            @Override
            public TextVerdicts moderateChallengeText(String title, String description) {
                return new TextVerdicts(verdictOf(title), verdictOf(description));
            }

            private ModerationResult verdictOf(String text) {
                if (text == null || text.isBlank()) return ModerationResult.APPROVED;
                String lower = text.toLowerCase();
                return BANNED.stream().anyMatch(lower::contains)
                        ? ModerationResult.REJECTED : ModerationResult.APPROVED;
            }
        };
    }
}
