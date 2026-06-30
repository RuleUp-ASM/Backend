package com.ruleup.ruleup_backend.challenge.moderation;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 챌린지 REJECTED 1시간 수정창 마감 배치 (CLAUDE.md §5.1 상태머신 끝단 / §8.2).
 *  - REJECTED 상태로 fixDeadline 이 지난(=1시간 내 미수정) 챌린지를 "영구 닫힘" 처리한다.
 *  - 영구 닫힘 = 소프트 삭제. 이후 loadActive 가 CHALLENGE_NOT_FOUND 를 던져 추가 수정/조회/가입이
 *    모두 막히므로, "거절 후 1시간 지나면 더 이상 못 고친다"는 불변식이 강제된다.
 *  - 1시간 내 이름/이미지를 고쳤다면 resubmitModeration() 으로 PENDING_REVIEW(+fixDeadline=null)가 되어
 *    이 폴링 대상에서 빠진다.
 *
 * 동시성: FOR UPDATE SKIP LOCKED 선점이라 다중 인스턴스에서도 중복 처리 없음(ShedLock 없이 DB 멱등).
 * 인프라(EventBridge/ShedLock)가 갖춰지면 트리거만 교체 — 처리 로직·불변식은 그대로(§8).
 */
@Service
@RequiredArgsConstructor
public class ChallengeModerationCloseService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationCloseService.class);
    private static final int CLAIM_LIMIT = 200;

    private final ChallengeRepository challengeRepository;
    private final NotificationService notificationService;

    /** 1분마다: 수정창이 끝난 REJECTED 챌린지를 닫는다. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void closeExpiredRejected() {
        Instant now = Instant.now();
        List<Challenge> expired = challengeRepository.findRejectedFixWindowExpiredForUpdate(now, CLAIM_LIMIT);
        for (Challenge c : expired) {
            c.softDelete();   // 영구 닫힘
            notificationService.notify(c.getCreatorId(), NotificationType.CHALLENGE_CLOSED,
                    "챌린지가 닫혔어요",
                    "이름이 커뮤니티 기준에 맞지 않아 1시간 안에 수정되지 않은 챌린지가 닫혔습니다. "
                            + "새로 만들 때는 이름을 다시 확인해주세요.");
        }
        if (!expired.isEmpty()) {
            log.info("모더레이션 수정창 마감으로 닫은 챌린지 {}건", expired.size());
        }
    }
}
