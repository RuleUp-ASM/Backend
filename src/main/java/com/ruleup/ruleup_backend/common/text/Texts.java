package com.ruleup.ruleup_backend.common.text;

/** 사용자에게 그대로 나가는 문자열을 다루는 공용 헬퍼. */
public final class Texts {

    private Texts() {}

    /**
     * 최대 {@code maxCodePoints} "글자"까지 자른다. {@code String.substring} 과 달리
     * 서로게이트 페어를 반으로 쪼개지 않는다.
     *
     * <p>이모지·일부 한자는 char 두 개(서로게이트 페어)로 저장된다. char 기준으로 자르면
     * 짝 없는 half surrogate 가 문자열 끝에 남고, 그 문자열은 UTF-8 로 인코딩할 수 없어
     * JSON 직렬화 단계에서 {@code ?} 나 U+FFFD 로 바뀐다 — 화면에는 "글자가 깨진" 것으로 보인다.
     * 한글 완성형은 char 하나라 영향이 없지만, 제목·설명·공지에는 이모지가 흔하다.
     *
     * <p>길이 기준이 코드포인트라 MySQL {@code varchar(n)}(문자 수 기준) 한도와도 어긋나지 않는다.
     */
    public static String truncate(String s, int maxCodePoints) {
        if (s == null) return null;
        if (maxCodePoints <= 0) return "";
        // char 수가 이미 한도 이하면 코드포인트 수는 그보다 작거나 같다 — 그대로 통과.
        if (s.length() <= maxCodePoints) return s;
        int keep = Math.min(maxCodePoints, s.codePointCount(0, s.length()));
        return s.substring(0, s.offsetByCodePoints(0, keep));
    }
}
