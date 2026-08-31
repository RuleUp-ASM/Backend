package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.WatcherNotice;
import com.ruleup.ruleup_backend.watcher.domain.WatcherRelation;
import com.ruleup.ruleup_backend.watcher.repository.WatcherNoticeRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 실패 통지 발송 — 패널티 감시자 백엔드 4-2.
 *
 * <h4>두 개의 절대 가드레일</h4>
 * <ol>
 *   <li><b>PENDING 발송 0건</b> — 발송 직전에 관계 상태를 다시 확인한다</li>
 *   <li><b>이의 기간 종료 전 발송 0건</b> — 트리거가 실패 <i>확정</i> 이벤트이므로 구조적으로
 *       보장된다. 인증 모듈은 귀속일+2일 00:00 KST 이후에만 이 이벤트를 발행하고,
 *       <b>이의가 인용된 건은 애초에 오지 않는다</b></li>
 * </ol>
 *
 * <p>야간 보류·중복 제어·푸시 발송은 <b>여기서 하지 않는다</b>. 알림 모듈 소관이므로 이벤트만
 * 발행한다 — 예전에는 이 모듈이 22:00 기준으로 자체 야간 큐를 굴려 알림 정책의 21:00 과
 * 어긋나 있었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatcherNoticeService {

    private final WatcherRelationRepository relationRepository;
    private final WatcherNoticeRepository noticeRepository;
    private final NotificationPublisher notificationPublisher;
    private final ChallengeQueryService challengeQuery;
    private final RoutineCatalog routineCatalog;
    private final UserRepository userRepository;

    /**
     * 실패 확정 1건 → ACTIVE 감시자 전원에게 통지.
     *
     * <p>같은 사람이 여러 챌린지에서 감시자면 <b>챌린지 수만큼 개별 발송</b>한다. 묶어 보내면
     * 어느 방의 실패인지 알 수 없고, 통지 1건 = 반응 1회라는 제약도 흐려진다.
     */
    @Transactional
    public void onFailureConfirmed(UUID challengeId, UUID failedUserId, UUID verificationId,
                                   LocalDate targetDate, Instant confirmedAt) {
        List<WatcherRelation> targets = relationRepository.findDispatchTargets(challengeId, failedUserId);
        if (targets.isEmpty()) return;   // 감시자가 없으면 방 외부 알림 자체가 없다

        Challenge challenge = challengeQuery.findChallenge(challengeId).orElse(null);
        String challengeTitle = (challenge != null) ? challenge.publicTitle() : "챌린지";
        String routineName = routineNameOf(challenge);
        String failedNickname = userRepository.findById(failedUserId)
                .map(u -> u.visibleNicknameTo(null)).orElse("회원");

        for (WatcherRelation relation : targets) {
            // 발송 직전 재확인 — 조회와 발송 사이에 토글이 꺼졌을 수 있다.
            if (!relation.isDispatchable()) continue;
            // 이벤트가 재전송돼도 같은 건으로 두 번 나가지 않는다.
            if (noticeRepository.existsByRelationIdAndVerificationId(relation.getId(), verificationId))
                continue;

            WatcherNotice notice = noticeRepository.save(
                    WatcherNotice.sent(relation.getId(), verificationId, Instant.now()));

            // 통지에 담는 것은 3개 필드뿐이다 — 감시자는 방 멤버가 아니므로 방 상세·랭킹·
            // 멤버 진입점을 주지 않으며 템플릿 복제 진입점도 노출하지 않는다.
            notificationPublisher.publish(new NotificationEvent(
                    relation.getWatcherUserId(),
                    NotificationType.PENALTY_FAILURE_SHARED,
                    "감시 알림",
                    failedNickname + "님이 [" + challengeTitle + "]의 " + routineName
                            + " 약속을 지키지 못했어요.",
                    notice.getId().toString(),
                    null,                     // 방 음소거 대상이 아니다 — 감시자는 그 방의 멤버가 아니다
                    failedUserId,             // 감시자가 이 사람을 차단했으면 알림이 생성되지 않는다
                    null));
        }
    }

    private String routineNameOf(Challenge challenge) {
        if (challenge == null || challenge.getTemplateId() == null) return "루틴";
        return routineCatalog.findById(challenge.getTemplateId())
                .map(t -> t.getName()).orElse("루틴");
    }
}
