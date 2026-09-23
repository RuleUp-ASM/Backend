package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.common.verification.ScreenApp;
import com.ruleup.ruleup_backend.verification.domain.SettingKind;
import com.ruleup.ruleup_backend.verification.domain.VerificationSettingSnapshot;
import com.ruleup.ruleup_backend.verification.repository.VerificationSettingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.CollectionType;

import java.time.LocalDate;
import java.util.List;

/**
 * 날짜 D 에 적용되던 멤버 설정(인증 장소·대상 앱)을 되짚는다.
 *
 * <p>유예 구간(D+1\~D+2) 덕분에 과거 날짜를 다시 평가하는 일이 생겼는데, 그때 <b>지금</b> 설정을 쓰면
 * 어제 갔던 곳이 갑자기 "안 간 곳"이 된다. 스냅샷 이력에서 그 날 적용되던 값을 찾아 쓴다.
 *
 * <p>이력이 없으면 멤버의 현재 값으로 폴백한다 — 이력 도입 이전 데이터가 조용히 실패하지 않게 하는 안전망이다.
 */
@Component
@RequiredArgsConstructor
public class MemberSettingsResolver {

    private static final Logger log = LoggerFactory.getLogger(MemberSettingsResolver.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final VerificationSettingSnapshotRepository snapshotRepo;

    /** 그 날짜에 적용되던 인증 장소. 없으면 현재 값. */
    public List<GeoAnchor> anchorsOn(ChallengeMember member, LocalDate date) {
        List<GeoAnchor> historical = read(member, SettingKind.ANCHORS, date, GeoAnchor.class);
        if (historical != null) return historical;
        return (member.getAnchors() != null) ? member.getAnchors() : List.of();
    }

    /** 그 날짜에 적용되던 대상 앱 패키지명. 없으면 멤버의 그날 적용 세트. */
    public List<String> screenAppPackagesOn(ChallengeMember member, LocalDate date) {
        List<ScreenApp> historical = read(member, SettingKind.SCREEN_APPS, date, ScreenApp.class);
        List<ScreenApp> apps = (historical != null) ? historical : member.effectiveScreenApps(date);
        return apps.stream().map(ScreenApp::packageName).toList();
    }

    private <T> List<T> read(ChallengeMember member, SettingKind kind, LocalDate date, Class<T> type) {
        List<VerificationSettingSnapshot> found =
                snapshotRepo.findEffective(member.getId(), kind, date, PageRequest.of(0, 1));
        if (found.isEmpty()) return null;
        String payload = found.get(0).getPayload();
        if (payload == null || payload.isBlank()) return List.of();
        try {
            CollectionType listType = JSON.getTypeFactory().constructCollectionType(List.class, type);
            return JSON.readValue(payload, listType);
        } catch (RuntimeException e) {
            // 이력이 깨졌다고 판정을 막지는 않는다 — 현재 값으로 폴백하고 관측만 남긴다.
            log.warn("설정 스냅샷 파싱 실패 memberId={} kind={} date={}", member.getId(), kind, date, e);
            return null;
        }
    }
}
