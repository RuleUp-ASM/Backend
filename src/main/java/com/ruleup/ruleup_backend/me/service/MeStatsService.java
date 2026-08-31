package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.CompletionPolicy;
import com.ruleup.ruleup_backend.me.dto.MeStatsResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 통계 리포트(GET /me/stats) — 정책 지표 4종.
 *
 * <p>원천은 {@link RoutineOutcome} 하나다. 이 테이블에는 <b>확정된 판정만</b> 쌓이므로
 * "확정된 건만 센다"가 조건 없이 성립하고, 이의 인용으로 뒤집힌 결과도 같은 행이 고쳐지므로
 * 통계·스트릭이 캘린더와 저절로 같은 값을 낸다(단일 원천 원칙).
 *
 * <p>사전 집계 테이블은 두지 않았다 — 현 규모에 과설계이고, 소급 정정이 잦은 도메인이라
 * 재집계가 오히려 복잡해진다(6. 이외 고려 사항). 느려지면 그때 전환한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeStatsService {

    private final RoutineOutcomeRepository outcomeRepo;
    private final ChallengeMemberRepository memberRepository;

    public MeStatsResponse stats(UUID userId) {
        List<RoutineOutcome> outcomes = outcomeRepo.findByUserId(userId);

        long success = 0, failed = 0;
        // 날짜 → [그날 성공 수, 그날 판정 수]. 스트릭은 하루 단위 판정이라 방을 가로질러 접는다.
        Map<LocalDate, int[]> byDay = new TreeMap<>();

        for (RoutineOutcome o : outcomes) {
            boolean ok = o.getStatus() == VerificationStatus.SUCCESS;
            if (!ok && o.getStatus() != VerificationStatus.FAILED) continue;   // 확정 종결만 센다
            if (ok) success++; else failed++;

            int[] day = byDay.computeIfAbsent(o.getTargetDate(), k -> new int[2]);
            day[1]++;
            if (ok) day[0]++;
        }

        return new MeStatsResponse(
                successRate(success, failed), success, streak(byDay), completedCount(userId));
    }

    /** 전체 성공률 — 방 랭킹과 동일 산식. 판정이 없으면 0(비율을 만들 수 없다). */
    private double successRate(long success, long failed) {
        long judged = success + failed;
        if (judged == 0) return 0.0;
        return Math.round(1000.0 * success / judged) / 1000.0;
    }

    /**
     * 스트릭 — 그날 예정된 판정을 전부 성공한 날만 이어진다.
     *
     * <p><b>판정이 없는 날은 건너뛴다.</b> 달력의 연속이 아니라 "판정이 있었던 날의 연속"이라서,
     * 주 3회 루틴의 쉬는 날이 스트릭을 죽이지 않는다. 그래서 날짜 간격을 보지 않고 판정이 있는
     * 날만 순서대로 훑는다.
     *
     * <p>현재 스트릭은 <b>가장 최근 판정일부터</b> 거슬러 센다. 유예 구간(확정 전 2일)의 실패 예정은
     * 아직 RoutineOutcome 에 없으므로 자연히 "아직 끊기지 않은 것"으로 취급된다.
     */
    private MeStatsResponse.Streak streak(Map<LocalDate, int[]> byDay) {
        int best = 0, run = 0, current = 0;
        boolean stillCurrent = true;

        List<LocalDate> days = List.copyOf(byDay.keySet());   // TreeMap 이라 오래된 순
        for (LocalDate d : days) {
            int[] c = byDay.get(d);
            if (c[0] == c[1]) { run++; best = Math.max(best, run); }
            else run = 0;
        }
        // 현재 스트릭은 최신 쪽에서 거슬러 올라간다.
        for (int i = days.size() - 1; i >= 0 && stillCurrent; i--) {
            int[] c = byDay.get(days.get(i));
            if (c[0] == c[1]) current++; else stillCurrent = false;
        }
        return new MeStatsResponse.Streak(current, best);
    }

    /** 완주 개수 — 완주 = 기간 중 80% 이상 성공(챌린지 탐색 정책과 같은 커트라인). */
    private long completedCount(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(ChallengeMember::getProgressRate)
                .filter(CompletionPolicy::isCompleted)
                .count();
    }
}
