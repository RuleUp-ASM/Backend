package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.dto.MeStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** Four policy metrics, derived from the same current judgements as the calendar. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class MeStatsService {
    private final MeJudgementQuery judgements;
    private final MeChallengeCounts counts;
    public MeStatsResponse stats(UUID userId) {
        long success=0,failed=0;
        Map<LocalDate,int[]> days=new TreeMap<>();
        for (var row:judgements.between(userId,null,null)) {
            var status=row.status();
            if (status==VerificationStatus.NOT_TARGET || status==VerificationStatus.NOT_REQUIRED) continue;
            int[] day=days.computeIfAbsent(row.date(),date->new int[3]);
            if(status==VerificationStatus.SUCCESS) { success++;day[0]++; }
            else if(status==VerificationStatus.FAILED) { failed++;day[1]++; }
            else day[2]++;
        }
        int run=0,best=0;
        for(int[] day:days.values()) {
            if(day[1]>0) run=0;
            else if(day[2]==0 && day[0]>0) { run++;best=Math.max(best,run); }
            // A pending day neither extends nor breaks a streak.
        }
        Double rate=success+failed==0 ? null : Math.round(1000.0*success/(success+failed))/1000.0;
        return new MeStatsResponse(rate,success,new MeStatsResponse.Streak(run,best),counts.completedSuccessfully(userId));
    }
}
