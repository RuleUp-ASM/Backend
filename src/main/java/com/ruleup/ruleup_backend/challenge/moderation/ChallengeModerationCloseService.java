package com.ruleup.ruleup_backend.challenge.moderation;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeHardDeleter;
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
 * 챌린지 REJECTED 1시간 수정창 마감 배치 (§3-3 상태머신 끝단 / §8-4).
 *  - REJECTED 상태로 fixDeadline 이 지난(=1시간 내 미수정) 챌린지를 자동 삭제한다.
 *  - 자동 삭제 = 하드 삭제 + 감사 로깅(§8-4 — 소프트 딜리트 폐기). 이후 findByIdAndDeletedAtIsNull 이
 *    빈 값을 돌려 추가 수정/조회/가입이 모두 막혀 "거절 후 1시간 지나면 못 고친다" 불변식이 강제된다.
 *  - 대표 이미지(S3)는 URL로만 참조하므로 DB 행을 지워도 S3 객체는 남는다. 나중에 S3에서 추적할 수 있도록
 *    삭제 전 imageUrl 을 감사 로그로 남긴다.
 *  - 1시간 내 이미지를 고쳤다면 resubmitModeration() 으로 PENDING_REVIEW(+fixDeadline=null)가 되어
 *    이 폴링 대상에서 빠진다.
 *  - 이 시점은 시작 전·멤버 0명(방장만)이라 삭제 정책(§8 참여자 0명)과 충돌하지 않는다.
 *
 * 동시성: FOR UPDATE SKIP LOCKED 선점이라 다중 인스턴스에서도 중복 처리 없음(ShedLock 없이 DB 멱등).
 */
@Service
@RequiredArgsConstructor
public class ChallengeModerationCloseService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationCloseService.class);
    private static final int CLAIM_LIMIT = 200;

    private final ChallengeRepository challengeRepository;
    private final ChallengeHardDeleter hardDeleter;
    private final NotificationService notificationService;

    /** 1분마다: 수정창이 끝난 REJECTED 챌린지를 하드 삭제한다. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void closeExpiredRejected() {
        Instant now = Instant.now();
        List<Challenge> expired = challengeRepository.findRejectedFixWindowExpiredForUpdate(now, CLAIM_LIMIT);
        for (Challenge c : expired) {
            // 알림·감사 로그를 먼저(엔티티 필드는 하드 삭제의 em.clear() 후에도 이미 로딩되어 읽을 수 있음).
            notificationService.notify(c.getCreatorId(), NotificationType.CHALLENGE_CLOSED,
                    "챌린지가 닫혔어요",
                    "대표 이미지가 커뮤니티 기준에 맞지 않아 1시간 안에 수정되지 않은 챌린지가 닫혔습니다. "
                            + "새로 만들 때는 이미지를 다시 확인해주세요.");
            // 대표 이미지(S3)는 DB와 무관하게 남으므로 imageUrl 을 감사 로그로 보존한다.
            log.warn("[AUDIT] 모더레이션 마감 하드 삭제 challengeId={} ownerId={} imageUrl={}",
                    c.getId(), c.getCreatorId(), c.getImageUrl());
            hardDeleter.hardDelete(c.getId());
        }
        if (!expired.isEmpty()) {
            log.info("모더레이션 수정창 마감으로 하드 삭제한 챌린지 {}건", expired.size());
        }
    }
}
