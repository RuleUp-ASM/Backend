package com.ruleup.ruleup_backend.challenge.reminder;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.push.PushSender;
import com.ruleup.ruleup_backend.push.SilentPush;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 셋업 미완료(권한 없음) 멤버에게 고스트(무음) 푸시로 재설정을 유도하는 배치.
 *
 * <p>배경: 서버는 OS 권한 보유 상태를 저장하지 않는다(챌린지 스펙 §5.6). "권한 없음"은
 * 멤버 {@code setupStatus=PENDING_SETUP}(= 가입했으나 권한·앵커 셋업 전)로 드러난다. AUTO 인증
 * 챌린지의 ACTIVE 멤버가 이 상태로 남아 있으면 자동 인증이 계속 skip 되므로, 앱을 무음으로 깨워
 * 셋업/권한 재요청을 유도한다({@link SilentPush#setupRequired}). 화면 알림은 띄우지 않는다.
 *
 * <p>전송은 {@link PushSender} 추상화 뒤에 있다(현재는 로깅 스텁 — FCM 자격증명 준비 시 어댑터만 교체).
 * 쿨다운({@link #COOLDOWN})으로 재발송 간격을 둬 스팸을 막고, FOR UPDATE SKIP LOCKED 선점으로
 * 다중 인스턴스에서도 중복 발송이 없다(활성화/완료 배치와 동일 패턴).
 */
@Service
@RequiredArgsConstructor
public class GhostPushSetupReminderService {

    private static final Logger log = LoggerFactory.getLogger(GhostPushSetupReminderService.class);

    /** 같은 멤버에게 다시 보내기까지의 최소 간격(스팸 방지). */
    private static final Duration COOLDOWN = Duration.ofHours(24);
    private static final int CLAIM_LIMIT = 200;

    private final ChallengeMemberRepository memberRepository;
    private final ChallengeRepository challengeRepository;
    private final PushSender pushSender;

    /** 1시간마다: 셋업 미완료(권한 없음) AUTO 멤버를 깨우는 무음 푸시를 보낸다. */
    @Scheduled(fixedDelay = 3_600_000L)
    @Transactional
    public void nudgeSetupPendingMembers() {
        Instant now = Instant.now();
        Instant cooldownThreshold = now.minus(COOLDOWN);
        List<ChallengeMember> claimed =
                memberRepository.findSetupPendingForGhostPushForUpdate(cooldownThreshold, CLAIM_LIMIT);

        int sent = 0;
        for (ChallengeMember m : claimed) {
            Challenge c = challengeRepository.findByIdAndDeletedAtIsNull(m.getChallengeId()).orElse(null);
            // 소프트삭제됐거나 AUTO 인증이 아니면(수동은 즉시형 카메라뿐 → 권한 유도 대상 아님) 건너뛴다.
            if (c == null || c.getVerificationConfig() == null
                    || c.getVerificationConfig().selectedMethod() != SelectedMethod.AUTO) {
                continue;
            }
            pushSender.sendSilent(m.getUserId(), SilentPush.setupRequired(c.getId().toString()));
            m.markGhostPushed(now);   // 쿨다운 기준 갱신(같은 트랜잭션에서 선점 락 유지 중)
            sent++;
        }
        if (sent > 0) {
            log.info("셋업 미완료 멤버 고스트 푸시 {}건 발송(대상 선점 {}건)", sent, claimed.size());
        }
    }
}
