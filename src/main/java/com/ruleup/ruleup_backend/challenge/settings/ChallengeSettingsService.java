package com.ruleup.ruleup_backend.challenge.settings;

import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUpload;
import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUploadRepository;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.draft.DraftView;
import com.ruleup.ruleup_backend.challenge.dto.ChallengeSettingsResponse;
import com.ruleup.ruleup_backend.challenge.dto.PatchChallengeResponse;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationRequested;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 챌린지 설정 조회(방장 전용) + 수정(PATCH, JSON Merge Patch 유사) — API 명세 기준.
 *
 *  - editableFields 는 서버가 잠금 규칙으로 계산한 결과가 최종 권위다:
 *    시작 전 + 방장 혼자 = 카테고리 제외 전부 / 그 외 = 제목·설명·정원·이미지.
 *  - PATCH 는 저장 직전 행 잠금 하에서 상태·참여 인원·version 을 재검증한다(가입과의 경합 감지).
 *    body 에 없는 필드는 변경하지 않고, null 은 imageUrl(기본 이미지 되돌리기)에서만 유효하다.
 *  - 제목·설명·이미지는 언제 고치든 재심사(세트 1회) — 반복 거부 잠금 중이면 429.
 */
@Service
@RequiredArgsConstructor
public class ChallengeSettingsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CAPACITY_MAX = 10_000;

    /** 시작 전 + 방장 혼자일 때 수정 가능한 전체 필드(카테고리 제외 — 어떤 상황에도 불변). */
    private static final List<String> FULL_EDITABLE = List.of(
            "title", "description", "imageUrl", "capacity", "mode", "visibility",
            "rankingVisible", "minTier", "period", "weeklyCount", "params", "verification", "penalties.watcher");

    /** 참여자 발생·시작 이후에도 수정 가능한 필드. */
    private static final List<String> LIMITED_EDITABLE = List.of(
            "title", "description", "capacity", "imageUrl");

    private final ChallengeRepository challengeRepository;
    private final ChallengeImageUploadRepository imageUploadRepository;
    private final RoutineCatalog catalog;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ===== GET /settings =====

    @Transactional(readOnly = true)
    public ChallengeSettingsResponse settings(UUID userId, UUID challengeId) {
        Challenge c = load(challengeId);
        ensureOwner(c, userId);
        return new ChallengeSettingsResponse(
                new ChallengeSettingsResponse.Config(
                        c.getTitle(), c.getDescription(), c.getImageUrl(),
                        c.getCategory(), c.getParticipationType().name(),
                        c.getVisibility(), c.getRankingVisible(), c.getMaxParticipants(),
                        (c.getMinTier() != null) ? c.getMinTier().name() : null,
                        new DraftView.Period(c.getStartDate().toString(), c.getEndDate().toString()),
                        c.getWeeklyCount(),
                        (c.getParamSpecs() != null) ? c.getParamSpecs() : List.of(),
                        verificationView(c),
                        new ChallengeSettingsResponse.Penalties(
                                c.getPenalties() != null && c.getPenalties().score(),
                                c.getPenalties() != null && c.getPenalties().groupShare(),
                                c.getPenalties() != null && c.getPenalties().watcher())),
                editableFields(c),
                c.getVersion(),
                new ChallengeSettingsResponse.Moderation(
                        c.getModerationTitle().name(),
                        c.getModerationDescription().name(),
                        c.getModerationImage().name()));
    }

    // ===== PATCH =====

    @Transactional
    public PatchChallengeResponse patch(UUID userId, UUID challengeId, JsonNode body) {
        // 저장 직전 상태·참여 인원 재검증을 위해 행 잠금 로드(가입 트랜잭션과 직렬화)
        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ensureOwner(c, userId);

        // version 필수 + 일치(그 사이 수정·가입이 있었으면 재조회 유도)
        if (body == null || !body.has("version") || !body.get("version").isNumber())
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        if (body.get("version").intValue() != c.getVersion())
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);

        // 반복 거부 수정 잠금(제목·설명·이미지 재심사 대상 항목만 잠근다)
        Instant now = Instant.now();
        boolean touchesModerated = body.has("title") || body.has("description") || body.has("imageUrl");
        if (touchesModerated && c.isModerationLocked(now)) {
            long retryAfter = Math.max(1, Duration.between(now, c.getModerationLockedUntil()).toSeconds());
            throw new BusinessException(ErrorCode.MODERATION_LOCKED, String.valueOf(retryAfter));
        }

        // 잠금 범위: 카테고리는 항상 불가, 방 성격 항목은 시작 전+혼자일 때만
        boolean fullEditable = c.isUpcoming() && c.getParticipantCount() <= 1;
        if (body.has("category")) rejectNotEditable(c);
        if (!fullEditable) {
            for (String field : List.of("mode", "visibility", "rankingVisible", "minTier",
                    "period", "weeklyCount", "params", "verification", "penalties")) {
                if (body.has(field)) rejectNotEditable(c);
            }
        }

        Map<String, Object> updated = new LinkedHashMap<>();
        Map<String, String> moderation = new LinkedHashMap<>();

        applyTitle(c, body, updated, moderation);
        applyDescription(c, body, updated, moderation);
        applyImage(c, userId, body, now, updated, moderation);
        applyCapacity(c, body, updated);

        if (fullEditable) {
            applyMode(c, body, updated);
            applyVisibility(c, body, updated);
            applyRankingVisible(c, body, updated);
            applyMinTier(c, userId, body, updated);
            applyPeriod(c, body, updated);
            applyWeeklyCount(c, body, updated);
            applyParams(c, body, updated);
            applyVerification(c, body, updated);
            applyWatcher(c, body, updated);
        }

        if (!updated.isEmpty()) c.bumpVersion();
        if (!moderation.isEmpty()) {
            eventPublisher.publishEvent(new ChallengeModerationRequested(c.getId()));
        }
        return new PatchChallengeResponse(
                c.getId().toString(),
                moderation.isEmpty() ? null : moderation,
                updated);
    }

    // ===== 항목별 적용 =====

    private void applyTitle(Challenge c, JsonNode body, Map<String, Object> updated, Map<String, String> moderation) {
        if (!body.has("title")) return;
        JsonNode node = body.get("title");
        if (node.isNull() || !node.isString()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        String title = node.stringValue();
        if (title.isBlank()) throw new BusinessException(ErrorCode.TITLE_REQUIRED);
        if (title.length() > 30) throw new BusinessException(ErrorCode.TITLE_TOO_LONG);
        if (title.equals(c.getTitle())) return;   // 값이 같으면 변경 아님(재심사도 없음)
        c.changeTitle(title);
        c.markTitleInReview();                    // 수정할 때마다 재심사(§3.2)
        updated.put("title", title);
        moderation.put("title", "IN_REVIEW");
    }

    private void applyDescription(Challenge c, JsonNode body, Map<String, Object> updated, Map<String, String> moderation) {
        if (!body.has("description")) return;
        JsonNode node = body.get("description");
        if (node.isNull() || !node.isString()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        String description = node.stringValue();
        if (description.length() > 200) throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);
        if (description.equals(c.getDescription())) return;
        c.changeDescription(description);
        c.markDescriptionInReview();
        updated.put("description", description);
        moderation.put("description", "IN_REVIEW");
    }

    private void applyImage(Challenge c, UUID userId, JsonNode body, Instant now,
                            Map<String, Object> updated, Map<String, String> moderation) {
        if (!body.has("imageUrl")) return;
        JsonNode node = body.get("imageUrl");
        if (node.isNull()) {
            // null = 기본 이미지로 되돌리기(유일하게 null 이 유효한 필드)
            if (c.getImageUrl() == null) return;
            c.changeImage(null);
            updated.put("imageUrl", null);
            return;
        }
        if (!node.isString()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        String imageUrl = node.stringValue();
        if (imageUrl.equals(c.getImageUrl())) return;
        ChallengeImageUpload upload = imageUploadRepository.findByImageUrl(imageUrl)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_IMAGE_URL));
        if (!upload.ownedBy(userId)) throw new BusinessException(ErrorCode.IMAGE_NOT_OWNED);
        upload.markRegistered(now);
        c.changeImage(imageUrl);                  // IN_REVIEW 로 전환(고칠 때마다 재심사)
        updated.put("imageUrl", imageUrl);
        moderation.put("image", "IN_REVIEW");
    }

    private void applyCapacity(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("capacity")) return;
        JsonNode node = body.get("capacity");
        if (node.isNull() || !node.isNumber()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        int capacity = node.intValue();
        if (capacity < 1 || capacity > CAPACITY_MAX)
            throw new BusinessException(ErrorCode.CAPACITY_OUT_OF_RANGE);
        if (capacity < c.getParticipantCount())
            throw new BusinessException(ErrorCode.CAPACITY_BELOW_CURRENT);
        if (!c.isGroup()) return;                 // 솔로는 정원 1 고정 — 적용 대상 아님
        c.changeMaxParticipants(capacity);
        updated.put("capacity", capacity);
    }

    private void applyMode(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("mode")) return;
        JsonNode node = body.get("mode");
        if (node.isNull() || !node.isString()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        ParticipationType mode;
        try {
            mode = ParticipationType.valueOf(node.stringValue());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARTICIPATION_TYPE);
        }
        if (mode == c.getParticipationType()) return;
        // 파생 필드 정규화는 서버 책임 — 요청에 함께 온 visibility/rankingVisible 을 반영해 재계산
        String visibility = body.has("visibility") && body.get("visibility").isString()
                ? body.get("visibility").stringValue() : null;
        Boolean rankingVisible = body.has("rankingVisible") && body.get("rankingVisible").isBoolean()
                ? body.get("rankingVisible").booleanValue() : null;
        c.changeModeNormalized(mode, visibility, rankingVisible);
        updated.put("mode", mode.name());
    }

    private void applyVisibility(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("visibility") || body.has("mode")) return;   // mode 전환 시 이미 정규화됨
        JsonNode node = body.get("visibility");
        if (node.isNull() || !node.isString()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        String visibility = node.stringValue();
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility))
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        if (!c.isGroup() || visibility.equals(c.getVisibility())) return;
        c.changeVisibility(visibility);
        updated.put("visibility", visibility);
    }

    private void applyRankingVisible(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("rankingVisible") || body.has("mode")) return;
        JsonNode node = body.get("rankingVisible");
        if (node.isNull() || !node.isBoolean()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        boolean rankingVisible = node.booleanValue();
        if (c.isGroup()) return;
        c.changeRankingVisible(rankingVisible);
        updated.put("rankingVisible", rankingVisible);
    }

    private void applyMinTier(Challenge c, UUID userId, JsonNode body, Map<String, Object> updated) {
        if (!body.has("minTier")) return;
        JsonNode node = body.get("minTier");
        if (node.isNull() || !node.isString()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        Tier requested;
        try {
            requested = Tier.valueOf(node.stringValue());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }
        Tier owner = scoreSummaryRepository.findById(userId)
                .map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        if (owner == Tier.UNRANKED) owner = Tier.BRONZE;
        if (requested == Tier.UNRANKED || requested.ordinal() > owner.ordinal())
            throw new BusinessException(ErrorCode.MIN_TIER_EXCEEDS_OWNER);
        if (requested == c.getMinTier()) return;
        c.changeMinTier(requested);
        updated.put("minTier", requested.name());
    }

    private void applyPeriod(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("period")) return;
        JsonNode node = body.get("period");
        if (node.isNull() || !node.isObject() || !node.has("start") || !node.has("end"))
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        LocalDate start, end;
        try {
            start = LocalDate.parse(node.get("start").stringValue());
            end = LocalDate.parse(node.get("end").stringValue());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
        if (end.isBefore(start) || start.isBefore(LocalDate.now(KST)))
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        c.changePeriod(start, end);
        updated.put("period", Map.of("start", start.toString(), "end", end.toString()));
    }

    private void applyWeeklyCount(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("weeklyCount")) return;
        JsonNode node = body.get("weeklyCount");
        if (node.isNull() || !node.isIntegralNumber()
                || node.intValue() < 1 || node.intValue() > 7) {
            throw new BusinessException(ErrorCode.INVALID_WEEKLY_COUNT);
        }
        int weeklyCount = node.intValue();
        if (Integer.valueOf(weeklyCount).equals(c.getWeeklyCount())) return;
        c.changeWeeklyCount(weeklyCount);
        updated.put("weeklyCount", weeklyCount);
    }

    private void applyParams(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("params")) return;
        JsonNode node = body.get("params");
        if (node.isNull() || !node.isArray()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        RoutineTemplate template = (c.getTemplateId() != null)
                ? catalog.findById(c.getTemplateId()).orElse(null) : null;
        Map<String, Object> given = new LinkedHashMap<>();
        for (JsonNode item : node) {
            if (item.has("key") && item.get("key").isString()) {
                given.put(item.get("key").stringValue(),
                        item.has("value") && !item.get("value").isNull() ? item.get("value").stringValue() : null);
            }
        }
        if (template == null) {
            List<DraftView.DraftParam> specs = new ArrayList<>();
            given.forEach((k, v) -> specs.add(new DraftView.DraftParam(
                    k, v == null ? null : String.valueOf(v), null, null, null, null, null)));
            c.replaceParams(given, specs);
        } else {
            Map<String, Object> values = new LinkedHashMap<>();
            List<DraftView.DraftParam> specs = new ArrayList<>();
            List<String> known = new ArrayList<>();
            for (ParamSpec spec : template.paramSpecs()) {
                known.add(spec.key());
                Object raw = given.get(spec.key());
                Object value;
                if (raw != null) {
                    try {
                        value = spec.validate(raw);
                    } catch (RuntimeException e) {
                        throw new BusinessException(ErrorCode.INVALID_ROUTINE_PARAM);
                    }
                } else {
                    value = spec.defaultValue();
                }
                if (value != null) values.put(spec.key(), String.valueOf(value));
                specs.add(new DraftView.DraftParam(spec.key(),
                        value == null ? null : String.valueOf(value),
                        spec.defaultValue() == null ? null : String.valueOf(spec.defaultValue()),
                        spec.kind().name(), spec.unit(), spec.min(), spec.max()));
            }
            for (String key : given.keySet()) {
                if (!known.contains(key)) throw new BusinessException(ErrorCode.INVALID_ROUTINE_PARAM);
            }
            c.replaceParams(values, specs);
        }
        updated.put("params", given);
    }

    private void applyVerification(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("verification")) return;
        JsonNode node = body.get("verification");
        if (node.isNull() || !node.isObject() || !node.has("type"))
            throw new BusinessException(ErrorCode.ROUTINE_METHOD_REQUIRED);
        SelectedMethod method;
        try {
            method = SelectedMethod.valueOf(node.get("type").stringValue());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ROUTINE_METHOD_REQUIRED);
        }
        SelectedMethod current = c.getVerificationConfig().selectedMethod();
        if (method == current) return;
        RoutineTemplate template = (c.getTemplateId() != null)
                ? catalog.findById(c.getTemplateId()).orElse(null) : null;
        if (method == SelectedMethod.AUTO) {
            // 수동 방(자동 불가 루틴 포함)의 AUTO 전환은 불가 — 단방향(AUTO→MANUAL)만 허용
            throw new BusinessException(ErrorCode.ROUTINE_AUTO_NOT_SUPPORTED);
        }
        c.changeVerification(VerificationConfig.manual(template));
        updated.put("verification", Map.of("type", "MANUAL"));
    }

    private void applyWatcher(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("penalties")) return;
        JsonNode node = body.get("penalties");
        if (node.isNull() || !node.isObject()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        if (!node.has("watcher")) return;         // score·groupShare 는 서버 고정 — 무시
        JsonNode watcher = node.get("watcher");
        if (watcher.isNull() || !watcher.isBoolean()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        boolean value = watcher.booleanValue();
        if (c.getPenalties() != null && c.getPenalties().watcher() == value) return;
        c.changeWatcherPenalty(value);
        updated.put("penalties", Map.of("watcher", value));
    }

    // ===== 헬퍼 =====

    private Challenge load(UUID challengeId) {
        return challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    private void ensureOwner(Challenge c, UUID userId) {
        if (!c.isOwner(userId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
    }

    private void rejectNotEditable(Challenge c) {
        // 클라 안내용으로 지금 수정 가능한 필드 목록을 reason 에 담는다(계약: 응답에 editableFields 포함)
        throw new BusinessException(ErrorCode.CHALLENGE_NOT_EDITABLE, String.join(",", editableFields(c)));
    }

    private List<String> editableFields(Challenge c) {
        boolean fullEditable = c.getStatus() == ChallengeStatus.UPCOMING && c.getParticipantCount() <= 1;
        return fullEditable ? FULL_EDITABLE : LIMITED_EDITABLE;
    }

    private DraftView.Verification verificationView(Challenge c) {
        VerificationConfig config = c.getVerificationConfig();
        boolean auto = config != null && config.selectedMethod() == SelectedMethod.AUTO;
        String method = auto
                ? catalog.findById(c.getTemplateId()).map(RoutineTemplate::getVerificationMethod).orElse(null)
                : "SELF_CHECK";
        return new DraftView.Verification(
                auto ? "AUTO" : "MANUAL", method,
                (config != null && config.requiredPermissions() != null)
                        ? config.requiredPermissions() : List.of());
    }
}
