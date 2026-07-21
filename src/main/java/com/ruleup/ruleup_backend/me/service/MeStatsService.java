package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.dto.MeStatsResponse;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.reputation.ReputationSnapshotRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 기간 통계(마이프로필 §6.2). RoutineOutcome(완주율·연속일·완료수) + ReputationSnapshot(온도 변화) 조립.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeStatsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String[] KOR_DOW = {"월", "화", "수", "목", "금", "토", "일"};

    private final RoutineOutcomeRepository outcomeRepo;
    private final ReputationSnapshotRepository snapshotRepo;

    public MeStatsResponse stats(UUID userId, String period, String anchorRaw) {
        String p = (period == null || period.isBlank()) ? "MONTHLY" : period.toUpperCase();
        if (!Set.of("WEEKLY", "MONTHLY", "YEARLY").contains(p))
            throw new BusinessException(ErrorCode.INVALID_STATS_PERIOD);
        LocalDate anchor = parseAnchor(anchorRaw);

        LocalDate start, end;
        switch (p) {
            case "WEEKLY" -> { start = anchor.with(DayOfWeek.MONDAY); end = start.plusDays(6); }
            case "YEARLY" -> { start = anchor.withDayOfYear(1); end = anchor.with(TemporalAdjusters.lastDayOfYear()); }
            default -> { start = anchor.withDayOfMonth(1); end = anchor.with(TemporalAdjusters.lastDayOfMonth()); }
        }

        List<RoutineOutcome> outcomes = outcomeRepo.findByUserIdAndTargetDateBetween(userId, start, end);

        int totalSuccess = 0, totalFailed = 0;
        // 버킷별 카운트
        Map<String, int[]> buckets = new LinkedHashMap<>();
        for (String key : bucketKeys(p, start, end)) buckets.put(key, new int[2]);
        // 요일별 카운트(인사이트)
        int[][] dow = new int[7][2];
        // 챌린지별 날짜→성공여부(연속일)
        Map<UUID, TreeMap<LocalDate, Boolean>> byChallenge = new HashMap<>();

        for (RoutineOutcome o : outcomes) {
            boolean success = o.getStatus() == VerificationStatus.SUCCESS;
            boolean counted = success || o.getStatus() == VerificationStatus.FAILED;
            if (!counted) continue;
            if (success) totalSuccess++; else totalFailed++;

            int[] b = buckets.get(bucketOf(p, o.getTargetDate()));
            if (b != null) { b[1]++; if (success) b[0]++; }

            int di = o.getTargetDate().getDayOfWeek().getValue() - 1;
            dow[di][1]++; if (success) dow[di][0]++;

            byChallenge.computeIfAbsent(o.getChallengeId(), k -> new TreeMap<>())
                    .put(o.getTargetDate(), success);
        }

        int avgCompletionRate = rate(totalSuccess, totalSuccess + totalFailed);
        List<MeStatsResponse.Series> series = new ArrayList<>();
        for (Map.Entry<String, int[]> e : buckets.entrySet())
            series.add(new MeStatsResponse.Series(e.getKey(), rate(e.getValue()[0], e.getValue()[0] + e.getValue()[1])));

        return new MeStatsResponse(p, totalSuccess, avgCompletionRate,
                mannerDelta(userId, start, end), avgStreak(byChallenge), series, insight(dow));
    }

    /** 기간 내 온도 변화 = 스냅샷 일별 delta 합(스냅샷이 정직한 이력). */
    private BigDecimal mannerDelta(UUID userId, LocalDate start, LocalDate end) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ReputationSnapshot s : snapshotRepo
                .findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(userId, start, end)) {
            sum = sum.add(s.getDelta());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** 평균 연속 성공일 = 챌린지별 성공 연속 구간 길이의 평균. */
    private BigDecimal avgStreak(Map<UUID, TreeMap<LocalDate, Boolean>> byChallenge) {
        List<Integer> runs = new ArrayList<>();
        for (TreeMap<LocalDate, Boolean> days : byChallenge.values()) {
            int run = 0;
            LocalDate prev = null;
            for (Map.Entry<LocalDate, Boolean> e : days.entrySet()) {
                boolean success = e.getValue();
                boolean consecutive = prev != null && e.getKey().equals(prev.plusDays(1));
                if (success) {
                    run = (consecutive && run > 0) ? run + 1 : 1;
                } else {
                    if (run > 0) runs.add(run);
                    run = 0;
                }
                prev = e.getKey();
            }
            if (run > 0) runs.add(run);
        }
        if (runs.isEmpty()) return BigDecimal.ZERO.setScale(1);
        double avg = runs.stream().mapToInt(Integer::intValue).average().orElse(0);
        return BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP);
    }

    /** 인사이트: 완주율이 가장 낮은 요일(데이터 있는 요일만). */
    private String insight(int[][] dow) {
        int worst = -1;
        double worstRate = Double.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            if (dow[i][1] == 0) continue;
            double r = (double) dow[i][0] / dow[i][1];
            if (r < worstRate) { worstRate = r; worst = i; }
        }
        if (worst < 0) return null;
        return "%s요일에 가장 낮아요! 이 패턴을 살펴보세요".formatted(KOR_DOW[worst]);
    }

    private int rate(int success, int total) {
        if (total == 0) return 0;
        return (int) Math.round(100.0 * success / total);
    }

    // ===== 버킷 =====
    private List<String> bucketKeys(String period, LocalDate start, LocalDate end) {
        List<String> keys = new ArrayList<>();
        switch (period) {
            case "WEEKLY" -> { for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) keys.add(d.toString()); }
            case "YEARLY" -> { for (int m = 1; m <= 12; m++) keys.add(m + "월"); }
            default -> {   // MONTHLY: 주별 W1..Wn
                int weeks = (int) Math.ceil(end.getDayOfMonth() / 7.0);
                for (int w = 1; w <= weeks; w++) keys.add("W" + w);
            }
        }
        return keys;
    }

    private String bucketOf(String period, LocalDate date) {
        return switch (period) {
            case "WEEKLY" -> date.toString();
            case "YEARLY" -> date.getMonthValue() + "월";
            default -> "W" + (((date.getDayOfMonth() - 1) / 7) + 1);
        };
    }

    private LocalDate parseAnchor(String raw) {
        if (raw == null || raw.isBlank()) return LocalDate.now(KST);
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_STATS_PERIOD);
        }
    }
}
