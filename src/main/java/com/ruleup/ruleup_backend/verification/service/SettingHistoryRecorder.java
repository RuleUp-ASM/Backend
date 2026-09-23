package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.SettingKind;
import com.ruleup.ruleup_backend.verification.domain.VerificationSettingSnapshot;
import com.ruleup.ruleup_backend.verification.repository.VerificationSettingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 설정이 바뀔 때 "언제부터 적용되는 값인지"를 남긴다.
 *
 * <p>덮어쓰지 않고 쌓기만 한다 — 과거 날짜를 다시 평가할 때 그 날 적용되던 값을 찾아야 하기 때문이다.
 * 적용 시점은 설정마다 다르다. 앵커는 변경 즉시라 변경일, 대상 앱은 다음 날 00:00 부터라 그 적용일이다.
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
}
