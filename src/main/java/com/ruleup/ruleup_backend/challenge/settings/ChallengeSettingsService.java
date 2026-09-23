package com.ruleup.ruleup_backend.challenge.settings;

import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUpload;
import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUploadRepository;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
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
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
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
 *    body 에 없는 필드는 변경하지 않는다. 설명·이미지 삭제와 무제한 정원·기간은 null 로 표현한다.
 *  - 제목·설명·이미지 변경분은 재심사하며 반복 거부만으로 수정 잠금을 걸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeSettingsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 탐색의 노출 후보·필터·정렬을 정하는 값들. 이 중 하나라도 바뀌면 투영을 다시 만든다. */
    private static final java.util.Set<String> EXPLORE_FIELDS = java.util.Set.of(
            "visibility", "mode", "category", "verification", "minTier", "capacity", "period");


    /** 시작 전 + 방장 혼자일 때 수정 가능한 전체 필드(카테고리 제외 — 어떤 상황에도 불변). */
    private static final List<String> FULL_EDITABLE = List.of(
            "title", "description", "imageUrl", "capacity", "mode", "visibility",
            "rankingVisible", "minTier", "period", "weeklyCount", "params", "verification", "penalties.watcher");

    /** 참여자 발생·시작 이후에도 수정 가능한 필드. */
    private static final List<String> LIMITED_EDITABLE = List.of(
            "title", "description", "capacity", "imageUrl");

    private final ChallengeRepository challengeRepository;
    private final com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository memberRepository;
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
                        new DraftView.Period(c.getStartDate().toString(), c.getEndDate() == null ? null : c.getEndDate().toString()),
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

        if (c.getStatus() == ChallengeStatus.COMPLETED) rejectNotEditable(c);

        // version 필수 + 일치(그 사이 수정·가입이 있었으면 재조회 유도)
        if (body == null || !body.has("version") || !body.get("version").isNumber())
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        if (body.get("version").intValue() != c.getVersion())
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);

        Instant now = Instant.now();

        // 잠금 범위: 카테고리는 항상 불가, 방 성격 항목은 시작 전+혼자일 때만.
        // 여기는 challenges 행을 <b>쓰기 잠금</b>으로 들고 있으므로, 같은 방의 가입과
        // 직렬화된다 — 아래에서 센 수는 이 트랜잭션 동안 늘지 않는다.
        boolean fullEditable = aloneAndUpcoming(c);
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
        if (fullEditable) applyMode(c, body, updated);
        applyCapacity(c, body, updated);

        if (fullEditable) {
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
        if (updated.containsKey("title") || updated.containsKey("description") || updated.containsKey("imageUrl")) {
            c.refreshModerationPending(now);
        }
        if (c.hasPendingModeration() && (updated.containsKey("title") || updated.containsKey("description") || updated.containsKey("imageUrl"))) {
            eventPublisher.publishEvent(new ChallengeModerationRequested(c.getId()));
        }
        // 탐색 노출·필터를 정하는 값이 바뀌었으면 파생 인덱스를 <b>커밋 직후</b> 다시 만든다.
        // 5분 보정만 믿으면 비공개→공개로 바꾼 방이 그동안 목록에 안 뜨고, AUTO→MANUAL 로
        // 바꾼 방은 그동안 옛 필터 결과에 뜬다 — 사용자에게는 설정이 안 먹은 것으로 보인다.
        if (updated.keySet().stream().anyMatch(EXPLORE_FIELDS::contains)) {
            eventPublisher.publishEvent(new com.ruleup.ruleup_backend.challenge.explore.ChallengeExploreProjectionRequested(c.getId()));
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
        if (!node.isNull() && !node.isString()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        String description = node.isNull() || node.stringValue().isBlank() ? null : node.stringValue();
        if (description != null && description.length() > 200) throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);
        if (java.util.Objects.equals(description, c.getDescription())) return;
        c.changeDescription(description);
        if (description == null) c.clearDescriptionModeration();
        else c.markDescriptionInReview();
        updated.put("description", description);
        moderation.put("description", c.getModerationDescription().name());
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

    /**
     * 정원은 {@link com.ruleup.ruleup_backend.challenge.domain.ChallengeCapacity#CHOICES} 중 하나
     * 또는 null(무제한)이다. 현재 ACTIVE 인원 미만으로 줄일 수 없다.
     *
     * <p>생성과 <b>같은 집합</b>을 쓴다. 수정만 사이 값을 받으면 생성으로 못 만드는 크기의 방을
     * 수정으로 만들 수 있다.
     */
    private void applyCapacity(Challenge c, JsonNode body, Map<String, Object> updated) {
        if (!body.has("capacity")) return;
        JsonNode node = body.get("capacity");
        if (node.isNull()) {                       // 무제한으로 전환 — 줄이는 게 아니라 푸는 것이라 현재 인원과 무관
            if (!c.isGroup()) return;              // 솔로는 정원 1 고정
            c.changeMaxParticipants(null);
            updated.put("capacity", null);
            return;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        int capacity = com.ruleup.ruleup_backend.challenge.domain.ChallengeCapacity
                .validateGroup(node.intValue());
        // 비교 대상은 <b>원천</b>이다. 표시용 participant_count 는 커밋 뒤 비동기로 채워지므로
        // 그 값으로 판정하면 「방금 들어온 인원 아래로 정원을 줄이는」 요청이 통과할 수 있다.
        long active = memberRepository.countByChallengeIdAndStatus(
                c.getId(), com.ruleup.ruleup_backend.challenge.domain.MemberStatus.ACTIVE);
        if (capacity < active)
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
        if (!body.has("visibility")) return;   // mode 전환 시 이미 정규화됨
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
        if (!body.has("rankingVisible")) return;
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
            end = node.get("end").isNull() ? null : LocalDate.parse(node.get("end").stringValue());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
        if ((end != null && end.isBefore(start)) || start.isBefore(LocalDate.now(KST)))
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        c.changePeriod(start, end);
        updated.put("period", new DraftView.Period(start.toString(), end == null ? null : end.toString()));
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
        return c.getStatus() == ChallengeStatus.COMPLETED ? List.of()
                : aloneAndUpcoming(c) ? FULL_EDITABLE : LIMITED_EDITABLE;
    }

    /**
     * 「아직 시작 전이고 방장 혼자인가」 — <b>멤버십 행을 직접 센다.</b>
     *
     * <p>{@code challenges.participant_count} 를 쓰면 안 된다. 가입 트랜잭션은 그 열을 갱신하지
     * 않고 커밋 뒤 원천에서 다시 세어 채우는 <b>표시용 작업본</b>이다(탐색 백엔드 3-1). 그
     * 재계산이 늦거나 유실된 구간에는 실제 참여자가 둘 이상인데도 값이 1 로 남아 있고, 그
     * 값으로 권한을 판정하면 <b>이미 사람이 들어온 방의 모드·공개 범위·기간·인증 방식이 열린다</b> —
     * 들어온 사람이 약속과 다른 방에 남는다. 권한은 파생값이 아니라 원천으로 판정해야 한다.
     *
     * <p>세는 비용은 {@code (challenge_id, status)} 커버링 인덱스가 받는다(V53).
     */
    private boolean aloneAndUpcoming(Challenge c) {
        return c.getStatus() == ChallengeStatus.UPCOMING
                && memberRepository.countByChallengeIdAndStatus(c.getId(), MemberStatus.ACTIVE) <= 1;
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
