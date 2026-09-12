package com.ruleup.ruleup_backend.notification.dormancy;

import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 휴면 전환 예고 · 장기 미접속 탈퇴 예고.
 *
 * <h4>상태를 바꾸지 않는다 — 고지만 한다</h4>
 * 휴면은 상태가 아니다({@code UserStatus} 참조). {@code last_active_at} 으로 계산하고 로그인하면
 * 저절로 풀린다. 여기서도 계정을 건드리지 않고 <b>알림만</b> 낸다 — 전환·탈퇴 집행은 별도 정책이며,
 * 고지 없이 먼저 집행하는 것이 사용자에게 가장 나쁘기 때문에 고지부터 세운다.
 *
 * <h4>재발송은 멱등 키가 막는다 — 발송 이력 테이블을 두지 않는다</h4>
 * 키에 {@code last_active_at} 을 넣는다. 같은 침묵 구간에서는 값이 그대로라 매일 돌아도 한 번만
 * 적재되고, 사용자가 돌아왔다가 다시 잠잠해지면 값이 달라져 새 고지가 나간다. 별도 상태를 두면
 * 그 상태와 실제 활동 시각이 어긋나는 경로가 생긴다.
 *
 * <h4>임계값은 스펙에 없다</h4>
 * 회원 정책에 휴면·탈퇴 예고 시점이 정의돼 있지 않아 <b>여기서 정하고 프로퍼티로 뺐다</b>.
 * 기본값은 관행(휴면 1년 · 보관 2년)의 <b>한 달 전</b>이다 — 예고는 조치 전에 도착해야 의미가 있다.
 * 정책이 확정되면 값만 바꾸면 된다.
 */
@Slf4j
@Component
public class DormancyNoticeBatch {

    /** 한 번에 처리할 인원. 예고 대상은 매일 조금씩 늘어나는 성질이라 크게 잡을 이유가 없다. */
    private static final int LIMIT = 1_000;

    private final UserRepository userRepository;
    private final NotificationPublisher publisher;
    private final Duration dormancyAfter;
    private final Duration withdrawalAfter;

    public DormancyNoticeBatch(UserRepository userRepository, NotificationPublisher publisher,
                               @Value("${app.dormancy.notice-after-days:335}") int noticeAfterDays,
                               @Value("${app.dormancy.withdrawal-notice-after-days:700}")
                               int withdrawalAfterDays) {
        this.userRepository = userRepository;
        this.publisher = publisher;
        this.dormancyAfter = Duration.ofDays(noticeAfterDays);
        this.withdrawalAfter = Duration.ofDays(withdrawalAfterDays);
    }

    /**
     * 매일 03:40 KST. 02:00~03:00 점검 창과 03:10 알림 파기, 03:30 추천 수집을 피한 자리다.
     *
     * @return 적재한 고지 수
     */
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public int notifyInactive() {
        Instant now = Instant.now();
        Instant dormancyLine = now.minus(dormancyAfter);
        Instant withdrawalLine = now.minus(withdrawalAfter);

        List<User> candidates = userRepository.findInactiveSince(dormancyLine, Limit.of(LIMIT));
        if (candidates.isEmpty()) return 0;

        int dormancy = 0;
        int withdrawal = 0;
        for (User user : candidates) {
            Instant lastActive = user.getLastActiveAt();
            if (lastActive == null) continue;   // 활동 시각이 없으면 침묵 길이를 셀 수 없다

            // 탈퇴 예고 선을 넘었으면 그쪽만 보낸다. 둘 다 보내면 같은 날 두 통이 오고,
            // 사용자는 더 무거운 쪽(탈퇴)을 놓친다.
            if (lastActive.isBefore(withdrawalLine)) {
                publish(user, NotificationType.INACTIVE_WITHDRAWAL_NOTICE,
                        "오랫동안 들어오지 않으셨어요",
                        "계정이 곧 정리될 예정이에요. 계속 쓰시려면 한 번만 들어와주세요.",
                        "withdrawal");
                withdrawal++;
            } else {
                publish(user, NotificationType.DORMANCY_NOTICE,
                        "곧 휴면 계정이 돼요",
                        "한동안 활동이 없어 곧 휴면으로 전환돼요. 지금 들어오시면 그대로 유지돼요.",
                        "dormancy");
                dormancy++;
            }
        }
        if (dormancy + withdrawal > 0) {
            log.info("미접속 고지 — 휴면 예고 {}건, 탈퇴 예고 {}건", dormancy, withdrawal);
        }
        return dormancy + withdrawal;
    }

    /** 키에 {@code lastActiveAt} 을 넣어 같은 침묵 구간에서는 한 번만 적재되게 한다. */
    private void publish(User user, NotificationType type, String title, String body, String kind) {
        publisher.publish(NotificationEvent.of(user.getId(), type, title, body,
                Map.of(NotificationParams.EVENT_KEY,
                        kind + ":" + user.getLastActiveAt().toEpochMilli())));
    }
}
