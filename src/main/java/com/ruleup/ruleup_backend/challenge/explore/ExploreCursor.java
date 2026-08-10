package com.ruleup.ruleup_backend.challenge.explore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 목록 커서 (백엔드 테크스펙 §11-3). 정렬값 + challengeId 를 JSON 으로 만든 뒤 Base64 로 감싼다.
 *
 * <p><b>Base64 는 보안 장치가 아니다.</b> 그래서 디코딩 후 버전·정렬 종류·타입·UUID 를 전부 검증하고
 * 하나라도 어긋나면 {@code CURSOR_INVALID} 로 돌려보낸다. 특히 정렬이 바뀐 커서를 그대로 받으면
 * 기준이 다른 값끼리 비교돼 페이지가 중복·누락된다 — 그래서 커서에 sort 를 새겨 둔다.
 *
 * @param primary   1차 정렬값(문자열로 보관 — 숫자·날짜·NULL 을 한 형태로 다루기 위해)
 * @param secondary 2차 정렬값(POPULAR 의 마지막 참여 시각). 없으면 null
 */
public record ExploreCursor(ExploreSort sort, String primary, String secondary, UUID id) {

    private static final int VERSION = 1;
    private static final ObjectMapper OM = new ObjectMapper();

    public String encode() {
        ObjectNode node = OM.createObjectNode();
        node.put("v", VERSION);
        node.put("sort", sort.name());
        node.put("p", primary);
        node.put("s", secondary);
        node.put("id", id.toString());
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(OM.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new IllegalStateException("커서 직렬화 실패", e);
        }
    }

    /** @param expected 이번 요청의 정렬 — 커서에 새겨진 값과 다르면 거부한다 */
    public static ExploreCursor decode(String raw, ExploreSort expected) {
        if (raw == null || raw.isBlank()) return null;
        try {
            var node = OM.readTree(Base64.getUrlDecoder().decode(raw));
            if (node.path("v").asInt() != VERSION) throw new IllegalArgumentException("version");
            ExploreSort sort = ExploreSort.valueOf(node.path("sort").asText());
            if (sort != expected) throw new IllegalArgumentException("sort mismatch");
            UUID id = UUID.fromString(node.path("id").asText());
            String primary = node.path("p").isNull() ? null : node.path("p").asText();
            String secondary = node.path("s").isNull() ? null : node.path("s").asText();
            return new ExploreCursor(sort, primary, secondary, id);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
    }
}
