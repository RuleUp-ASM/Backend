package com.ruleup.ruleup_backend.admin.stats;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.service.AdminAuditService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 일별 서비스 지표 — 운영 콘솔의 조회·CSV·수동 재계산.
 *
 * <p>조회도 감사 로그에 남긴다(백오피스 공통 5-3). 지표에 개인정보는 없지만 「누가 언제 무엇을 봤나」는
 * 다른 조회와 같은 규칙을 따른다. 수동 재계산은 저장된 값을 덮어쓰는 조작이라 별도 action 이다.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    /** 조회·CSV 한 번에 볼 수 있는 최대 일수 — 1년. */
    static final int MAX_VIEW_DAYS = 366;

    /** 수동 재계산 한 번의 최대 일수. 하루마다 판정 표를 훑으므로 길게 잡지 않는다. */
    static final int MAX_RECOMPUTE_DAYS = 92;

    /** 기본 조회 기간 — 어제까지 30일. */
    private static final int DEFAULT_VIEW_DAYS = 30;

    private final DailyServiceStatsService statsService;
    private final AdminAuditService auditService;
    private final Clock clock;

    public AdminDtos.DailyStatsResponse list(UUID operatorId, String from, String to) {
        Range range = viewRange(from, to);
        auditService.allowed(operatorId, AdminAction.STATS_VIEW, null, null, range.toString());
        return new AdminDtos.DailyStatsResponse(range.from().toString(), range.to().toString(),
                statsService.find(range.from(), range.to()).stream().map(AdminStatsService::toItem).toList());
    }

    /** CSV 본문과 파일 이름. 운영자가 Excel 로 바로 열어도 UTF-8 로 읽히도록 BOM 을 붙인다. */
    public Csv csv(UUID operatorId, String from, String to) {
        Range range = viewRange(from, to);
        auditService.allowed(operatorId, AdminAction.STATS_EXPORT, null, null, range.toString());

        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", CSV_HEADER)).append("\r\n");
        for (DailyServiceStatsService.DailyStat s : statsService.find(range.from(), range.to())) {
            Double rate = s.successRate();
            sb.append(s.statDate()).append(',')
                    .append(s.signups()).append(',')
                    .append(s.challengesCreated()).append(',')
                    .append(s.challengeJoins()).append(',')
                    .append(s.rejoins()).append(',')
                    .append(s.participants()).append(',')
                    .append(s.returningParticipants()).append(',')
                    .append(s.verificationTargets()).append(',')
                    .append(s.verificationAttempts()).append(',')
                    .append(s.judgedSuccess()).append(',')
                    .append(s.judgedSuccessAppeal()).append(',')
                    .append(s.judgedFail()).append(',')
                    .append(s.judgedFailNoEvidence()).append(',')
                    .append(s.judgementPending()).append(',')
                    .append(s.judgementFinal()).append(',')
                    .append(rate == null ? "" : rate.toString()).append(',')
                    .append(s.appeals()).append(',')
                    .append(s.computedAt())
                    .append("\r\n");
        }
        String filename = "daily-service-stats_" + range.from() + "_" + range.to() + ".csv";
        return new Csv(filename, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 기간을 지금 다시 계산해 덮어쓴다(백필). 오늘 이후 날짜는 받지 않는다 — 끝나지 않은 하루를
     * 저장하면 다음 배치 전까지 반쪽 값이 남는다.
     */
    public AdminDtos.StatsRecomputeResponse recompute(UUID operatorId, AdminDtos.StatsRecomputeRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        LocalDate from = parse(request.from());
        LocalDate to = parse(request.to());
        if (from == null || to == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, DailyServiceStatsService.KST);
        if (to.isBefore(from) || !to.isBefore(today)
                || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RECOMPUTE_DAYS)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        // 실행 전에 남긴다 — 도중에 실패해도 「누가 무엇을 덮어쓰려 했는지」는 남아야 한다.
        auditService.allowed(operatorId, AdminAction.STATS_RECOMPUTE, null, null, from + "~" + to);

        List<AdminDtos.DailyStatItem> items = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            items.add(toItem(statsService.recompute(day, now)));
        }
        return new AdminDtos.StatsRecomputeResponse(from.toString(), to.toString(), items.size(), items);
    }

    // ===== 내부 =====

    public record Csv(String filename, byte[] body) {}

    private record Range(LocalDate from, LocalDate to) {
        @Override
        public String toString() { return from + "~" + to; }
    }

    static final List<String> CSV_HEADER = List.of(
            "stat_date", "signups", "challenges_created", "challenge_joins", "rejoins", "participants",
            "returning_participants", "verification_targets", "verification_attempts",
            "judged_success", "judged_success_appeal", "judged_fail", "judged_fail_no_evidence",
            "judgement_pending", "judgement_final", "success_rate", "appeals", "computed_at");

    /** 둘 다 없으면 어제까지 30일. 하나만 오면 나머지를 그 기준으로 채운다. */
    private Range viewRange(String rawFrom, String rawTo) {
        LocalDate yesterday = LocalDate.ofInstant(clock.instant(), DailyServiceStatsService.KST).minusDays(1);
        LocalDate to = (rawTo == null || rawTo.isBlank()) ? null : parse(rawTo);
        LocalDate from = (rawFrom == null || rawFrom.isBlank()) ? null : parse(rawFrom);
        if (to == null) to = (from == null) ? yesterday : from.plusDays(DEFAULT_VIEW_DAYS - 1L);
        if (from == null) from = to.minusDays(DEFAULT_VIEW_DAYS - 1L);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_VIEW_DAYS)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return new Range(from, to);
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static AdminDtos.DailyStatItem toItem(DailyServiceStatsService.DailyStat s) {
        return new AdminDtos.DailyStatItem(
                s.statDate().toString(), s.signups(), s.challengesCreated(), s.challengeJoins(),
                s.rejoins(), s.participants(), s.returningParticipants(), s.verificationTargets(),
                s.verificationAttempts(), s.judgedSuccess(), s.judgedSuccessAppeal(), s.judgedFail(),
                s.judgedFailNoEvidence(), s.judgementPending(), s.judgementFinal(), s.successRate(),
                s.appeals(), s.computedAt() == null ? null : s.computedAt().toString());
    }
}
