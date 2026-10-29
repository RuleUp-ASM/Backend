package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.FailureEvidence;
import com.ruleup.ruleup_backend.verification.domain.FailureReasons;
import com.ruleup.ruleup_backend.verification.domain.GapReason;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationFailureDetail;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import com.ruleup.ruleup_backend.verification.repository.VerificationFailureDetailRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationMethodResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 실패(예정) 한 건의 설명 — 오늘 인증 카드와 이의 접수가 <b>같은 문장</b>을 쓰게 한 곳에 둔다.
 *
 * <p><b>확정된 실패는 판정 당시 스냅샷을, 실패 예정은 지금 신호로 계산</b>한다. 실패 예정에 상세 행을
 * 만들지 않는 이유가 여기 드러난다. 그 상태는 늦게 도착한 신호나 이의로 뒤집힐 수 있어서, 저장해 두면
 * 완료가 된 뒤에도 실패 기록이 남는다. 대신 화면에 보여줄 근거는 그때그때 계산한다 — 유저는 이 숫자를
 * 보고 이의를 낸다(공통 5-8).
 */
@Component
@RequiredArgsConstructor
public class FailureExplainer {

    private final VerificationMethodResultRepository methodResultRepo;
    private final VerificationFailureDetailRepository failureDetailRepo;

    /** 실패 설명 — 사유 코드 · 판정 불가 사유 · 한 줄 요약 · 기준값 · 실제값. */
    public record Explanation(String reasonCode, String gapReason, String summary,
                              Map<String, Object> expected, Map<String, Object> actual) {}

    public Explanation explain(VerificationDaily daily, VerificationConfig config) {
        if (daily.getStatus() == VerificationStatus.FAILED) {
            VerificationFailureDetail detail = failureDetailRepo.findById(daily.getId()).orElse(null);
            if (detail != null) {
                return new Explanation(daily.getFailureReason(), daily.getGapReason(), detail.getEvidenceSummary(),
                        detail.getExpectedValue(), detail.getActualValue());
            }
        }
        VerificationMethod method = config.primaryMethod();
        Map<String, Object> evidence = (method == null) ? null
                : methodResultRepo.findByVerificationDailyIdAndMethod(daily.getId(), method.name())
                .map(VerificationMethodResult::getEvidence).orElse(null);

        // 목표 미달은 확정 전까지 사유가 비어 있다 — 확정 배치와 같은 규칙으로 채워야
        // 같은 사건이 화면에서 두 번 다르게 설명되지 않는다.
        String pending = FailureReasons.pendingReasonOf(evidence);
        String reasonCode = (daily.getFailureReason() != null) ? daily.getFailureReason()
                : (pending != null) ? pending : FailureReasons.of(method, config);
        String gapReason = (daily.getGapReason() != null)
                ? daily.getGapReason() : GapReason.of(reasonCode);

        FailureEvidence ev = FailureEvidence.of(reasonCode, evidence);
        return new Explanation(reasonCode, gapReason, ev.summary(), ev.expected(), ev.actual());
    }
}
