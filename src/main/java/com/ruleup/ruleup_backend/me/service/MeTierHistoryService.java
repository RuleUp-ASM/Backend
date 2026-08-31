package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.me.dto.MeTierHistoryResponse;
import com.ruleup.ruleup_backend.score.ScoreTransactionRepository;
import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
import com.ruleup.ruleup_backend.score.domain.TierBands;
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
import java.util.UUID;

/**
 * 티어 히스토리(GET /me/tier/history) — 월말 스냅샷 그래프.
 *
 * <p>스냅샷 테이블을 따로 두지 않고 변동 원장에서 접어 만든다. 이의가 자동 인용이라 과거 판정이
 * 수시로 뒤집히는데, 물질화한 스냅샷은 정정마다 과거 월을 되짚어 고쳐야 한다. 원장에서 파생하면
 * 정정 행이 하나 쌓이는 것으로 재계산이 끝난다 — 스펙이 요구하는 "소급 정정 시 재계산된 값으로
 * 다시 그림"이 공짜로 성립한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeTierHistoryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 보관 기간. 그 이전 이력은 삭제되어 조회되지 않는다. */
    private static final int RETENTION_MONTHS = 12;
    private static final String RETENTION_NOTE = "1년 보관";

    private final ScoreTransactionRepository transactionRepository;

    public MeTierHistoryResponse history(UUID userId, Integer months) {
        int window = window(months);
        // 보관 자체가 1년이라 창은 보관 기간을 넘을 수 없다.
        Instant since = LocalDate.now(KST).minusMonths(window).atStartOfDay(KST).toInstant();

        List<ScoreTransaction> ledger = transactionRepository.findSince(userId, since);

        return new MeTierHistoryResponse(best(ledger), monthly(ledger), RETENTION_NOTE);
    }

    private int window(Integer months) {
        if (months == null) return RETENTION_MONTHS;   // 기본 12 — 보관 자체가 1년
        if (months < 1 || months > RETENTION_MONTHS)
            throw new BusinessException(ErrorCode.INVALID_HISTORY_MONTHS);
        return months;
    }

    /**
     * 역대 최고 — 보관 범위 안에서 가장 높았던 잔액. 같은 점수가 여러 번이면 <b>처음</b> 도달한 날을
     * 준다(달성일이라는 말에 맞다). 원장이 오래된 순이라 부등호를 엄격히 쓰면 그렇게 된다.
     */
    private MeTierHistoryResponse.Best best(List<ScoreTransaction> ledger) {
        ScoreTransaction peak = null;
        for (ScoreTransaction t : ledger)
            if (peak == null || t.getBalanceAfter() > peak.getBalanceAfter()) peak = t;
        if (peak == null) return null;
        return new MeTierHistoryResponse.Best(
                TierBands.of(peak.getBalanceAfter()).name(), peak.getBalanceAfter(),
                LocalDate.ofInstant(peak.getCreatedAt(), KST).toString());
    }

    /** 월말 스냅샷 — 그 달 마지막 변동의 잔액. 변동이 없던 달은 점이 없다(그래프가 직선으로 잇는다). */
    private List<MeTierHistoryResponse.Monthly> monthly(List<ScoreTransaction> ledger) {
        Map<YearMonth, Integer> lastOfMonth = new LinkedHashMap<>();
        for (ScoreTransaction t : ledger) {
            YearMonth ym = YearMonth.from(LocalDate.ofInstant(t.getCreatedAt(), KST));
            lastOfMonth.put(ym, t.getBalanceAfter());   // 오래된 순이라 마지막 put 이 월말 값이다
        }
        List<MeTierHistoryResponse.Monthly> out = new ArrayList<>(lastOfMonth.size());
        lastOfMonth.forEach((ym, score) -> out.add(new MeTierHistoryResponse.Monthly(
                ym.toString(), TierBands.of(score).name(), score)));
        return out;
    }
}
