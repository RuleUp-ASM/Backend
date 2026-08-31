package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.ReactionType;
import com.ruleup.ruleup_backend.watcher.domain.WatcherNotice;
import com.ruleup.ruleup_backend.watcher.domain.WatcherReaction;
import com.ruleup.ruleup_backend.watcher.domain.WatcherRelation;
import com.ruleup.ruleup_backend.watcher.dto.WatcherReactionDtos;
import com.ruleup.ruleup_backend.watcher.repository.WatcherNoticeRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherReactionRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 응원·놀림 — 패널티 감시자 백엔드 4-2.
 *
 * <p><b>1회 제한을 서버 카운터가 아니라 DB 제약으로 보장</b>한다. 먼저 조회해서 막는 방식은
 * 두 요청이 동시에 들어오면 둘 다 통과하므로, INSERT 를 시도하고 무결성 위반을 409 로 바꾼다.
 * 사전 조회는 흔한 경우를 싸게 거르는 최적화일 뿐 정확성의 근거가 아니다.
 */
@Service
@RequiredArgsConstructor
public class WatcherReactionService {

    private final WatcherNoticeRepository noticeRepository;
    private final WatcherReactionRepository reactionRepository;
    private final WatcherRelationRepository relationRepository;
    private final NotificationPublisher notificationPublisher;
    private final UserRepository userRepository;

    @Transactional
    public WatcherReactionDtos.Response react(UUID watcherUserId, UUID noticeId,
                                              WatcherReactionDtos.Request request) {
        ReactionType reaction = ReactionType.find(request == null ? null : request.reaction())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));

        WatcherNotice notice = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));

        // 그 통지를 받은 관계의 감시자 본인만 반응할 수 있다.
        WatcherRelation relation = relationRepository.findById(notice.getRelationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));
        if (!relation.getWatcherUserId().equals(watcherUserId))
            throw new BusinessException(ErrorCode.NOT_WATCHER);

        if (reactionRepository.existsByNoticeIdAndWatcherUserId(noticeId, watcherUserId))
            throw new BusinessException(ErrorCode.REACTION_ALREADY_SENT);

        Instant now = Instant.now();
        try {
            reactionRepository.saveAndFlush(
                    WatcherReaction.of(noticeId, watcherUserId, reaction, now));
        } catch (DataIntegrityViolationException e) {
            // 복합 PK 위반 — 경합에서 진 요청이다. 사전 조회를 통과했어도 여기서 걸린다.
            throw new BusinessException(ErrorCode.REACTION_ALREADY_SENT);
        }

        String reactorNickname = userRepository.findById(watcherUserId)
                .map(u -> u.visibleNicknameTo(null)).orElse("회원");

        // 실패 당사자 1명에게만 알린다. 감시자 닉네임은 공개한다 — 누가 보냈는지 모르면
        // 응원도 놀림도 의미가 없다.
        notificationPublisher.publish(NotificationEvent.of(
                relation.getTargetUserId(),
                NotificationType.WATCHER_REACTION,
                reaction == ReactionType.CHEER ? "응원이 도착했어요" : "놀림이 도착했어요",
                reactorNickname + "님이 반응을 보냈어요.").withActor(watcherUserId));

        return new WatcherReactionDtos.Response(
                noticeId.toString(), reaction.name(), reactorNickname, now.toString());
    }
}
