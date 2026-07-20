package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 한 (멤버×날짜×챌린지)의 평가 입력. 오케스트레이터가 sync마다 만들어 평가기에 넘긴다.
 *  - memberId: 이 평가 대상 멤버 식별자(= geofenceId). 유저가 여러 챌린지에 참여 중이면 sync 신호에는
 *    다른 멤버(=다른 지오펜스)의 전환도 섞여 오므로, GPS 평가기가 이 값으로 자기 전환만 골라내야 한다(교차 인증 차단).
 *  - signals: 이번 sync로 들어온 신호 전부(델타). 평가기가 자기 type만 골라 씀.
 *  - priorEvidence: 직전까지 누적된 method_result.evidence(없으면 null). 신호가 델타라 prior+이번 신호 합쳐 누적.
 *  - memberAnchors: 멤버 바인딩 앵커(PER_MEMBER, v2 §5). GPS 평가의 fallback 반경 판정에 사용(없으면 빈 리스트).
 *  - memberScreenApps: 멤버 바인딩 SCREEN_TIME 대상 앱 패키지명(오늘 0시 기준 적용 세트). 비면 config.targetPackages 폴백.
 */
public record DayContext(
        LocalDate targetDate,
        ZoneId zone,
        Instant now,
        VerificationConfig config,
        List<SyncSignal> signals,
        Map<String, Object> priorEvidence,
        List<GeoAnchor> memberAnchors,
        List<String> memberScreenApps,
        String memberId
) {}
