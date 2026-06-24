package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.verification.domain.GeoAnchor;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationRequest;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupRequest;
import com.ruleup.ruleup_backend.verification.dto.SetupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    private static final int MIN_RADIUS_M = 20;       // 지오펜스 현실 하한(헬스장·카페 핀)
    private static final int MAX_RADIUS_M = 5000;     // 상한
    private static final Duration CHANGE_COOLDOWN = Duration.ofDays(7); // §11.5 잦은 변경 방지

    private final ChallengeRepository challengeRepo;
    private final ChallengeMemberRepository memberRepo;
    private final VerificationConfigFactory configFactory;

    // ===== §11.4 최초 진입 셋업 =====
    @Transactional
    public SetupResponse setup(UUID userId, UUID challengeId, SetupRequest req) {
        Challenge ch = challengeRepo.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeMember member = activeMember(challengeId, userId);
        VerificationConfig config = configFactory.build(ch);

        List<String> missing = new ArrayList<>();

        // (1) 권한: config가 요구하는 권한 중 grant 안 된 것.
        Set<String> granted = new HashSet<>();
        if (req != null && req.grantedPermissions() != null) granted.addAll(req.grantedPermissions());
        if (config.requiredPermissions() != null) {
            for (String need : config.requiredPermissions()) {
                if (need != null && !granted.contains(need)) missing.add("PERMISSION:" + need);
            }
        }

        // (2) GPS_PRESENCE: 바인딩 앵커 필요. 형식 오류는 즉시 400, "아직 없음"은 missing(소프트).
        boolean gps = config.hasMethod(VerificationMethod.GPS_PRESENCE);
        List<GeoAnchor> anchors = null;
        if (gps) {
            SetupRequest.LocationBinding loc = (req != null) ? req.location() : null;
            List<SetupRequest.AnchorDto> raw = (loc != null) ? loc.anchors() : null;
            if (raw == null || raw.isEmpty()) {
                missing.add("ANCHORS");
            } else {
                anchors = toAnchors(raw);   // 형식 위반 시 INVALID_ANCHOR throw
            }
        }

        // (3) SCREEN_TIME: 대상 앱 선택 필요. (멤버에 별도 컬럼 없음 → 현재는 존재 여부만 검증, 영속화는 후속 과제로 flag.)
        if (config.hasMethod(VerificationMethod.SCREEN_TIME)) {
            List<String> pkgs = (req != null) ? req.targetPackages() : null;
            if (pkgs == null || pkgs.isEmpty()) missing.add("TARGET_PACKAGES");
        }

        Instant now = Instant.now();
        // 유효 앵커가 들어왔으면 모자란 항목과 무관하게 저장(부분 진행 보존).
        if (anchors != null) member.replaceAnchors(anchors, now);

        if (missing.isEmpty()) {
            member.markSetupReady();
        }
        memberRepo.save(member);

        return new SetupResponse(member.getSetupStatus().name(), missing);
    }

    // ===== §11.5 내 인증 장소 수정 =====
    @Transactional
    public MemberLocationResponse updateLocation(UUID userId, UUID challengeId, MemberLocationRequest req) {
        ChallengeMember member = activeMember(challengeId, userId);

        if (req == null || req.anchors() == null || req.anchors().isEmpty()) {
            throw new BusinessException(ErrorCode.GEOFENCE_NOT_CONFIGURED);
        }

        // 쿨다운: 최근 7일 내 변경 이력이 있으면 차단.
        Instant now = Instant.now();
        Instant last = member.getAnchorUpdatedAt();
        if (last != null && last.isAfter(now.minus(CHANGE_COOLDOWN))) {
            throw new BusinessException(ErrorCode.LOCATION_CHANGE_COOLDOWN);
        }

        List<GeoAnchor> anchors = toAnchors(req.anchors());
        member.replaceAnchors(anchors, now);
        // 셋업이 아직이면 장소가 채워졌으니 READY로 끌어올림(권한은 셋업에서 이미 본 것으로 가정).
        if (!member.isSetupReady()) member.markSetupReady();
        memberRepo.save(member);

        // 즉시 적용. (윈도우-락 "익일부터" 단계 적용은 staged anchors 스키마 도입 후속 과제.)
        String appliedFrom = LocalDate.now(KST).toString();
        return new MemberLocationResponse(req.anchors(), appliedFrom);
    }

    // ===== 헬퍼 =====
    private ChallengeMember activeMember(UUID challengeId, UUID userId) {
        ChallengeMember member = memberRepo.findByChallengeIdAndUserId(challengeId, userId).orElse(null);
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
}
