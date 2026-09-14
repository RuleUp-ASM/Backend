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
import java.time.LocalDate;
import java.util.UUID;
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
 *
 * <p><b>아웃박스가 불러 준다</b>({@link AppealAbuseOutboxHandler}). 예전에는
 * {@code @TransactionalEventListener} 로 커밋 직후 같은 스레드에서 실행해 30일치 조회가 이의
 * 응답을 붙잡았고, 비동기로 바꾼 뒤에도 <b>프로세스가 내려가면 유실</b>되고 지연·적체를 밖에서
 * 볼 수 없었다. 아웃박스에 실으면 유실·지연·적체가 모두 기존 게이지로 답해진다.
 */
@Component
@RequiredArgsConstructor
public class AppealAbuseMonitor {

    private static final Logger log = LoggerFactory.getLogger(AppealAbuseMonitor.class);

    private static final Duration LOOKBACK = Duration.ofDays(30);
    private static final Duration BURST = Duration.ofHours(24);

    private final AppealRepository appealRepo;
    private final VerificationMetrics metrics;

    /**
     * 한 건 집계. <b>아웃박스 핸들러가 부른다</b> — 예외를 삼키지 않는다(재시도가 붙는다).
     */
    @Transactional(readOnly = true)
    public void sample(UUID appealId, UUID userId, UUID challengeId,
                       LocalDate targetDate, Instant acceptedAt) {
        List<Appeal> recent = appealRepo.findByUserIdAndAcceptedAtGreaterThanEqualOrderByAcceptedAtDesc(
                userId, acceptedAt.minus(LOOKBACK));
        Appeal current = recent.stream()
                .filter(a -> a.getId().equals(appealId)).findFirst().orElse(null);
        if (current == null) return;

        Instant burstFrom = acceptedAt.minus(BURST);
        long inBurst = recent.stream().filter(a -> !a.getAcceptedAt().isBefore(burstFrom)).count();
        long sameReason = recent.stream()
                .filter(a -> Objects.equals(a.getReason(), current.getReason())).count();
        long sameImage = (current.getImageUrl() == null) ? 0 : recent.stream()
                .filter(a -> Objects.equals(a.getImageUrl(), current.getImageUrl())).count();

        log.info("appeal_result accepted=true userId={} challengeId={} targetDate={} " +
                        "recent30d={} within24h={} sameReason={} sameImage={}",
                userId, challengeId, targetDate, recent.size(), inBurst, sameReason, sameImage);
        metrics.appealAbuseSampled();
    }
}
