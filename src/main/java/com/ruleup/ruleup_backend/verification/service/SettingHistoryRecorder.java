package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.SettingKind;
import com.ruleup.ruleup_backend.verification.domain.VerificationSettingSnapshot;
import com.ruleup.ruleup_backend.verification.repository.VerificationSettingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 설정이 바뀔 때 "언제부터 적용되는 값인지"를 남긴다.
 *
 * <p>덮어쓰지 않고 쌓기만 한다 — 과거 날짜를 다시 평가할 때 그 날 적용되던 값을 찾아야 하기 때문이다.
 * 적용 시점은 설정마다 다르다. 앵커는 인증 시간 밖이면 변경일, 인증 시간 중이면 다음 날이고,
 * 대상 앱은 항상 다음 날 00:00 부터라 그 적용일이다.
 */
@Component
@RequiredArgsConstructor
public class SettingHistoryRecorder {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final VerificationSettingSnapshotRepository snapshotRepo;

    public void record(UUID challengeMemberId, SettingKind kind, LocalDate effectiveFrom, List<?> value) {
        String payload = JSON.writeValueAsString((value != null) ? value : List.of());
        snapshotRepo.save(VerificationSettingSnapshot.of(challengeMemberId, kind, effectiveFrom, payload));
    }

    /**
     * {@code date} 에 적용되던 이력이 없으면 지금 값을 {@code since} 부터 적용된 것으로 먼저 남긴다.
     *
     * <p>이력이 없을 때 판정은 멤버의 현재 값으로 폴백한다. 그래서 다음 날 적용할 새 값을 멤버에 먼저
     * 써 버리면, 이력 도입 이전 멤버는 오늘(과 유예 중인 지난 날) 판정이 새 값으로 넘어간다.
     */
    public void backfillIfMissing(UUID challengeMemberId, SettingKind kind, LocalDate date,
                                  LocalDate since, List<?> current) {
        if (!snapshotRepo.findEffective(challengeMemberId, kind, date, PageRequest.of(0, 1)).isEmpty()) return;
        record(challengeMemberId, kind, since, current);
    }

    /** 아직 오지 않은 적용일이 잡혀 있으면 그 날. 없으면 null. */
    public LocalDate pendingFrom(UUID challengeMemberId, SettingKind kind, LocalDate today) {
        return snapshotRepo.findFirstByChallengeMemberIdAndKindOrderByEffectiveFromDescCreatedAtDesc(challengeMemberId, kind)
                .map(VerificationSettingSnapshot::getEffectiveFrom)
                .filter(today::isBefore)
                .orElse(null);
    }
}
