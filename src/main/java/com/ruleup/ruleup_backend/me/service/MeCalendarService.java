package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.dto.CalendarDayResponse;
import com.ruleup.ruleup_backend.me.dto.CalendarMonthResponse;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 활동 캘린더(마이프로필 §6.3). 과거일은 RoutineOutcome(내구성 원천), 당일은 VerificationDaily(PENDING·지연분 보강).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeCalendarService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineOutcomeRepository outcomeRepo;
    private final VerificationDailyRepository dailyRepo;
    private final ChallengeRepository challengeRepository;

    // ===== 월 캘린더 =====
    public CalendarMonthResponse month(UUID userId, String month) {
        YearMonth ym = parseMonth(month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        LocalDate today = LocalDate.now(KST);

        // date -> [successCount, targetCount]
        Map<LocalDate, int[]> agg = new TreeMap<>();
        java.util.Set<LocalDate> pendingDays = new java.util.HashSet<>();

        // 과거일(오늘 이전): RoutineOutcome
        LocalDate pastTo = monthEnd.isBefore(today) ? monthEnd : today.minusDays(1);
        if (!pastTo.isBefore(monthStart)) {
            for (RoutineOutcome o : outcomeRepo.findByUserIdAndTargetDateBetween(userId, monthStart, pastTo)) {
                int[] a = agg.computeIfAbsent(o.getTargetDate(), k -> new int[2]);
                a[1]++;
                if (o.getStatus() == VerificationStatus.SUCCESS) a[0]++;
            }
        }
        // 당일: VerificationDaily(확정 지연·PENDING 보강)
        if (!today.isBefore(monthStart) && !today.isAfter(monthEnd)) {
            for (VerificationDaily d : dailyRepo.findByUserIdAndTargetDate(userId, today)) {
                VerificationStatus s = d.getStatus();
                if (s == VerificationStatus.NOT_TARGET || s == VerificationStatus.NOT_REQUIRED) continue;
                int[] a = agg.computeIfAbsent(today, k -> new int[2]);
                a[1]++;
                if (s == VerificationStatus.SUCCESS) a[0]++;
                else if (s == VerificationStatus.PENDING)
                    pendingDays.add(today);   // 미확정 → 그날은 진행 중
            }
        }

        List<CalendarMonthResponse.Day> days = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> e : agg.entrySet()) {
            int success = e.getValue()[0], target = e.getValue()[1];
            if (target == 0) continue;
            days.add(new CalendarMonthResponse.Day(
                    e.getKey().toString(), dayStatus(success, target, pendingDays.contains(e.getKey())),
                    success, target));
        }
        return new CalendarMonthResponse(ym.toString(), days);
    }

    private String dayStatus(int success, int target, boolean hasPending) {
        if (hasPending) return "PENDING";
        if (success == target) return "ALL_DONE";
        if (success == 0) return "FAILED";
        return "PARTIAL";
    }

    // ===== 일자 상세 =====
    public CalendarDayResponse day(UUID userId, String date) {
        LocalDate d = parseDate(date);
        LocalDate today = LocalDate.now(KST);
        List<CalendarDayResponse.Item> items = new ArrayList<>();

        if (d.isBefore(today)) {
            // 과거: RoutineOutcome(카테고리 스냅샷 보존 — 챌린지 삭제돼도 유지)
            List<RoutineOutcome> outcomes = outcomeRepo.findByUserIdAndTargetDate(userId, d);
            Map<UUID, Challenge> titles = titles(outcomes.stream().map(RoutineOutcome::getChallengeId).toList());
            for (RoutineOutcome o : outcomes) {
                Challenge c = titles.get(o.getChallengeId());
                items.add(new CalendarDayResponse.Item(
                        o.getChallengeId().toString(), c != null ? c.getTitle() : null, o.getCategory(),
                        o.getStatus().name(),
                        o.getVerifiedVia() != null ? o.getVerifiedVia().name() : null,
                        str(o.getConfirmedAt()), o.getFailureReason()));
            }
        } else {
            // 당일/미래: VerificationDaily(당일 실시간 상태). 미래는 대개 비어 있음.
            List<VerificationDaily> dailies = dailyRepo.findByUserIdAndTargetDate(userId, d);
            Map<UUID, Challenge> chs = titles(dailies.stream().map(VerificationDaily::getChallengeId).toList());
            for (VerificationDaily vd : dailies) {
                if (vd.getStatus() == VerificationStatus.NOT_TARGET) continue;
                Challenge c = chs.get(vd.getChallengeId());
                items.add(new CalendarDayResponse.Item(
                        vd.getChallengeId().toString(), c != null ? c.getTitle() : null,
                        c != null ? c.getCategory() : null,
                        itemStatus(vd.getStatus()),
                        vd.getVerifiedVia() != null ? vd.getVerifiedVia().name() : null,
                        str(vd.getVerifiedAt()), vd.getFailureReason()));
            }
        }
        return new CalendarDayResponse(d.toString(), items);
    }

    /** 캘린더 아이템 상태: 잠정 실패는 미확정이라 PENDING 으로 표기. */
    private String itemStatus(VerificationStatus s) {
        return s.name();
    }

    private Map<UUID, Challenge> titles(List<UUID> challengeIds) {
        if (challengeIds.isEmpty()) return Map.of();
        return challengeRepository.findAllById(challengeIds).stream()
                .collect(Collectors.toMap(Challenge::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) throw new BusinessException(ErrorCode.INVALID_CALENDAR_MONTH);
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_MONTH);
        }
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE);
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE);
        }
    }

    private String str(Instant i) { return (i != null) ? i.toString() : null; }
}
