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
import com.ruleup.ruleup_backend.verification.domain.Appeal;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.AppealRepository;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final AppealRepository appealRepo;

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

    /**
     * 그날의 루틴별 상태 + 이의 진입 가능 여부.
     *
     * <p>원천이 둘이다. {@link VerificationDaily} 는 인증 건 ID 와 이의 기한을 들고 있어 이의 버튼을
     * 그릴 수 있는 유일한 원천이고, {@link RoutineOutcome} 은 방이 하드 삭제된 뒤에도 남는 내구성
     * 스냅샷이다. 그래서 <b>인증 건을 먼저 깔고, 그 방에 인증 건이 없을 때만 스냅샷으로 메운다</b> —
     * 삭제된 방의 과거 기록은 상태만 보이고 이의는 걸 수 없다(대상 인증이 사라졌으므로 정상이다).
     */
    public CalendarDayResponse day(UUID userId, String date) {
        LocalDate d = parseDate(date);
        Instant now = Instant.now();

        List<VerificationDaily> dailies = dailyRepo.findByUserIdAndTargetDate(userId, d).stream()
                .filter(vd -> vd.getStatus() != VerificationStatus.NOT_TARGET)
                .toList();
        Set<UUID> covered = dailies.stream().map(VerificationDaily::getChallengeId)
                .collect(Collectors.toSet());
        List<RoutineOutcome> orphans = outcomeRepo.findByUserIdAndTargetDate(userId, d).stream()
                .filter(o -> !covered.contains(o.getChallengeId()))
                .toList();

        Map<UUID, Challenge> chs = titles(Stream.concat(
                dailies.stream().map(VerificationDaily::getChallengeId),
                orphans.stream().map(RoutineOutcome::getChallengeId)).distinct().toList());
        Set<UUID> appealed = appealRepo.findByUserIdOrderByAcceptedAtDesc(userId).stream()
                .map(Appeal::getVerificationDailyId).collect(Collectors.toSet());

        List<CalendarDayResponse.Item> items = new ArrayList<>();
        for (VerificationDaily vd : dailies) {
            Challenge c = chs.get(vd.getChallengeId());
            String status = displayStatus(vd.getStatus(), d);
            items.add(new CalendarDayResponse.Item(
                    vd.getChallengeId().toString(), c != null ? c.getTitle() : null,
                    c != null ? c.getCategory() : null,
                    vd.getId().toString(), status,
                    vd.getVerifiedVia() != null ? vd.getVerifiedVia().name() : null,
                    str(vd.getVerifiedAt()), vd.getFailureReason(),
                    appeal(status, vd, appealed.contains(vd.getId()), now)));
        }
        for (RoutineOutcome o : orphans) {
            Challenge c = chs.get(o.getChallengeId());
            items.add(new CalendarDayResponse.Item(
                    o.getChallengeId().toString(), c != null ? c.getTitle() : null, o.getCategory(),
                    null, displayStatus(o.getStatus(), d),
                    o.getVerifiedVia() != null ? o.getVerifiedVia().name() : null,
                    str(o.getConfirmedAt()), o.getFailureReason(), null));
        }
        return new CalendarDayResponse(d.toString(), items);
    }

    /**
     * 저장 상태 + 귀속일 → 화면 상태. 상태값은 <b>진행중 · 실패 예정 · 완료 · 실패</b> 4종이다.
     *
     * <p>인증 모듈의 TodayStatusView 를 쓰지 않는 이유가 둘 있다. 하나는 그쪽이 아직 구 {@code CHECKING}
     * 을 내린다는 것이고(2026-08-28 폐기), 다른 하나는 실패 예정 판정에 방마다 다른 polarity 가
     * 필요해 월 캘린더에서 방 설정을 전부 읽어야 한다는 것이다. 일자 단위 화면에서는 <b>귀속일이
     * 끝났는데 아직 확정되지 않았다</b>는 사실만으로 실패 예정이 성립한다 — 유예 하루가 정확히 그 구간이다.
     */
    private String displayStatus(VerificationStatus stored, LocalDate targetDate) {
        return switch (stored) {
            case SUCCESS -> "DONE";
            case FAILED -> "FAILED";
            case NOT_TARGET, NOT_REQUIRED -> "NOT_TARGET";
            case PENDING -> targetDate.isBefore(LocalDate.now(KST)) ? "FAIL_EXPECTED" : "IN_PROGRESS";
        };
    }

    /**
     * 이의 진입 가능 여부. 대상은 실패했거나 실패 예정인 건뿐이다 — 아직 채울 기회가 남은 건과
     * 이미 완료된 건은 이의 대상이 아니다.
     */
    private CalendarDayResponse.Appeal appeal(String status, VerificationDaily vd,
                                              boolean alreadyAppealed, Instant now) {
        if (!"FAILED".equals(status) && !"FAIL_EXPECTED".equals(status)) return null;
        String until = str(vd.getAppealClosesAt());
        if (alreadyAppealed) return CalendarDayResponse.Appeal.closed("ALREADY_APPEALED", until);
        boolean open = vd.getAppealClosesAt() != null && now.isBefore(vd.getAppealClosesAt());
        return open ? CalendarDayResponse.Appeal.open(until)
                    : CalendarDayResponse.Appeal.closed("WINDOW_CLOSED", until);
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
