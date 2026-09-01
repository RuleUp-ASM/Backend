package com.ruleup.ruleup_backend.challenge.explore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruleup.ruleup_backend.challenge.explore.store.SortKeyCodec;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

/**
 * 목록 커서. 정렬값 + challengeId + <b>조회 경로</b>를 JSON 으로 만든 뒤 Base64 로 감싼다.
 *
 * <p><b>Base64 는 보안 장치가 아니다.</b> 그래서 디코딩 후 버전·정렬 종류·조회 경로·타입·UUID 를
 * 전부 검증하고 하나라도 어긋나면 {@code CURSOR_INVALID} 로 돌려보낸다.
 *
 * <p>새기는 것이 둘인 이유가 각각 다르다.
 * <ul>
 *   <li><b>정렬</b> — 정렬이 바뀐 커서를 그대로 받으면 기준이 다른 값끼리 비교돼 페이지가 중복·누락된다.</li>
 *   <li><b>조회 경로</b> — Redis 커서는 ZSET 멤버 문자열이고 MySQL 커서는 컬럼값이다. <b>같은 필드에
 *       전혀 다른 의미가 들어간다.</b> 폴백 진입·복귀가 페이지 사이에 일어나면 서로의 커서를
 *       해석하게 되므로, 경로가 다르면 무조건 첫 페이지부터 다시 받게 한다.</li>
 * </ul>
 *
 * @param primary   MySQL 경로에서는 1차 정렬값(문자열), Redis 경로에서는 <b>ZSET 멤버 문자열 전체</b>
 * @param secondary MySQL 경로의 2차 정렬값(POPULAR 의 마지막 참여 시각). Redis 경로에서는 항상 null
 */
public record ExploreCursor(ExploreSort sort, ExploreDataSource source,
                            String primary, String secondary, UUID id) {

    private static final int VERSION = 2;
    private static final ObjectMapper OM = new ObjectMapper();

    public String encode() {
        ObjectNode node = OM.createObjectNode();
        node.put("v", VERSION);
        node.put("sort", sort.name());
        node.put("src", source.name());
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

    /**
     * @param expectedSort   이번 요청의 정렬 — 커서에 새겨진 값과 다르면 거부한다
     * @param expectedSource 이번 요청이 쓸 저장소 — 다르면 거부한다(폴백 진입·복귀 구간)
     */
    public static ExploreCursor decode(String raw, ExploreSort expectedSort,
                                       ExploreDataSource expectedSource) {
        if (raw == null || raw.isBlank()) return null;
        try {
            var node = OM.readTree(Base64.getUrlDecoder().decode(raw));
            if (!node.isObject() || !node.path("v").isIntegralNumber()
                    || node.path("v").intValue() != VERSION) {
                throw new IllegalArgumentException("version");
            }
            if (!node.path("sort").isTextual() || !node.path("src").isTextual()
                    || !node.path("id").isTextual() || !node.path("p").isTextual()) {
                throw new IllegalArgumentException("required field type");
            }
            ExploreSort sort = ExploreSort.valueOf(node.path("sort").textValue());
            if (sort != expectedSort) throw new IllegalArgumentException("sort mismatch");

            ExploreDataSource source = ExploreDataSource.valueOf(node.path("src").textValue());
            if (source != expectedSource) throw new IllegalArgumentException("source mismatch");

            UUID id = UUID.fromString(node.path("id").textValue());
            String primary = node.path("p").textValue();
            String secondary = node.path("s").isTextual() ? node.path("s").textValue() : null;
            boolean hasSecondary = node.has("s") && !node.path("s").isNull();

            if (source == ExploreDataSource.REDIS) validateRedisMember(sort, primary, id, hasSecondary);
            else validateSortValues(sort, primary, secondary, hasSecondary);

            return new ExploreCursor(sort, source, primary, secondary, id);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CURSOR_INVALID);
        }
    }

    /**
     * Redis 커서는 멤버 문자열 하나가 전부다 — 정렬값과 id 가 한 문자열에 박혀 있으므로
     * <b>거기서 꺼낸 id 가 함께 실려 온 id 와 같은지</b>만 확인하면 위조·손상이 걸린다.
     */
    private static void validateRedisMember(ExploreSort sort, String member, UUID id,
                                            boolean hasSecondary) {
        rejectSecondary(hasSecondary);
        if (!SortKeyCodec.idOf(member, sort).equals(id)) throw new IllegalArgumentException("member/id");
    }

    private static void validateSortValues(ExploreSort sort, String primary, String secondary,
                                           boolean hasNonNullSecondary) {
        if (primary == null || primary.isBlank()) throw new IllegalArgumentException("primary");
        switch (sort) {
            case POPULAR -> {
                requireNonNegativeInteger(primary);
                if (!hasNonNullSecondary || secondary == null || secondary.isBlank()) {
                    throw new IllegalArgumentException("secondary");
                }
                Timestamp.valueOf(secondary);
            }
            case PARTICIPANTS -> {
                requireNonNegativeInteger(primary);
                rejectSecondary(hasNonNullSecondary);
            }
            case COMPLETION_RATE, SUCCESS_FAIL_RATIO -> {
                BigDecimal rate = new BigDecimal(primary);
                if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("rate");
                }
                rejectSecondary(hasNonNullSecondary);
            }
            case RECENT -> {
                Timestamp.valueOf(primary);
                rejectSecondary(hasNonNullSecondary);
            }
            case DEADLINE -> {
                LocalDate.parse(primary);
                rejectSecondary(hasNonNullSecondary);
            }
        }
    }

    private static void requireNonNegativeInteger(String value) {
        if (Integer.parseInt(value) < 0) throw new IllegalArgumentException("negative");
    }

    private static void rejectSecondary(boolean hasNonNullSecondary) {
        if (hasNonNullSecondary) throw new IllegalArgumentException("unexpected secondary");
    }
}
