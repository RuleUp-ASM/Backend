package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.Appeal;
import com.ruleup.ruleup_backend.verification.repository.AppealRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 이의 남용 이상탐지 입력 기록 (인증 정책 §5.3).
 *
 * <p>인용이 <b>커밋된 뒤</b>에만 돈다. 이상탐지 결과는 이미 인용된 개별 결과를 지연시키지도 뒤집지도 않는다 —
 * 조치가 필요하면 운영자 직권 제재 정책을 따른다. 그래서 여기서는 판단하지 않고 관측값만 남긴다.
 *
 * <p>남기는 값: 최근 30일 이의 건수, 짧은 기간(24시간) 반복 건수, 동일 사유 반복 건수, 동일 이미지 반복 건수.
 * 임계값 판정과 운영자 알림은 실데이터 관측 후 확정할 후속 작업이라 지금은 로그로만 적재한다.
 */
@Component
@RequiredArgsConstructor
public class AppealAbuseMonitor {

    private static final Logger log = LoggerFactory.getLogger(AppealAbuseMonitor.class);

    private static final Duration LOOKBACK = Duration.ofDays(30);
    private static final Duration BURST = Duration.ofHours(24);

    private final AppealRepository appealRepo;

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onAppealAccepted(AppealService.AppealAccepted event) {
        List<Appeal> recent = appealRepo.findByUserIdAndAcceptedAtGreaterThanEqualOrderByAcceptedAtDesc(
                event.userId(), event.acceptedAt().minus(LOOKBACK));
        Appeal current = recent.stream()
                .filter(a -> a.getId().equals(event.appealId())).findFirst().orElse(null);
        if (current == null) return;

        Instant burstFrom = event.acceptedAt().minus(BURST);
        long inBurst = recent.stream().filter(a -> !a.getAcceptedAt().isBefore(burstFrom)).count();
        long sameReason = recent.stream()
                .filter(a -> Objects.equals(a.getReason(), current.getReason())).count();
        long sameImage = (current.getImageUrl() == null) ? 0 : recent.stream()
                .filter(a -> Objects.equals(a.getImageUrl(), current.getImageUrl())).count();

        log.info("appeal_result accepted=true userId={} challengeId={} targetDate={} " +
                        "recent30d={} within24h={} sameReason={} sameImage={}",
                event.userId(), event.challengeId(), event.targetDate(),
                recent.size(), inBurst, sameReason, sameImage);
    }
}
