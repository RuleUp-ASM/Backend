package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
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
import java.util.Map;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Validates current consent and the persisted final judgement before atomically recording and publishing. */
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
    private final com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository challenges;
    private final com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository verifications;
    private final com.ruleup.ruleup_backend.report.BlockService blocks;
    private final jakarta.persistence.EntityManager em;
    private final WatcherAudit audit;
    @org.springframework.beans.factory.annotation.Value("${app.watcher.dispatch-enabled:true}")
    private boolean dispatchEnabled;


    /**
     * 실패 확정 1건 → ACTIVE 감시자 전원에게 통지.
     *
     * <p>같은 사람이 여러 챌린지에서 감시자면 <b>챌린지 수만큼 개별 발송</b>한다. 묶어 보내면
     * 어느 방의 실패인지 알 수 없고, 통지 1건 = 반응 1회라는 제약도 흐려진다.
     */
    @Transactional
    public void onFailureConfirmed(UUID challengeId, UUID failedUserId, UUID verificationId,
                                   LocalDate targetDate, Instant confirmedAt) {
        if (!dispatchEnabled) return;
        Challenge lockedChallenge = challenges.findByIdForUpdate(challengeId).orElse(null);
        if (lockedChallenge == null || lockedChallenge.getDeletedAt() != null
                || lockedChallenge.getStatus() == com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus.COMPLETED
                || lockedChallenge.getPenalties() == null || !lockedChallenge.getPenalties().watcher()) return;
        var daily = verifications.findById(verificationId).orElse(null);
        if (daily == null) return;
        em.refresh(daily, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Instant now = Instant.now();
        if (!daily.getChallengeId().equals(challengeId) || !daily.getUserId().equals(failedUserId)
                || !daily.getTargetDate().equals(targetDate)
                || daily.getStatus() != com.ruleup.ruleup_backend.common.verification.VerificationStatus.FAILED
                || com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines.finalizeAfter(daily.getTargetDate()).isAfter(now)
                || daily.getVerifiedAt() == null || daily.getVerifiedAt().isAfter(now)
                || daily.getShareableAt() == null || daily.getShareableAt().isAfter(now)
                || daily.getAppealClosesAt() == null || daily.getAppealClosesAt().isAfter(now)) return;
        List<WatcherRelation> targets = relationRepository.findDispatchTargets(challengeId, failedUserId);
        if (targets.isEmpty()) return;   // 감시자가 없으면 방 외부 알림 자체가 없다

        Challenge challenge = challengeQuery.findChallenge(challengeId).orElse(null);
        String challengeTitle = (challenge != null) ? challenge.publicTitle() : "챌린지";
        String routineName = routineNameOf(challenge);
        String routineId = routineIdOf(challenge, challengeId);
        String failedNickname = userRepository.findById(failedUserId)
                .map(u -> u.visibleNicknameTo(null)).orElse("회원");

        for (WatcherRelation relation : targets) {
            // 관계와 판정 행을 잠근 상태에서 동의·차단·수락 시각을 확인한다.
            if (!relation.isDispatchable() || relation.getAcceptedAt().isAfter(daily.getVerifiedAt())
                    || blocks.isUserBlocked(relation.getWatcherUserId(), failedUserId)) continue;
            // 이벤트가 재전송돼도 같은 건으로 두 번 나가지 않는다.
            if (noticeRepository.existsByRelationIdAndVerificationId(relation.getId(), verificationId))
                continue;

            WatcherNotice notice = noticeRepository.save(
                    WatcherNotice.sent(relation.getId(), verificationId, now));

            audit.afterCommit("WATCHER_NOTICE_SENT", relation.getId(), failedUserId, null, "SENT", relation.getConsentVersion());

            // 통지에 담는 것은 3개 필드뿐이다 — 감시자는 방 멤버가 아니므로 방 상세·랭킹·
            // 멤버 진입점을 주지 않으며 템플릿 복제 진입점도 노출하지 않는다.
            // challengeId 를 비워 둔다 — 카운터 귀속 전용 컬럼인데 감시자의 「내 챌린지」 목록에
            // 그 방이 없어 카운터가 뜰 자리가 없다(공통 #19).
            notificationPublisher.publish(NotificationEvent.of(
                    relation.getWatcherUserId(),
                    NotificationType.PENALTY_FAILURE_SHARED,
                    Map.of(NotificationParams.ACTOR_NAME, failedNickname,
                            NotificationParams.CHALLENGE_TITLE, challengeTitle,
                            NotificationParams.ROUTINE_NAME, routineName,
                            NotificationParams.EVENT_KEY, notice.getId().toString(),
                            NotificationParams.NOTICE_ID, notice.getId().toString(),
                            NotificationParams.CHALLENGE_ID, relation.getChallengeId().toString(),
                            // 키에는 이름이 아니라 id 다 — 아래 routineIdOf 참조.
                            NotificationParams.ROUTINE_ID, routineId,
                            NotificationParams.TARGET_USER_ID, failedUserId.toString())));
        }
    }

    /** 화면에 보이는 이름. 문구용이며 <b>키에는 쓰지 않는다</b>. */
    private String routineNameOf(Challenge challenge) {
        if (challenge == null || challenge.getTemplateId() == null) return "루틴";
        return routineCatalog.findById(challenge.getTemplateId())
                .map(t -> t.getName()).orElse("루틴");
    }

    /**
     * 억제 키에 들어갈 루틴 <b>식별자</b>.
     *
     * <p>여기에 이름을 넣으면 두 가지가 깨진다. 루틴 이름이 바뀌는 순간 억제 키가 달라져
     * <b>24시간 억제가 풀리고</b>, 반대로 이름이 같은 서로 다른 루틴은 같은 키로 묶인다.
     * 이름은 표시용이고 키는 식별용이라 섞으면 안 된다.
     *
     * <p>템플릿이 없는 커스텀 루틴은 챌린지 id 로 대신한다 — 억제 단위가 방 하나로 좁아질 뿐,
     * 키가 비어 발행이 통째로 막히는 것보다 낫다(연속 실패 경고와 같은 규약).
     */
    private String routineIdOf(Challenge challenge, UUID challengeId) {
        if (challenge == null || challenge.getTemplateId() == null) return challengeId.toString();
        return challenge.getTemplateId().toString();
    }
}
