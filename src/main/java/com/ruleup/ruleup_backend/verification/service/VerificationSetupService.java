package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.common.verification.ScreenApp;
import com.ruleup.ruleup_backend.verification.config.VerificationProperties;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.dto.AnchorDto;
import com.ruleup.ruleup_backend.verification.dto.AppDto;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationRequest;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationResponse;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationUpdateResponse;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsResponse;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsUpdateRequest;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsUpdateResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupRequest;
import com.ruleup.ruleup_backend.verification.dto.SetupRequirementResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 최초 진입 셋업(GET/POST /setup)과 내 바인딩 수정(my-location / my-screen-apps).
 *
 * <p><b>셋업</b>: 앵커·대상 앱 바인딩만 받는다. OS 권한은 생성/가입 단계에서 클라가 이미 받았으므로
 * 여기서 받지 않고, 서버는 보유 여부를 저장하지도 않는다. 필요한 바인딩을 다 채우면 READY(평가 대상 진입),
 * 모자라면 PENDING_SETUP + missing[]. READY 전까지 VerificationSyncService 가 그 멤버 평가를 건너뛴다.
 *
 * <p><b>반경은 유저가 정하지 않는다</b> — 서버 설정 단일값(VerificationProperties.geofenceRadiusM)이라
 * 요청에서 radiusM 이 빠졌고, 응답의 serverRadiusM 으로만 내려간다.
 *
 * <p><b>변경은 월 1회</b>(앵커·대상 앱 공통, 매월 1일 00:00 KST 리셋). 첫 설정은 소진하지 않는다.
 * 앵커 변경은 인증 윈도우 중이면 거부(409)하고 평상시엔 즉시 적용, 대상 앱 변경은 항상 익일 00:00부터 적용된다.
 */
