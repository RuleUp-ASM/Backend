package com.ruleup.ruleup_backend.llm;

import com.ruleup.ruleup_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ImageBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.ImageSource;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

import java.time.Duration;
import java.util.List;

/**
 * AWS Bedrock Converse API 로 Amazon Nova(Lite 등)를 호출하는 {@link LlmClient} 구현.
 * {@code app.llm.provider=bedrock} 일 때만 활성화된다(Gemini 대신 주입).
 *
 * <p>자격증명은 기본 provider 체인(ECS 태스크 역할). 리전·모델은 설정으로 주입.
 * Nova 는 Gemini 의 responseSchema/responseMimeType 강제가 없어, JSON 은 시스템 프롬프트로 유도하고
 * 파싱 폴백({@link LlmJson})으로 흡수한다. 멀티모달(이미지) 지원 → 사진 검수도 가능.
 * 실패/미설정은 예외 없이 null 반환 → 호출 측이 폴백.
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "bedrock")
public class BedrockNovaClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(BedrockNovaClient.class);

    private static final String DEFAULT_REGION = "ap-northeast-2";                // 서울
    private static final String DEFAULT_MODEL_ID = "apac.amazon.nova-lite-v1:0";  // APAC 온디맨드 크로스리전 추론 프로파일
    private static final long DEFAULT_TIMEOUT_MS = 8000L;
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 300L;
    private static final int MAX_TOKENS = 2048;

    /** Gemini 의 responseMimeType=json 대체 — Nova 가 순수 JSON만 뱉도록 강제하는 시스템 지시. */
    private static final String SYSTEM_JSON =
            "You are a JSON API. Respond with ONLY a single valid JSON object using the requested fields. "
            + "No prose, no explanation, no markdown, no code fences.";

    private final BedrockRuntimeClient client;
    private final String modelId;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final boolean configured;

    public BedrockNovaClient(AppProperties props) {
        AppProperties.Llm llm = (props != null) ? props.llm() : null;
        AppProperties.Llm.Bedrock cfg = (llm != null) ? llm.bedrock() : null;

        String region = (cfg != null && cfg.region() != null && !cfg.region().isBlank())
                ? cfg.region() : DEFAULT_REGION;
        this.modelId = (cfg != null && cfg.modelId() != null && !cfg.modelId().isBlank())
                ? cfg.modelId() : DEFAULT_MODEL_ID;
        long timeoutMs = (cfg != null && cfg.timeoutMs() > 0) ? cfg.timeoutMs() : DEFAULT_TIMEOUT_MS;
        this.maxRetries = (cfg != null && cfg.maxRetries() > 0) ? cfg.maxRetries() : DEFAULT_MAX_RETRIES;
        this.retryBackoffMs = (cfg != null && cfg.retryBackoffMs() > 0) ? cfg.retryBackoffMs() : DEFAULT_RETRY_BACKOFF_MS;

        // 자격증명은 기본 체인(ECS 역할)에서 지연 로딩 — 구성 실패로 기동이 깨지지 않게 try 로 감싼다.
        BedrockRuntimeClient built = null;
        try {
            built = BedrockRuntimeClient.builder()
                    .region(Region.of(region))
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofMillis(timeoutMs))
                            .build())
                    .build();
        } catch (Exception e) {
            log.warn("Bedrock 클라이언트 초기화 실패 — LLM 호출은 폴백 처리: {}", e.getMessage());
        }
        this.client = built;
        this.configured = built != null;
        log.info("LLM provider=bedrock model={} region={} configured={}", modelId, region, configured);
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public String generateText(String prompt) {
        return converse(prompt, null, null);
    }

    @Override
    public String generateStructured(String prompt, String responseSchemaJson) {
        // Nova 는 네이티브 스키마 강제가 없다 → 스키마는 무시하고 JSON 시스템 지시 + 파싱 폴백으로 처리.
        return converse(prompt, null, null);
    }

    @Override
    public String generateText(String prompt, byte[] image, String mimeType) {
        if (image == null || image.length == 0) return generateText(prompt);
        return converse(prompt, image, mimeType);
    }

    private String converse(String prompt, byte[] image, String mimeType) {
        if (!configured) {
            log.warn("Bedrock 미설정 — 호출을 건너뜁니다.");
            return null;
        }
        Message message = buildMessage(prompt, image, mimeType);
        int attempts = maxRetries + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ConverseResponse resp = client.converse(r -> r
                        .modelId(modelId)
                        .system(SystemContentBlock.fromText(SYSTEM_JSON))
                        .messages(message)
                        .inferenceConfig(ic -> ic.temperature(0.0f).maxTokens(MAX_TOKENS)));
                String text = firstText(resp);
                return (text == null || text.isBlank()) ? null : text;
            } catch (ThrottlingException te) {
                if (attempt < attempts) {
                    log.warn("Bedrock 쓰로틀({}/{}회) — 재시도", attempt, attempts);
                    if (!backoff(attempt)) return null;
                    continue;
                }
                log.warn("llm_fail class=QUOTA provider=bedrock msg={}", te.getMessage());
                return null;
            } catch (Exception e) {
                // 그 외 오류(자격증명·모델 미승인·전송)는 재시도 무의미 → 즉시 폴백.
                log.warn("llm_fail class=TRANSPORT provider=bedrock msg={}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    private Message buildMessage(String prompt, byte[] image, String mimeType) {
        Message.Builder b = Message.builder().role(ConversationRole.USER);
        if (image != null && image.length > 0) {
            ContentBlock imageBlock = ContentBlock.fromImage(ImageBlock.builder()
                    .format(toImageFormat(mimeType))
                    .source(ImageSource.fromBytes(SdkBytes.fromByteArray(image)))
                    .build());
            return b.content(imageBlock, ContentBlock.fromText(prompt)).build();
        }
        return b.content(ContentBlock.fromText(prompt)).build();
    }

    /** Converse 응답에서 첫 텍스트 블록. */
    private String firstText(ConverseResponse resp) {
        if (resp == null || resp.output() == null || resp.output().message() == null) return null;
        List<ContentBlock> content = resp.output().message().content();
        if (content == null) return null;
        for (ContentBlock cb : content) {
            if (cb.text() != null && !cb.text().isBlank()) return cb.text();
        }
        return null;
    }

    private ImageFormat toImageFormat(String mimeType) {
        String m = (mimeType == null) ? "" : mimeType.toLowerCase();
        if (m.contains("png"))  return ImageFormat.PNG;
        if (m.contains("webp")) return ImageFormat.WEBP;
        if (m.contains("gif"))  return ImageFormat.GIF;
        return ImageFormat.JPEG;   // 기본
    }

    private boolean backoff(int attempt) {
        long wait = retryBackoffMs * (1L << (attempt - 1));
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
