package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.dto.CalendarDayResponse;
import com.ruleup.ruleup_backend.me.dto.CalendarMonthResponse;
import com.ruleup.ruleup_backend.verification.service.TodayStatusView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Both calendar surfaces read current judgements; recent and corrected days are never stale-cached. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class MeCalendarService {
    private final MeJudgementQuery judgements;
    public CalendarMonthResponse month(UUID userId,String month) {
        YearMonth ym;
        try { if(month==null || !month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException(); ym=YearMonth.parse(month); }
        catch(RuntimeException invalid) { throw new BusinessException(ErrorCode.INVALID_CALENDAR_MONTH); }
        Instant now=Instant.now();
        Map<LocalDate,List<MeJudgementQuery.Row>> days=new TreeMap<>();
        judgements.between(userId,ym.atDay(1),ym.atEndOfMonth()).stream().filter(this::target)
                .forEach(row->days.computeIfAbsent(row.date(),d->new ArrayList<>()).add(row));
        return new CalendarMonthResponse(ym.toString(),days.entrySet().stream().map(entry->{
            var rows=entry.getValue();
            int success=(int)rows.stream().filter(row->row.status()==VerificationStatus.SUCCESS).count();
            boolean failed=rows.stream().anyMatch(row->row.status()==VerificationStatus.FAILED);
            boolean expected=rows.stream().anyMatch(row->"FAIL_EXPECTED".equals(display(row,now)));
            boolean pending=rows.stream().anyMatch(row->row.status()==VerificationStatus.PENDING);
            String status=failed ? success==0 ? "FAILED" : "PARTIAL" : expected ? "FAIL_EXPECTED" : pending ? "IN_PROGRESS" : "ALL_DONE";
            return new CalendarMonthResponse.Day(entry.getKey().toString(),status,success,rows.size());
        }).toList());
    }
    public CalendarDayResponse day(UUID userId,String date) {
        LocalDate day;
        try { if(date==null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException(); day=LocalDate.parse(date); }
        catch(RuntimeException invalid) { throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE); }
        Instant now=Instant.now();
        return new CalendarDayResponse(day.toString(),judgements.between(userId,day,day).stream().filter(this::target).map(row->{
            String status=display(row,now);
            return new CalendarDayResponse.Item(row.challengeId().toString(),row.title(),row.category(),
                    row.id()==null ? null : row.id().toString(),status,row.verifiedVia(),str(row.verifiedAt()),row.failureReason(),appeal(row,status,now));
        }).toList());
    }
    private boolean target(MeJudgementQuery.Row row) {
        return row.status()!=VerificationStatus.NOT_TARGET && row.status()!=VerificationStatus.NOT_REQUIRED;
    }
    private String display(MeJudgementQuery.Row row,Instant now) {
        return TodayStatusView.of(row.status(),row.date(),row.failureReason(),row.polarity(),now);
    }
    private CalendarDayResponse.Appeal appeal(MeJudgementQuery.Row row,String status,Instant now) {
        if(row.id()==null || (!"FAILED".equals(status) && !"FAIL_EXPECTED".equals(status))) return null;
        String until=str(row.appealClosesAt());
        if(row.appealed()) return CalendarDayResponse.Appeal.closed("ALREADY_APPEALED",until);
        return row.appealClosesAt()!=null && now.isBefore(row.appealClosesAt())
                ? CalendarDayResponse.Appeal.open(until) : CalendarDayResponse.Appeal.closed("WINDOW_CLOSED",until);
    }
    private String str(Instant at) { return at==null ? null : at.toString(); }
}
