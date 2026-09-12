package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationMute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationMuteRepository
        extends JpaRepository<NotificationMute, NotificationMute.Key> {

    List<NotificationMute> findByUserIdOrderByMutedAtAsc(UUID userId);

    /** 컨슈머의 묶음 조회용. */
    List<NotificationMute> findByUserIdIn(List<UUID> userIds);

    /** 방이 끝났을 때 그 방의 음소거를 통째로 지운다 — 멤버 수가 정원(최대 50)이라 건별 삭제로 충분하다. */
    int deleteByChallengeId(UUID challengeId);

    /** 회원 탈퇴 — 계정이 사라지므로 남겨 둘 이유가 없다. */
    int deleteByUserId(UUID userId);
}
