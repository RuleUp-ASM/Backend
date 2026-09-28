package com.ruleup.ruleup_backend.admin.stats;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 일별 서비스 지표 배치 — 매일 00:40 KST 에 최근 사흘을 다시 계산한다.
 *
 * <h4>왜 사흘인가</h4>
 * <ul>
 *   <li><b>어제(D-1)</b> — 방금 끝난 하루. 가입·참여·이의는 이 시점에 확정이다. 판정은 아직 유예 중이라
 *       잠정값({@code judgement_final = 0})으로 들어간다.</li>
 *   <li><b>그저께(D-2)</b> — 00:00 KST 에 판정이 확정됐다. 확정 배치가 1분 주기로 비우므로 40분 뒤면
 *       대부분 끝나 있고, 여기서 판정 칸이 확정값으로 바뀐다.</li>
 *   <li><b>사흘 전(D-3)</b> — 확정 배치가 밀리거나 재시도로 늦게 확정된 건의 안전망. 이틀째 계산에
 *       {@code judgement_pending} 이 남아 있었다면 여기서 0 이 된다.</li>
 * </ul>
 * UPSERT 라 여러 번 돌아도 결과가 같다. 하루가 실패해도 나머지 날짜는 계산한다 — 다음 날 다시 돈다.
 *
 * <p>00:00 확정 배치·00:05 빈도 주기 전환과 겹치지 않게 조금 늦춰 둔다. 읽기 전용 집계라 점검 창
 * (02:00~03:00)과도 무관하지만 그 전에 끝난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyServiceStatsBatch {

    /** 자동 배치가 다시 계산하는 날 수 — 어제부터 거꾸로. */
    static final int LOOKBACK_DAYS = 3;

    private final DailyServiceStatsService statsService;
    private final Clock clock;

    @SchedulerLock(name = "DailyServiceStatsBatch.aggregateRecentDays", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Scheduled(cron = "0 40 0 * * *", zone = "Asia/Seoul")
    public void aggregateRecentDays() {
        aggregateRecentDays(clock.instant());
    }

    /** @return 계산에 성공한 날짜들(오래된 순) */
    public List<LocalDate> aggregateRecentDays(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, DailyServiceStatsService.KST);
        List<LocalDate> done = new ArrayList<>();
        for (int back = LOOKBACK_DAYS; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            try {
                statsService.recompute(day, now);
                done.add(day);
            } catch (RuntimeException e) {
                log.error("일별 서비스 지표 계산 실패 — 다음 실행이 다시 계산한다. day={}", day, e);
            }
        }
        log.info("일별 서비스 지표 집계: {}", done);
        return done;
    }
}
