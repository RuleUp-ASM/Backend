package com.ruleup.ruleup_backend.notification.terms;

import com.ruleup.ruleup_backend.agreement.UserAgreementStateRepository;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 약관 개정 고지 — 저장 버전이 현행과 어긋난 사람에게 재동의를 안내한다.
 *
 * <h4>대상 집합을 {@code needsReconsent} 와 정확히 맞춘다</h4>
 * 재동의 판정은 이미 동의 모듈이 소유하고 있다 — <b>필수 약관 중 저장 버전이 현행과 다른 것</b>.
 * 여기서 규칙을 새로 세우면 「알림은 왔는데 동의 화면에는 재동의 항목이 없다」(또는 그 반대)가
 * 되고, 그 어긋남은 사용자가 문의를 넣어야 드러난다. 선택 약관(마케팅·이벤트)은 개정돼도 화면을
 * 막지 않으므로 고지 대상도 아니다.
 *
 * <p>이미 동의해 둔 사람만 대상이다. 한 번도 동의한 적 없는 사람에게 「개정됐어요」는 틀린
 * 문장이고, 필수 약관 미동의는 개정과 무관하게 게이트가 이미 막고 있다.
 *
 * <h4>재발송은 멱등 키가 막는다</h4>
 * 키에 <b>현행 버전</b>을 넣는다. 매일 돌아도 같은 개정에 대해서는 한 번만 적재되고, 다음 개정이
 * 나오면 버전이 달라져 새 고지가 나간다. 발송 이력 테이블이 필요 없는 이유다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TermsUpdateNoticeBatch {

    /** 한 항목에서 한 번에 처리할 인원. 개정은 드물지만 터지면 전원이 대상이라 상한을 둔다. */
    private static final int LIMIT = 2_000;

    private final UserAgreementStateRepository stateRepository;
    private final NotificationPublisher publisher;
    private final AppProperties props;

    /** 매일 03:50 KST. 03:40 미접속 고지 다음 자리다. */
    @Scheduled(cron = "0 50 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public int notifyOutdated() {
        int total = 0;
        for (AgreementType type : AgreementType.values()) {
            // 재동의 대상은 필수 약관뿐이다(AgreementService.needsReconsent 와 같은 기준).
            if (!type.isRequired()) continue;

            String current = props.client().termsVersions().of(type);
            if (current == null || current.isBlank()) continue;   // 버전을 모르면 비교할 수 없다

            List<UserAgreementState> outdated =
                    stateRepository.findOutdated(type, current, Limit.of(LIMIT));
            if (outdated.isEmpty()) continue;

            total += publishAll(type, current, outdated);
        }
        if (total > 0) log.info("약관 개정 고지 — {}건", total);
        return total;
    }

    private int publishAll(AgreementType type, String current, List<UserAgreementState> outdated) {
        List<NotificationEvent> events = new ArrayList<>(outdated.size());
        for (UserAgreementState state : outdated) {
            events.add(NotificationEvent.of(state.getUserId(),
                    NotificationType.TERMS_UPDATED,
                    "약관이 개정됐어요",
                    "계속 이용하시려면 새 약관에 동의해주세요.",
                    // 개정마다 한 번 — 같은 버전이면 재실행해도 적재되지 않는다.
                    Map.of(NotificationParams.EVENT_KEY, type.name() + ":" + current)));
        }
        return publisher.publishAll(events).size();
    }
}
