package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ExploreSort;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.TemplateStats;
import com.ruleup.ruleup_backend.challenge.dto.ExploreResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.TemplateStatsRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 챌린지 둘러보기 목록(탐색 §3). 처리 순서: 공통 제외 → 필터(AND) → 정렬(역정규화 값) → 커서 페이지.
 *
 * <p>정렬 7종·null 최하위·복합 커서는 앱단에서 처리한다(질의 시점 집계 없음 — 정렬 값은 이미
 * 역정규화 컬럼/TemplateStats 에 있음). 후보 집합을 로드해 정렬·페이지네이션하므로 MVP 규모에 맞다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeExploreService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_SIZE = 5;
    private static final int MAX_SIZE = 10;
    private static final int ROOM_MIN_PARTICIPANTS = 10;      // 성공/실패 비율 표본 하한(초과해야 노출)
    private static final double ROOM_MIN_PROGRESS = 0.30;     // 성공/실패 비율 진행률 하한

    private final ChallengeRepository challengeRepository;
    private final TemplateStatsRepository templateStatsRepository;
    private final ReputationScoreRepository reputationScoreRepository;

    @Transactional(readOnly = true)
    public ExploreResponse explore(UUID userId, String category, String participationType,
                                   String verificationType, Boolean joinableOnly,
                                   String sort, String cursor, Integer size) {
        ExploreSort sortKey = ExploreSort.parse(sort)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SORT_TYPE));
        String categoryFilter = normalizeCategory(category);
        ParticipationType participationFilter = parseParticipation(participationType);
        String verificationFilter = normalizeVerification(verificationType);
        boolean applyManner = (joinableOnly == null) || joinableOnly;   // 기본 true
        int pageSize = clampSize(size);
        ExploreCursor cur = ExploreCursor.decode(cursor);               // null/손상 시 CURSOR_INVALID

        BigDecimal myTemp = reputationScoreRepository.findById(userId)
                .map(ReputationScore::getMannerTemperature)
                .orElse(ReputationScore.INITIAL_TEMPERATURE);

        LocalDate today = LocalDate.now(KST);
        List<Challenge> candidates = challengeRepository.findExploreList(
                today, categoryFilter, participationFilter, verificationFilter, applyManner, myTemp);

        // 템플릿 통계 일괄 로드(완주율·사용자 수).
        List<Long> templateIds = candidates.stream()
                .map(Challenge::getTemplateId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, TemplateStats> statsByTemplate = templateIds.isEmpty() ? Map.of()
                : templateStatsRepository.findByTemplateIdIn(templateIds).stream()
                    .collect(Collectors.toMap(TemplateStats::getTemplateId, s -> s));

        // 행 계산(정렬 값 + 표시 값).
        List<Row> rows = new ArrayList<>(candidates.size());
        for (Challenge c : candidates) {
            // templateId 가 null 이면 조회하지 않는다(Map.of()는 null 키 get 에서 NPE).
            TemplateStats stats = (c.getTemplateId() != null) ? statsByTemplate.get(c.getTemplateId()) : null;
            rows.add(buildRow(c, myTemp, today, sortKey, stats));
        }

        boolean desc = sortKey.isDescending();
        rows.sort((a, b) -> compareKey(a.sortValue, a.id(), b.sortValue, b.id(), desc));

        int totalCount = rows.size();

        // 커서 이후만 남긴다(복합 키 기준 strictly after).
        List<Row> after = (cur == null) ? rows
                : rows.stream()
                    .filter(r -> compareKey(r.sortValue, r.id(), cur.value(), cur.id(), desc) > 0)
                    .toList();

        boolean hasNext = after.size() > pageSize;
        List<Row> page = after.subList(0, Math.min(pageSize, after.size()));

        List<ExploreResponse.Item> items = page.stream().map(r -> r.item).toList();
        String nextCursor = null;
        if (hasNext && !page.isEmpty()) {
            Row last = page.get(page.size() - 1);
            nextCursor = new ExploreCursor(last.sortValue, last.id()).encode();
        }
        return new ExploreResponse(totalCount, items, nextCursor, hasNext);
    }

    // ===== 행 계산 =====
    private Row buildRow(Challenge c, BigDecimal myTemp, LocalDate today,
                         ExploreSort sortKey, TemplateStats stats) {
        int participantCount = c.getParticipantCount();

        // 주의: int/Integer 혼합 삼항은 null 브랜치를 언박싱해 NPE → 명시적 분기로 Integer 유지.
        Integer templateUsage;
        if (stats != null) templateUsage = (int) stats.getUsageCount();
        else if (c.getTemplateId() != null) templateUsage = 0;
        else templateUsage = null;
        Double completionRate = (stats != null && stats.getCompletionRate() != null)
                ? stats.getCompletionRate().doubleValue() : null;
        ExploreResponse.SuccessFailRatio successFail = successFailRatio(c, today);

        boolean joinable = c.getMinMannerTemperature() == null
                || c.getMinMannerTemperature().compareTo(myTemp) <= 0;

        // 모든 브랜치를 Double(박싱)로 통일 — 혼합 시 switch 가 double 로 언박싱해 null 브랜치에서 NPE 나는 것 방지.
        Double sortValue = switch (sortKey) {
            case TRENDING -> Double.valueOf(c.getTrendingScore());
            case TEMPLATE_USAGE -> Double.valueOf(templateUsage != null ? templateUsage : 0);
            case PARTICIPANTS -> Double.valueOf(participantCount);
            case COMPLETION_RATE -> completionRate;                                   // null → 최하위
            case SUCCESS_FAIL_RATIO -> (successFail != null) ? successFail.successRate() : null;
            case RECENT -> Double.valueOf(c.getCreatedAt().toEpochMilli());
            case DEADLINE -> Double.valueOf(c.getEndDate().toEpochDay());             // 오름차순
        };

        ExploreResponse.Item item = new ExploreResponse.Item(
                c.getId().toString(),
                c.getTemplateId() != null ? c.getTemplateId().toString() : null,
                c.getTitle(),
                c.getImageUrl(),
                c.getCategory(),
                c.getParticipationType().name(),
                c.getVerificationType(),
                c.getStatus().name(),
                c.getAnonymity().name(),
                participantCount,
                c.getMinMannerTemperature(),
                joinable,
                templateUsage,
                completionRate,
                successFail,
                c.getRepeatDays(),
                c.getDurationDays(),
                c.getStartDate().toString(),
                c.getEndDate().toString(),
                c.getCreatedAt().toString());
        return new Row(item, sortValue);
    }

    /** 방 성공/실패(§3.2.4): 참여자>10 AND 진행률≥30% 일 때만 값. 아니면 null. */
    private ExploreResponse.SuccessFailRatio successFailRatio(Challenge c, LocalDate today) {
        int participantCount = c.getParticipantCount();
        if (participantCount <= ROOM_MIN_PARTICIPANTS) return null;
        if (progress(c, today) < ROOM_MIN_PROGRESS) return null;

        int failCount = Math.min(Math.max(c.getFailCount(), 0), participantCount);
        int successCount = participantCount - failCount;
        double successRate = (participantCount > 0)
                ? BigDecimal.valueOf((double) successCount / participantCount)
                    .setScale(3, RoundingMode.HALF_UP).doubleValue() : 0.0;
        return new ExploreResponse.SuccessFailRatio(successCount, failCount, successRate);
    }

    /** 방 진행률 = 경과일 / 총일수 (시작 전이면 0). */
    private double progress(Challenge c, LocalDate today) {
        int totalDays = Math.max(1, c.getDurationDays());
        long elapsed = today.toEpochDay() - c.getStartDate().toEpochDay();
        if (elapsed <= 0) return 0.0;
        return Math.min(1.0, (double) elapsed / totalDays);
    }

    /**
     * 복합 키 비교: 정렬 값(방향 반영, null 최하위) → challengeId DESC.
     * 반환 &gt;0 이면 (aVal,aId)가 (bVal,bId)보다 뒤(정렬상 하위).
     */
    private int compareKey(Double aVal, UUID aId, Double bVal, UUID bId, boolean desc) {
        int c;
        if (aVal == null && bVal == null) c = 0;
        else if (aVal == null) c = 1;                 // null 최하위
        else if (bVal == null) c = -1;
        else {
            int cmp = Double.compare(aVal, bVal);
            c = desc ? -cmp : cmp;
        }
        if (c != 0) return c;
        return bId.compareTo(aId);                    // 동점 → challengeId 내림차순
    }

    // ===== 파라미터 검증 =====
    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String v = category.trim().toUpperCase();
        if (!InterestCategory.allValid(List.of(v))) throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        return v;
    }

    private ParticipationType parseParticipation(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ParticipationType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        }
    }

    private String normalizeVerification(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase();
        if (!v.equals("AUTO") && !v.equals("MANUAL")) throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        return v;
    }

    private int clampSize(Integer size) {
        if (size == null) return DEFAULT_SIZE;
        return Math.max(1, Math.min(MAX_SIZE, size));
    }

    private record Row(ExploreResponse.Item item, Double sortValue) {
        UUID id() { return UUID.fromString(item.challengeId()); }
    }
}
