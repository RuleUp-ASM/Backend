package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationMute;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 음소거 정리 — <b>방을 떠나면 그 방의 음소거도 사라진다</b>(음소거 API 명세).
 *
 * <p>정리 경로가 없어서 두 가지가 생겼다. 탈퇴·강퇴한 방의 음소거가 설정 목록에 <b>영영 남고</b>,
 * 나중에 같은 방에 다시 들어가면 <b>과거의 음소거가 되살아나</b> 사용자가 켠 적 없는 상태로
 * 알림이 막힌다. 등록은 참여 중인 방만 가능한데 해제만 사용자 손에 맡겨 둔 것이 원인이다.
 *
 * <h4>왜 {@link NotificationService} 가 아니라 별도 컴포넌트인가</h4>
 * 이 정리를 부르는 쪽은 챌린지 도메인(탈퇴·강퇴·종료 배치)이다. 그런데 {@code NotificationService}
 * 는 약관·사용자·설정까지 끌고 있어서, 음소거 한 줄 지우자고 그 의존 그래프를 챌린지 서비스에
 * 밀어 넣게 된다. 여기는 <b>음소거 리포지터리 하나만</b> 본다.
 *
 * <h4>실패해도 도메인을 막지 않는다</h4>
 * 호출부의 트랜잭션에 합류하므로 같은 커밋에서 사라진다. 다만 이건 부수 정리라, 정리가 안 돼도
 * 사용자는 해제 API 로 직접 지울 수 있다 — 반대로 정리 때문에 탈퇴가 실패하면 그게 더 나쁘다.
 */
@Service
@RequiredArgsConstructor
public class NotificationMuteCleaner {

    private final NotificationMuteRepository muteRepository;

    /** 한 사람이 한 방을 떠났다 — 탈퇴·강퇴. 없으면 아무 일도 하지 않는다(멱등). */
    @Transactional
    public void clearMute(UUID userId, UUID challengeId) {
        if (userId == null || challengeId == null) return;
        muteRepository.deleteById(new NotificationMute.Key(userId, challengeId));
    }

    /** 방이 끝났다 — 그 방의 음소거는 전원 분이 의미를 잃는다. */
    @Transactional
    public void clearMutesOfChallenge(UUID challengeId) {
        if (challengeId == null) return;
        muteRepository.deleteByChallengeId(challengeId);
    }

    /** 회원이 탈퇴했다 — 참여 중이던 방과 종료된 방을 가리지 않고 전부. */
    @Transactional
    public void clearMutesOfUser(UUID userId) {
        if (userId == null) return;
        muteRepository.deleteByUserId(userId);
    }
}
