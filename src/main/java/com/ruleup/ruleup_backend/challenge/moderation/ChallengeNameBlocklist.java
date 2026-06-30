package com.ruleup.ruleup_backend.challenge.moderation;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * 명백한 비속어를 동기로 차단하는 규칙 기반 blocklist (CLAUDE.md §5.1, 선택).
 *  - LLM 판정은 비동기 게이트(생성은 항상 성공)지만, 명백한 욕설은 굳이 공개 대기시키지 않고
 *    생성/수정 시점에 422 CHALLENGE_NAME_REJECTED 로 즉시 막는다.
 *  - 어디까지나 "명백한 것"만. 애매한 건 통과시키고 LLM 비동기 게이트에 맡긴다(오탐 최소화).
 *
 * MVP 최소 사전. 운영하며 단어를 보강하되, 우회(공백/특수문자 삽입)는 정규화로 일부 흡수한다.
 */
@Component
public class ChallengeNameBlocklist {

    // 명백한 욕설/혐오 표현만(부분 문자열 매칭). 보수적으로 유지.
    private static final List<String> BANNED = List.of(
            "씨발", "시발", "씨바", "병신", "ㅄ", "ㅂㅅ", "지랄", "개새끼", "좆", "fuck", "shit", "asshole"
    );

    public void validate(String title) {
        if (title == null) return;
        String normalized = normalize(title);
        for (String bad : BANNED) {
            if (normalized.contains(normalize(bad))) {
                throw new BusinessException(ErrorCode.CHALLENGE_NAME_REJECTED);
            }
        }
    }

    /** 소문자화 + 공백/구두점 제거 + 유니코드 정규화로 단순 우회를 일부 흡수. */
    private String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[\\s\\p{Punct}]", "");
    }
}
