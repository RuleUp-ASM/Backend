package com.ruleup.ruleup_backend.llm;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 프롬프트를 classpath(resources/prompts/*.txt)에서 읽어 캐싱하는 저장소.
 *
 * 프롬프트를 Java 텍스트 블록에 박지 않고 파일로 뺀 이유:
 *  - 프롬프트는 코드보다 훨씬 자주 튜닝된다 → diff 가 깨끗하다.
 *  - String.format 용 % 이스케이프 사고를 피한다({@code {{name}}} 치환 방식 사용).
 *  - v1/v2 를 나란히 두고 A/B 하기 쉽다.
 *
 * 입력 치환은 {@code {{name}}} 자리표시자를 문자열로 바꾸는 방식이라 % 문자에 안전하다.
 * 파일은 시작 시 한 번 읽어 캐싱한다(@PostConstruct). 없으면 기동 실패(fail-fast).
 */
@Component
public class PromptLibrary {

    private static final Logger log = LoggerFactory.getLogger(PromptLibrary.class);
    private static final String BASE = "prompts/";

    /** 시작 시 미리 로드해 존재를 검증할 프롬프트 이름들. */
    private static final List<String> KNOWN = List.of("challenge-draft", "routine-match");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void preload() {
        for (String name : KNOWN) {
            cache.put(name, read(name));
        }
        log.info("프롬프트 {}개 로드 완료: {}", cache.size(), KNOWN);
    }

    /** {@code {{key}}} 자리표시자를 vars 값으로 치환해 완성된 프롬프트를 돌려준다. */
    public String render(String name, Map<String, String> vars) {
        String tpl = cache.computeIfAbsent(name, this::read);  // 미등록 이름도 안전하게 lazy 로드
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String v = (e.getValue() != null) ? e.getValue() : "";
            tpl = tpl.replace("{{" + e.getKey() + "}}", v);
        }
        return tpl;
    }

    private String read(String name) {
        ClassPathResource res = new ClassPathResource(BASE + name + ".txt");
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 로드 실패: " + name, e);
        }
    }
}
