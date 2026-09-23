package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.SignalDomain;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 개별 동의 게이트 — <b>미동의 상태의 수집은 거부한다</b>(인증 공통 5-6 · 백엔드 정합화 3절).
 *
 * <h4>게이트와 배제는 다르다</h4>
 * 신호 위생(VPN·mock·저정확도)은 <b>받아서 저장하되 판정에 안 쓰는</b> 일이다. 개별 동의는 그
 * 반대다 — 법적 근거가 없으면 <b>애초에 받아 두면 안 된다</b>. 그래서 여기서 걸린 신호는
 * 배제 사유를 새겨 저장하는 것이 아니라 적재 이전에 떨어뜨린다.
 *
 * <h4>요청 전체를 거절하지 않는다</h4>
 * 한 번의 sync 에는 위치·건강·앱 사용이 섞여 온다. 위치 동의가 없다고 403 으로 요청을 통째로
 * 반려하면 <b>동의가 필요 없는 앱 사용 인증까지 함께 멈춘다</b>. 그래서 동의가 없는 종류만
 * 떨어뜨리고, 어떤 동의가 필요한지를 응답에 실어 클라가 동의 화면으로 보내게 한다.
 *
 * <p>동의 조회는 PK 한 번이라 sync 마다 불러도 부담이 없고, 해당 종류의 신호가 아예 없으면
 * 조회하지도 않는다.
 */
@Component
@RequiredArgsConstructor
public class SignalConsentGate {

    private static final Logger log = LoggerFactory.getLogger(SignalConsentGate.class);

    /** 저장 도메인 → 그 도메인을 쓰려면 있어야 하는 개별 동의. 앱 사용(DEVICE_USAGE)은 대상이 아니다. */
    private static final Map<SignalDomain, AgreementType> REQUIRED = Map.of(
            SignalDomain.LOCATION, AgreementType.LOCATION_INFO,
            SignalDomain.HEALTH_CONNECT, AgreementType.HEALTH_INFO);

    private final AgreementService agreementService;

    /**
     * @param accepted        적재해도 되는 신호
     * @param rejectedTypes   동의가 없어 떨어뜨린 신호 타입
     * @param consentRequired 클라가 받아야 할 동의 항목 — 동의 화면으로 보내는 근거
     */
    public record Decision(List<SyncSignal> accepted, List<String> rejectedTypes,
                           List<String> consentRequired) {}

    /**
     * 이 판정 방식이 요구하는 개별 동의 — 없으면 {@code null}(앱 사용·기상은 대상이 아니다).
     *
     * <p>신호가 한 건도 오지 않아도 물을 수 있어야 한다. 동의가 없으면 그 방은 <b>앞으로도</b>
     * 자동 인증이 불가능한 상태이고, 그건 권한이 꺼진 것과 같은 사건이다 — 신호가 실려 온
     * 요청에서만 알 수 있다면 「보내지도 못하는」 사용자는 영영 고지를 못 받는다.
     */
    public static AgreementType requiredFor(VerificationMethod method) {
        for (String signalType : MethodSignalTypes.of(method)) {
            AgreementType required = SignalDomain.of(signalType).map(REQUIRED::get).orElse(null);
            if (required != null) return required;
        }
        return null;
    }

    /** 그 개별 동의가 있는지. 없으면 그 종류의 신호는 받지 않는다. */
    public boolean hasConsent(UUID userId, AgreementType type) {
        return agreementService.hasIndividualConsent(userId, type);
    }

    public Decision apply(UUID userId, List<SyncSignal> signals) {
        if (signals == null || signals.isEmpty()) return new Decision(List.of(), List.of(), List.of());

        Map<SignalDomain, Boolean> consent = new EnumMap<>(SignalDomain.class);
        List<SyncSignal> accepted = new ArrayList<>(signals.size());
        Set<String> rejectedTypes = new LinkedHashSet<>();
        Set<String> consentRequired = new LinkedHashSet<>();

        for (SyncSignal s : signals) {
            if (s == null) continue;
            Optional<SignalDomain> domain = SignalDomain.of(s.type());
            AgreementType required = domain.map(REQUIRED::get).orElse(null);
            if (required == null) { accepted.add(s); continue; }   // 개별 동의 대상이 아니다

            boolean agreed = consent.computeIfAbsent(domain.get(),
                    d -> agreementService.hasIndividualConsent(userId, required));
            if (agreed) { accepted.add(s); continue; }

            rejectedTypes.add((s.type() != null) ? s.type() : "UNKNOWN");
            consentRequired.add(required.name());
        }

        if (!consentRequired.isEmpty()) {
            // 동의 없이 올라온 민감 신호의 규모를 관측한다. 개인정보라 내용은 남기지 않는다.
            log.info("consent_gate_rejected userId={} types={} required={}",
                    userId, rejectedTypes, consentRequired);
        }
        return new Decision(accepted, List.copyOf(rejectedTypes), List.copyOf(consentRequired));
    }
}
