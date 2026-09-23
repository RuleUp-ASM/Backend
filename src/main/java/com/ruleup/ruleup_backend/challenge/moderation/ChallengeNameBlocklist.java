package com.ruleup.ruleup_backend.challenge.moderation;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * 명백한 비속어 규칙 기반 blocklist — 비동기 심사의 LLM 사전 필터.
 *  - 명백한 욕설은 LLM 비용 없이 즉시 REJECTED 판정(생성·수정 자체는 막지 않는다 — 심사는 항상 사후).
 *  - 어디까지나 "명백한 것"만. 애매한 건 LLM 심사에 맡긴다(오탐 최소화).
 *
 * MVP 최소 사전. 운영하며 단어를 보강하되, 우회(공백/특수문자 삽입)는 정규화로 일부 흡수한다.
 */
@Component
public class ChallengeNameBlocklist {

    // 명백한 욕설/혐오 표현만(부분 문자열 매칭). 보수적으로 유지.
    private static final List<String> BANNED = List.of(
            "씨발", "시발", "씨바", "병신", "ㅄ", "ㅂㅅ", "지랄", "개새끼", "좆", "fuck", "shit", "asshole"
    );

    /** 명백한 금칙어 포함 여부 — 비동기 심사의 LLM 사전 필터(즉시 REJECTED). */
    public boolean hits(String text) {
        if (text == null) return false;
        String normalized = normalize(text);
        for (String bad : BANNED) {
            if (normalized.contains(normalize(bad))) return true;
        }
        return false;
    }

    /** 소문자화 + 공백/구두점 제거 + 유니코드 정규화로 단순 우회를 일부 흡수. */
    private String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[\\s\\p{Punct}]", "");
    }
}