@Service
@RequiredArgsConstructor
public class VerificationSetupService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 앵커 최대 개수(구 스펙 10개에서 축소). 초과 시 ANCHOR_LIMIT_EXCEEDED. */
    private static final int MAX_ANCHORS = 3;
    /** 측정 대상 앱 1~10개. */
    private static final int MAX_SCREEN_APPS = 10;
    /** Android 패키지명: 영문 시작 세그먼트 2개 이상(a.b). */
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    private final ChallengeQueryService challengeQuery;
    private final VerificationConfigFactory configFactory;
    private final VerificationDailyRepository dailyRepo;
    private final VerificationProperties properties;

    // ===== GET /setup — 최초 진입 시 설정이 필요한 정보 조회 =====
    @Transactional(readOnly = true)
    public SetupRequirementResponse getRequirements(UUID userId, UUID challengeId) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);
        VerificationConfig config = configFactory.build(ch);

        List<String> perms = (config.requiredPermissions() != null)
                ? config.requiredPermissions() : List.of();

        return new SetupRequirementResponse(
                member.getSetupStatus().name(),
                config.isManual(),
                config.primaryMethod() != null ? config.primaryMethod().name() : null,
                perms,
                config.hasMethod(VerificationMethod.GPS_PRESENCE),
                member.getAnchors() != null && !member.getAnchors().isEmpty(),
                config.hasMethod(VerificationMethod.SCREEN_TIME));
    }

    // ===== POST /setup — 인증 장소/인증 앱 제출 =====
    @Transactional
    public SetupResponse setup(UUID userId, UUID challengeId, SetupRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);
        VerificationConfig config = configFactory.build(ch);

        List<String> missing = new ArrayList<>();

        // (1) GPS_PRESENCE: 앵커 바인딩 필요. 형식 위반은 즉시 400, "아직 없음"은 missing(소프트).
        boolean gps = config.hasMethod(VerificationMethod.GPS_PRESENCE);
        List<GeoAnchor> anchors = null;
        if (gps) {
            SetupRequest.LocationBinding loc = (req != null) ? req.location() : null;
            List<AnchorDto> raw = (loc != null) ? loc.anchors() : null;
            if (raw == null || raw.isEmpty()) missing.add("ANCHORS_REQUIRED");
            else anchors = toAnchors(raw);
        }

        // (2) SCREEN_TIME: 대상 앱 선택 필요. 첫 설정은 대기 없이 즉시 현재 세트로 적용
        //     (이후 변경은 PUT my-screen-apps 로 익일 00:00부터).
        boolean screenTime = config.hasMethod(VerificationMethod.SCREEN_TIME);
        List<ScreenApp> screenApps = null;
        if (screenTime) {
            List<AppDto> raw = (req != null) ? req.targetPackages() : null;
            if (raw == null || raw.isEmpty()) missing.add("TARGET_PACKAGES_REQUIRED");
            else screenApps = toScreenApps(raw);
        }

        // 유효한 바인딩이 들어왔으면 모자란 항목과 무관하게 저장(부분 진행 보존).
        Instant now = Instant.now();
        if (anchors != null) member.replaceAnchors(anchors, now);      // 첫 설정 — 월 1회를 소진하지 않는다
        if (screenApps != null) member.setScreenAppsInitial(screenApps, now);
        if (missing.isEmpty()) member.markSetupReady();
        challengeQuery.saveMember(member);

        return new SetupResponse(member.getSetupStatus().name(), missing,
                gps ? properties.geofenceRadiusM() : null);
    }

    // ===== GET /my-location — 내 인증장소 조회 =====
    /**
     * 위치 셋업/수정 화면 재진입 시 지도에 이전 핀을 복원하기 위한 조회.
     * 바인딩된 앵커가 하나도 없으면 GEOFENCE_NOT_CONFIGURED(첫 설정은 setup API로).
     */
    @Transactional(readOnly = true)
    public MemberLocationResponse getMyLocation(UUID userId, UUID challengeId) {
        ChallengeMember member = activeMember(challengeId, userId);
        List<GeoAnchor> stored = member.getAnchors();
        if (stored == null || stored.isEmpty()) {
            throw new BusinessException(ErrorCode.GEOFENCE_NOT_CONFIGURED);
        }
        Instant now = Instant.now();
        Instant lastChanged = member.getAnchorChangedAt();
        return new MemberLocationResponse(
                toAnchorDtos(stored),
                properties.geofenceRadiusM(),
                formatKst(member.getAnchorUpdatedAt()),
                MonthlyChangeLimit.available(lastChanged, now),
                MonthlyChangeLimit.nextChangeAvailableAtOrNull(lastChanged, now));
    }

    // ===== PUT /my-location — 내 인증장소 수정 =====
    /**
     * 보낸 목록으로 앵커 세트 전체를 갈아끼운다(부분 수정 아님). 월 1회, 인증 윈도우 중에는 거부(익일 재시도),
     * 평상시엔 즉시 적용. 모더레이션은 타지 않는다(앵커는 심사 대상이 아님).
     */
    @Transactional
    public MemberLocationUpdateResponse updateLocation(UUID userId, UUID challengeId, MemberLocationRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);

        if (!configFactory.build(ch).hasMethod(VerificationMethod.GPS_PRESENCE)) {
            throw new BusinessException(ErrorCode.GEOFENCE_NOT_CONFIGURED);   // GPS 인증 챌린지가 아님
        }
        if (req == null || req.anchors() == null || req.anchors().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ANCHOR);
        }

        Instant now = Instant.now();

        // 그날 판정을 흔들 수 없게, 인증 윈도우가 진행 중이면 교체를 거부한다(익일 재시도).
        LocalDate today = LocalDate.now(KST);
        dailyRepo.findByChallengeMemberIdAndTargetDate(member.getId(), today).ifPresent(d -> {
            if (d.getWindowClosesAt() != null && now.isBefore(d.getWindowClosesAt()))
                throw new BusinessException(ErrorCode.LOCATION_LOCKED_IN_WINDOW);
        });

        if (!MonthlyChangeLimit.available(member.getAnchorChangedAt(), now)) {
            throw new BusinessException(ErrorCode.SETTING_CHANGE_LIMIT);
        }

        List<GeoAnchor> anchors = toAnchors(req.anchors());
        member.changeAnchors(anchors, now);
        if (!member.isSetupReady()) member.markSetupReady();
        challengeQuery.saveMember(member);

        return new MemberLocationUpdateResponse(
                toAnchorDtos(anchors), properties.geofenceRadiusM(),
                "IMMEDIATE", MonthlyChangeLimit.nextChangeAvailableAt(now));
    }

    // ===== GET /my-screen-apps — 내 스크린타임 앱 조회 =====
    /**
     * 현재 적용 세트(apps) + 익일 적용 대기 세트(pending)를 함께 반환. 도래한 대기 세트는 조회 시점에 승격한다.
     * 바인딩된 앱이 하나도 없으면 SCREENTIME_NOT_CONFIGURED.
     */
    @Transactional
    public ScreenAppsResponse getMyScreenApps(UUID userId, UUID challengeId) {
        ChallengeMember member = activeMember(challengeId, userId);
        LocalDate today = LocalDate.now(KST);
        if (member.promoteScreenAppsIfDue(today, KST)) {
            challengeQuery.saveMember(member);
        }

        List<ScreenApp> apps = member.getScreenApps();
        if (apps == null || apps.isEmpty()) {
            throw new BusinessException(ErrorCode.SCREENTIME_NOT_CONFIGURED);
        }

        ScreenAppsResponse.Pending pending = null;
        if (member.hasPendingScreenApps(today) && member.getPendingScreenApps() != null) {
            pending = new ScreenAppsResponse.Pending(
                    toAppDtos(member.getPendingScreenApps()),
                    member.getPendingScreenAppsEffectiveDate().atStartOfDay(KST).format(ISO_OFFSET));
        }

        Instant now = Instant.now();
        Instant lastChanged = member.getScreenAppsChangedAt();
        return new ScreenAppsResponse(
                toAppDtos(apps),
                formatKst(member.getScreenAppsAppliedFrom()),
                pending,
                MonthlyChangeLimit.available(lastChanged, now),
                MonthlyChangeLimit.nextChangeAvailableAtOrNull(lastChanged, now));
    }

    // ===== PUT /my-screen-apps — 내 스크린타임 앱 수정 =====
    /**
     * 보낸 목록으로 세트 전체를 갈아끼운다(부분 수정 아님). 월 1회, 적용은 항상 익일 00:00부터 —
     * 오늘 측정분은 오늘 0시 기준 세트로 판정하므로 당일 교체로 인증을 조작할 수 없다.
     * 목표값(N분 이하/이상)은 정책상 변경 불가라 이 API에서 다루지 않는다.
     */
    @Transactional
    public ScreenAppsUpdateResponse updateScreenApps(UUID userId, UUID challengeId, ScreenAppsUpdateRequest req) {
        Challenge ch = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);

        if (!configFactory.build(ch).hasMethod(VerificationMethod.SCREEN_TIME)) {
            throw new BusinessException(ErrorCode.SCREENTIME_NOT_CONFIGURED);   // 스크린 타임 인증 챌린지가 아님
        }
        if (req == null || req.apps() == null || req.apps().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_APP);
        }
        List<ScreenApp> apps = toScreenApps(req.apps());

        Instant now = Instant.now();
        if (!MonthlyChangeLimit.available(member.getScreenAppsChangedAt(), now)) {
            throw new BusinessException(ErrorCode.SETTING_CHANGE_LIMIT);
        }

        LocalDate today = LocalDate.now(KST);
        member.promoteScreenAppsIfDue(today, KST);     // 도래한 대기 세트를 먼저 승격(pending 판정 정확도)
        LocalDate effectiveDate = today.plusDays(1);   // 익일 00:00부터 적용
        member.stagePendingScreenApps(apps, effectiveDate, now);
        challengeQuery.saveMember(member);

        return new ScreenAppsUpdateResponse(
                toAppDtos(apps),
                effectiveDate.atStartOfDay(KST).format(ISO_OFFSET),
                MonthlyChangeLimit.nextChangeAvailableAt(now));
    }

    // ===== 헬퍼 =====
    private ChallengeMember activeMember(UUID challengeId, UUID userId) {
        ChallengeMember member = challengeQuery.findMembership(challengeId, userId).orElse(null);
        if (member == null || !member.isActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }
        return member;
    }

    /**
     * AnchorDto[] → GeoAnchor[]. 개수는 ANCHOR_LIMIT_EXCEEDED, 좌표 범위는 INVALID_ANCHOR.
     * 반경은 요청에 없으므로 서버 설정값을 채워 저장한다.
     */
    private List<GeoAnchor> toAnchors(List<AnchorDto> raw) {
        if (raw.size() > MAX_ANCHORS) throw new BusinessException(ErrorCode.ANCHOR_LIMIT_EXCEEDED);
        int radiusM = properties.geofenceRadiusM();
        List<GeoAnchor> out = new ArrayList<>(raw.size());
        for (AnchorDto a : raw) {
            if (a == null || a.lat() < -90 || a.lat() > 90 || a.lng() < -180 || a.lng() > 180) {
                throw new BusinessException(ErrorCode.INVALID_ANCHOR);
            }
            out.add(new GeoAnchor(a.lat(), a.lng(), radiusM, a.label()));
        }
        return out;
    }

    /** AppDto[] → ScreenApp[]. 개수(1~10)·패키지명 형식·중복 위반 시 INVALID_APP. */
    private List<ScreenApp> toScreenApps(List<AppDto> raw) {
        if (raw.isEmpty() || raw.size() > MAX_SCREEN_APPS) throw new BusinessException(ErrorCode.INVALID_APP);
        Set<String> seen = new LinkedHashSet<>();
        List<ScreenApp> out = new ArrayList<>(raw.size());
        for (AppDto a : raw) {
            if (a == null || a.packageName() == null
                    || !PACKAGE_NAME.matcher(a.packageName().trim()).matches()) {
                throw new BusinessException(ErrorCode.INVALID_APP);
            }
            String pkg = a.packageName().trim();
            if (!seen.add(pkg)) throw new BusinessException(ErrorCode.INVALID_APP);
            out.add(new ScreenApp(pkg, a.appName()));
        }
        return out;
    }

    private List<AnchorDto> toAnchorDtos(List<GeoAnchor> anchors) {
        return anchors.stream().map(a -> new AnchorDto(a.lat(), a.lng(), a.label())).toList();
    }

    private List<AppDto> toAppDtos(List<ScreenApp> apps) {
        return apps.stream().map(a -> new AppDto(a.packageName(), a.appName())).toList();
    }

    private String formatKst(Instant instant) {
        return (instant != null) ? ZonedDateTime.ofInstant(instant, KST).format(ISO_OFFSET) : null;
    }
}
