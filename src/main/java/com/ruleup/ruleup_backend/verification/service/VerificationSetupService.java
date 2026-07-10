package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.common.verification.ScreenApp;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationRequest;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationResponse;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsResponse;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsUpdateRequest;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsUpdateResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupRequest;
import com.ruleup.ruleup_backend.verification.dto.SetupRequirementResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 최초 진입 셋업(§11.4) + 내 인증 장소 수정(§11.5).
 *
 * <p>셋업: 클라가 grant한 권한·바인딩 장소·대상 앱을 받아 config.requiredPermissions와 대조.
 * 모자란 게 있으면 PENDING_SETUP 유지 + missing[] 반환(소프트). 다 채워지면 READY → 평가 대상 진입.
 * (READY 전까지 VerificationSyncService가 해당 멤버 평가를 건너뜀.)
 *
 * <p>장소 수정: 본인 앵커만 즉시 교체. 쿨다운(최근 7일 내 변경 시 차단)으로 남용 방지.
 * 스펙의 "인증 윈도우 중 변경은 익일부터" 단계적 적용(staged anchors)은 후속 과제로 분리 — 지금은 즉시 반영.
 */
@Service
@RequiredArgsConstructor
public class VerificationSetupService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_ANCHORS = 10;
    private static final int MIN_RADIUS_M = 500;      // 계약 하한(반경 500~5000m)
    private static final int MAX_RADIUS_M = 5000;     // 상한
    private static final Duration CHANGE_COOLDOWN = Duration.ofDays(7); // §11.5 잦은 변경 방지

    // ===== my-screen-apps =====
    private static final int MAX_SCREEN_APPS = 10;    // 1~10개
    private static final Duration SCREEN_APPS_COOLDOWN = Duration.ofDays(1); // 최근 변경 쿨다운(같은 날 재요청은 예외)
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    /** Android 패키지명: 소문자/숫자/밑줄 세그먼트 2개 이상(a.b). */
    private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    private final ChallengeQueryService challengeQuery;
    private final VerificationConfigFactory configFactory;
    private final com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository dailyRepo;

    // ===== §11.4 최초 진입 셋업 요구사항 조회 (제출 전 안내용) =====
    @Transactional(readOnly = true)
    public SetupRequirementResponse getRequirements(UUID userId, UUID challengeId) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);
        VerificationConfig config = configFactory.build(ch);

        List<String> perms = (config.requiredPermissions() != null)
                ? config.requiredPermissions() : List.of();
        boolean requiresAnchors = config.hasMethod(VerificationMethod.GPS_PRESENCE);
        boolean anchorsConfigured = member.getAnchors() != null && !member.getAnchors().isEmpty();
        boolean requiresTargetPackages = config.hasMethod(VerificationMethod.SCREEN_TIME);

        return new SetupRequirementResponse(
                member.getSetupStatus().name(),
                config.isManual(),
                config.primaryMethod() != null ? config.primaryMethod().name() : null,
                perms,
                requiresAnchors,
                anchorsConfigured,
                requiresTargetPackages);
    }

    // ===== §11.4 최초 진입 셋업 =====
    @Transactional
    public SetupResponse setup(UUID userId, UUID challengeId, SetupRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);
        VerificationConfig config = configFactory.build(ch);

        List<String> missing = new ArrayList<>();

        // §5.6/§9: 셋업은 OS 권한을 받지 않는다(권한 보유 상태 미저장). 바인딩(앵커·대상앱)만 검증한다.

        // (1) GPS_PRESENCE: 바인딩 앵커 필요. 형식 오류는 즉시 400, "아직 없음"은 missing(소프트).
        boolean gps = config.hasMethod(VerificationMethod.GPS_PRESENCE);
        List<GeoAnchor> anchors = null;
        if (gps) {
            SetupRequest.LocationBinding loc = (req != null) ? req.location() : null;
            List<SetupRequest.AnchorDto> raw = (loc != null) ? loc.anchors() : null;
            if (raw == null || raw.isEmpty()) {
                missing.add("ANCHORS_REQUIRED");
            } else {
                anchors = toAnchors(raw);   // 형식 위반 시 INVALID_ANCHOR throw
            }
        }

        // (2) SCREEN_TIME: 대상 앱 선택 필요. 최초 셋업은 대기 없이 즉시 현재 세트로 적용(이후 변경은 my-screen-apps PUT로 익일 적용).
        Instant now = Instant.now();
        boolean screenTime = config.hasMethod(VerificationMethod.SCREEN_TIME);
        List<ScreenApp> screenApps = null;
        if (screenTime) {
            List<ScreenAppsUpdateRequest.AppDto> raw = (req != null) ? req.screenApps() : null;
            if (raw == null || raw.isEmpty()) {
                // 레거시 폴백: targetPackages(문자열) → appName 스냅샷 없이 바인딩.
                List<String> pkgs = (req != null) ? req.targetPackages() : null;
                raw = (pkgs != null) ? pkgs.stream()
                        .map(p -> new ScreenAppsUpdateRequest.AppDto(p, null)).toList() : null;
            }
            if (raw == null || raw.isEmpty()) {
                missing.add("TARGET_PACKAGES_REQUIRED");
            } else {
                screenApps = toScreenApps(raw);   // 형식 위반 시 INVALID_APP throw
            }
        }

        // 유효 앵커가 들어왔으면 모자란 항목과 무관하게 저장(부분 진행 보존).
        if (anchors != null) member.replaceAnchors(anchors, now);
        if (screenApps != null) member.setScreenAppsInitial(screenApps, now);

        if (missing.isEmpty()) {
            member.markSetupReady();
        }
        challengeQuery.saveMember(member);

        return new SetupResponse(member.getSetupStatus().name(), missing);
    }

    // ===== §11.5 내 인증 장소(앵커) 조회 =====
    /**
     * 내 바인딩 앵커 조회. 위치 셋업/수정 화면 재진입 시 지도에 기존 핀을 다시 그리기 위함.
     * (기존엔 GET /setup 의 anchorsConfigured(boolean)만 있어 좌표를 되읽을 수 없었다.)
     * 앵커가 하나도 없으면 데이터가 없는 것이므로 GEOFENCE_NOT_CONFIGURED 로 실패 응답(success=false).
     *  - anchors     : 저장된 앵커 목록(1개 이상).
     *  - appliedFrom : 마지막 적용 시각(ISO) 또는 null. 쿨다운 표시용.
     */
    @Transactional(readOnly = true)
    public MemberLocationResponse getMyLocation(UUID userId, UUID challengeId) {
        ChallengeMember member = activeMember(challengeId, userId);
        List<GeoAnchor> stored = member.getAnchors();
        if (stored == null || stored.isEmpty()) {
            throw new BusinessException(ErrorCode.GEOFENCE_NOT_CONFIGURED);
        }
        List<SetupRequest.AnchorDto> anchors = stored.stream()
                .map(a -> new SetupRequest.AnchorDto(a.lat(), a.lng(), a.radiusM(), a.label()))
                .toList();
        String appliedFrom = (member.getAnchorUpdatedAt() != null)
                ? member.getAnchorUpdatedAt().toString() : null;
        return new MemberLocationResponse(anchors, appliedFrom);
    }

    // ===== §11.5 내 인증 장소 수정 =====
    @Transactional
    public MemberLocationResponse updateLocation(UUID userId, UUID challengeId, MemberLocationRequest req) {
        ChallengeMember member = activeMember(challengeId, userId);

        if (req == null || req.anchors() == null || req.anchors().isEmpty()) {
            throw new BusinessException(ErrorCode.GEOFENCE_NOT_CONFIGURED);
        }

        Instant now = Instant.now();

        // 인증 윈도우 중에는 위치 변경 거부(§11.5) → 익일 재시도. 오늘 인증 행의 창이 아직 안 닫혔으면 잠금.
        LocalDate today = LocalDate.now(KST);
        dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), today).ifPresent(d -> {
            if (d.getWindowClosesAt() != null && now.isBefore(d.getWindowClosesAt()))
                throw new BusinessException(ErrorCode.LOCATION_LOCKED_IN_WINDOW);
        });

        // 쿨다운: 최근 7일 내 변경 이력이 있으면 차단.
        Instant last = member.getAnchorUpdatedAt();
        if (last != null && last.isAfter(now.minus(CHANGE_COOLDOWN))) {
            throw new BusinessException(ErrorCode.LOCATION_CHANGE_COOLDOWN);
        }

        List<GeoAnchor> anchors = toAnchors(req.anchors());
        member.replaceAnchors(anchors, now);
        // 셋업이 아직이면 장소가 채워졌으니 READY로 끌어올림(권한은 셋업에서 이미 본 것으로 가정).
        if (!member.isSetupReady()) member.markSetupReady();
        challengeQuery.saveMember(member);

        // 성공 시 항상 즉시 적용(계약: appliedFrom="IMMEDIATE").
        return new MemberLocationResponse(req.anchors(), "IMMEDIATE");
    }

    // ===== my-screen-apps: 내 측정 대상 앱 조회 =====
    /**
     * 내 스크린타임 측정 대상 앱 조회. 현재 적용 세트(apps) + 익일 적용 대기 세트(pending)를 함께 반환.
     * 도래한 대기 세트는 조회 시점에 현재 세트로 승격(persist)한다. 바인딩된 앱이 없으면 SCREENTIME_NOT_CONFIGURED.
     */
    @Transactional
    public ScreenAppsResponse getMyScreenApps(UUID userId, UUID challengeId) {
        ChallengeMember member = activeMember(challengeId, userId);
        LocalDate today = LocalDate.now(KST);
        if (member.promoteScreenAppsIfDue(today, KST)) {   // 대기 세트 적용일 도래 → 승격
            challengeQuery.saveMember(member);
        }

        List<ScreenApp> apps = member.getScreenApps();
        if (apps == null || apps.isEmpty()) {
            throw new BusinessException(ErrorCode.SCREENTIME_NOT_CONFIGURED);
        }

        String appliedFrom = (member.getScreenAppsAppliedFrom() != null)
                ? formatKst(member.getScreenAppsAppliedFrom()) : null;

        ScreenAppsResponse.Pending pending = null;
        if (member.hasPendingScreenApps(today) && member.getPendingScreenApps() != null) {
            String effectiveFrom = member.getPendingScreenAppsEffectiveDate()
                    .atStartOfDay(KST).format(ISO_OFFSET);
            pending = new ScreenAppsResponse.Pending(toAppDtos(member.getPendingScreenApps()), effectiveFrom);
        }
        return new ScreenAppsResponse(toAppDtos(apps), appliedFrom, pending);
    }

    // ===== my-screen-apps: 내 측정 대상 앱 교체 =====
    /**
     * 본인 측정 대상 앱 세트 교체. 항상 익일 00:00부터 적용(당일 교체로 인증 조작 방지).
     * 같은 날 재요청 시 대기 세트를 덮어쓴다(마지막 요청 승리). 최근 변경 쿨다운 중에는 SCREENTIME_CHANGE_COOLDOWN.
     */
    @Transactional
    public ScreenAppsUpdateResponse updateScreenApps(UUID userId, UUID challengeId, ScreenAppsUpdateRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);

        VerificationConfig config = configFactory.build(ch);
        if (!config.hasMethod(VerificationMethod.SCREEN_TIME)) {
            throw new BusinessException(ErrorCode.SCREENTIME_NOT_CONFIGURED);   // 스크린타임 인증 챌린지가 아님
        }
        if (req == null || req.apps() == null || req.apps().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_APP);
        }
        List<ScreenApp> apps = toScreenApps(req.apps());   // 형식·중복·개수 검증 → INVALID_APP

        LocalDate today = LocalDate.now(KST);
        member.promoteScreenAppsIfDue(today, KST);   // 도래한 대기 세트 먼저 승격(쿨다운/pending 판정 정확도)
        LocalDate effectiveDate = today.plusDays(1);       // 익일 00:00부터 적용

        // 쿨다운: 같은 날 재요청(이미 익일 대기 세트를 stage한 상태)은 덮어쓰기 허용, 그 외 최근 변경은 차단.
        Instant now = Instant.now();
        boolean sameDayOverwrite = member.hasPendingScreenApps(today);
        Instant last = member.getScreenAppsUpdatedAt();
        if (!sameDayOverwrite && last != null && last.isAfter(now.minus(SCREEN_APPS_COOLDOWN))) {
            throw new BusinessException(ErrorCode.SCREENTIME_CHANGE_COOLDOWN);
        }

        member.stagePendingScreenApps(apps, effectiveDate, now);
        challengeQuery.saveMember(member);

        String appliedFrom = effectiveDate.atStartOfDay(KST).format(ISO_OFFSET);
        return new ScreenAppsUpdateResponse(toAppDtos(apps), appliedFrom);
    }

    // ===== 헬퍼 =====
    private ChallengeMember activeMember(UUID challengeId, UUID userId) {
        ChallengeMember member = challengeQuery.findMembership(challengeId, userId).orElse(null);
        if (member == null || !member.isActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }
        return member;
    }

    /** AnchorDto[] → GeoAnchor[]. 개수·반경·좌표 범위 검증. 위반 시 INVALID_ANCHOR. */
    private List<GeoAnchor> toAnchors(List<SetupRequest.AnchorDto> raw) {
        if (raw.size() > MAX_ANCHORS) throw new BusinessException(ErrorCode.INVALID_ANCHOR);
        List<GeoAnchor> out = new ArrayList<>(raw.size());
        for (SetupRequest.AnchorDto a : raw) {
            if (a == null) throw new BusinessException(ErrorCode.INVALID_ANCHOR);
            if (a.lat() < -90 || a.lat() > 90 || a.lng() < -180 || a.lng() > 180) {
                throw new BusinessException(ErrorCode.INVALID_ANCHOR);
            }
            if (a.radiusM() < MIN_RADIUS_M || a.radiusM() > MAX_RADIUS_M) {
                throw new BusinessException(ErrorCode.INVALID_ANCHOR);
            }
            out.add(new GeoAnchor(a.lat(), a.lng(), a.radiusM(), a.label()));
        }
        return out;
    }

    /** AppDto[] → ScreenApp[]. 개수(1~10)·패키지명 형식·중복 검증. 위반 시 INVALID_APP. */
    private List<ScreenApp> toScreenApps(List<ScreenAppsUpdateRequest.AppDto> raw) {
        if (raw.isEmpty() || raw.size() > MAX_SCREEN_APPS) throw new BusinessException(ErrorCode.INVALID_APP);
        Set<String> seen = new LinkedHashSet<>();
        List<ScreenApp> out = new ArrayList<>(raw.size());
        for (ScreenAppsUpdateRequest.AppDto a : raw) {
            if (a == null || a.packageName() == null
                    || !PACKAGE_NAME.matcher(a.packageName().trim()).matches()) {
                throw new BusinessException(ErrorCode.INVALID_APP);
            }
            String pkg = a.packageName().trim();
            if (!seen.add(pkg)) throw new BusinessException(ErrorCode.INVALID_APP);   // 중복 불가
            out.add(new ScreenApp(pkg, a.appName()));
        }
        return out;
    }

    private List<ScreenAppsResponse.AppDto> toAppDtos(List<ScreenApp> apps) {
        return apps.stream()
                .map(a -> new ScreenAppsResponse.AppDto(a.packageName(), a.appName()))
                .toList();
    }

    private String formatKst(Instant instant) {
        return ZonedDateTime.ofInstant(instant, KST).format(ISO_OFFSET);
    }
}
