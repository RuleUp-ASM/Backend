package com.ruleup.ruleup_backend.challenge.creation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengePenalties;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.TargetModerationStatus;
import com.ruleup.ruleup_backend.challenge.draft.ChallengeDraft;
import com.ruleup.ruleup_backend.challenge.draft.ChallengeDraftRepository;
import com.ruleup.ruleup_backend.challenge.draft.DraftView;
import com.ruleup.ruleup_backend.challenge.dto.CreateChallengeRequest;
import com.ruleup.ruleup_backend.challenge.dto.CreateChallengeResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationRequested;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 챌린지 최종 생성(POST /api/v1/challenges) — API 명세 「챌린지 최종 생성」.
 *
 *  판정 순서:
 *   ① Idempotency-Key 필수 → 기존 키 조회(동일 본문 재응답 / 다른 본문 409)
 *   ② draftId 원본 조회(본인 소유·24h) — 무효 400 / 만료 400
 *   ③ 값 검증: 카테고리 불변, AUTO→MANUAL 단방향, 정원, minTier ≤ 표시 티어, 기간, 목표값 스펙
 *   ④ 이미지 소유 검증(업로드 API 발급 + 본인) — 통과 시 등록 마킹
 *   ⑤ 심사 대상 서버 판정: 요청 title/description 을 draft 원본과 대조(자가 신고 없음)
 *   ⑥ 저장 + 생성자 OWNER 등록 + 멱등 키 스냅샷 저장
 *
 *  서버 강제: penalties.score(자동=ON)/groupShare(그룹=ON) 클라 값 무시, ai_title 은 draft 에서 복사.
 */
@Service
@RequiredArgsConstructor
public class ChallengeCreationService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeCreationService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CAPACITY_MAX = 10_000;
    private static final int TITLE_MAX = 30;
    private static final int DESCRIPTION_MAX = 200;

    /** Spring Boot 4(Jackson 3) 환경이라 fasterxml ObjectMapper 는 빈이 아님 — 해시·스냅샷 직렬화 전용. */
    private static final ObjectMapper OM = new ObjectMapper();

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeDraftRepository draftRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ChallengeImageUploadRepository imageUploadRepository;
    private final UserChallengeCounterRepository counterRepository;
    private final RoutineCatalog catalog;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CreateChallengeResponse create(UUID userId, String idempotencyKey, CreateChallengeRequest req) {
        // ① 멱등 키
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        String requestHash = sha256(canonicalJson(req));
        // 키 행을 먼저 선점하고 그 행을 잠근 채로 판정한다. 동시 재시도는 여기서 직렬화되며,
        // 뒤에 온 요청은 앞선 요청의 커밋을 기다렸다가 확정된 스냅샷을 그대로 재응답한다.
        idempotencyKeyRepository.reserve(userId, idempotencyKey, requestHash);
        IdempotencyKey keyRow = idempotencyKeyRepository.lockRow(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("멱등 키 선점 실패"));
        if (!keyRow.sameRequest(requestHash))
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        if (!keyRow.isPending())
            return readSnapshot(keyRow.getResponseSnapshot());

        // ② draft 원본 (본인 소유만 — 타인 draftId 는 존재를 숨겨 NOT_FOUND)
        ChallengeDraft draft = draftRepository.findByIdAndUserId(parseUuid(req.draftId()), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DRAFT_NOT_FOUND));
        if (draft.isExpired(Instant.now()))
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED);
        DraftView original = draft.getPayload().draft();

        // ③ 값 검증
        String title = validateTitle(req.title());
        String description = validateDescription(req.description());
        String category = validateCategoryImmutable(req.category(), original.category());
        ParticipationType mode = parseMode(req.mode());
        Integer capacity = validateCapacity(mode, req.capacity());
        Tier minTier = validateMinTier(userId, req.minTier());
        LocalDate[] period = validatePeriod(req.period());

        RoutineTemplate template = (draft.getTemplateId() != null)
                ? catalog.findById(draft.getTemplateId()).orElse(null) : null;
        VerificationConfig verification = resolveVerification(req.verification(), original, template);
        boolean auto = verification.selectedMethod() == SelectedMethod.AUTO;

        ParamsResult params = validateParams(template, req.params(), original.params());

        // ④ 이미지 소유 검증
        ChallengeImageUpload imageUpload = validateImage(userId, req.imageUrl());

        // ⑤ 심사 대상 서버 판정 — draft 원본 대조(클라 자가 신고 없음)
        TargetModerationStatus moderationTitle = title.equals(draft.getTitle())
                ? TargetModerationStatus.EXEMPT : TargetModerationStatus.IN_REVIEW;
        TargetModerationStatus moderationDescription = Objects.equals(description, draft.getDescription())
                ? TargetModerationStatus.EXEMPT : TargetModerationStatus.IN_REVIEW;
        TargetModerationStatus moderationImage = (req.imageUrl() == null)
                ? TargetModerationStatus.NONE : TargetModerationStatus.IN_REVIEW;

        // ⑥ 저장 — 서버 강제 패널티(선택 가능한 것은 watcher 뿐)
        ChallengePenalties penalties = ChallengePenalties.enforced(
                auto, mode == ParticipationType.GROUP,
                req.penalties() != null ? req.penalties().watcher() : null);

        Challenge challenge = Challenge.createFromDraft(
                userId, title, draft.getTitle(), description, req.imageUrl(),
                category, mode, resolveVisibility(mode, req.visibility()), req.rankingVisible(),
                capacity, minTier, period[0], period[1],
                draft.getPayload().repeatDays(), draft.getTemplateId(),
                verification, params.valueMap(), params.specs(), penalties,
                moderationTitle, moderationDescription, moderationImage);
        challengeRepository.save(challenge);

        memberRepository.save(ChallengeMember.owner(challenge.getId(), userId));
        challenge.increaseParticipantCount();
        // 생성자도 그 방의 ACTIVE 멤버 → 동시 참여 3개 카운터에 포함시킨다(가입 게이트와 같은 대장을 쓴다).
        counterRepository.ensureRow(userId);
        counterRepository.increment(userId);

        if (imageUpload != null) imageUpload.markRegistered(Instant.now());

        // 심사 대상(수정된 텍스트·이미지)이 있으면 커밋 후 비동기 심사 — 생성 응답은 기다리지 않는다
        if (moderationTitle == TargetModerationStatus.IN_REVIEW
                || moderationDescription == TargetModerationStatus.IN_REVIEW
                || moderationImage == TargetModerationStatus.IN_REVIEW) {
            eventPublisher.publishEvent(new ChallengeModerationRequested(challenge.getId()));
        }

        CreateChallengeResponse response = new CreateChallengeResponse(
                challenge.getId().toString(),
                challenge.getStatus().name(),
                new CreateChallengeResponse.Moderation(
                        moderationTitle.name(), moderationDescription.name(), moderationImage.name()),
                new CreateChallengeResponse.Verification(
                        verification.selectedMethod().name(),
                        auto ? template.getVerificationMethod() : "SELF_CHECK",
                        verification.requiredPermissions()),
                auto,                                            // AUTO 면 개인 인증 설정 진입 필요
                ZonedDateTime.now(KST).toOffsetDateTime().toString());

        keyRow.completeWith(writeSnapshot(response), challenge.getId());
        log.info("challenge_create_result success=true path={} verify_method={} challengeId={}",
                draft.getOrigin(), response.verification().method(), challenge.getId());
        return response;
    }

    // ===== 검증 =====

    private UUID parseUuid(String v) {
        if (v == null) throw new BusinessException(ErrorCode.DRAFT_NOT_FOUND);
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.DRAFT_NOT_FOUND);
        }
    }

    private String validateTitle(String title) {
        if (title == null || title.isBlank()) throw new BusinessException(ErrorCode.TITLE_REQUIRED);
        if (title.length() > TITLE_MAX) throw new BusinessException(ErrorCode.TITLE_TOO_LONG);
        return title;
    }

    private String validateDescription(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX)
            throw new BusinessException(ErrorCode.DESCRIPTION_TOO_LONG);
        return description;
    }

    /** 카테고리는 확인 화면에서도 수정 불가 — draft 원본과 다르면 거부. */
    private String validateCategoryImmutable(String requested, String original) {
        if (requested == null || !requested.equals(original))
            throw new BusinessException(ErrorCode.INVALID_CATEGORY);
        return requested;
    }

    private ParticipationType parseMode(String mode) {
        try {
            return ParticipationType.valueOf(mode);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_PARTICIPATION_TYPE);
        }
    }

    private Integer validateCapacity(ParticipationType mode, Integer capacity) {
        if (mode != ParticipationType.GROUP) return 1;
        if (capacity == null) throw new BusinessException(ErrorCode.CAPACITY_REQUIRED);
        if (capacity < 1 || capacity > CAPACITY_MAX)
            throw new BusinessException(ErrorCode.CAPACITY_OUT_OF_RANGE);
        return capacity;
    }

    /** minTier ≤ 생성자 표시 티어(서버 검증 필수 — 클라 검증만으로는 우회 가능). */
    private Tier validateMinTier(UUID userId, String minTier) {
        Tier owner = scoreSummaryRepository.findById(userId)
                .map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        if (owner == Tier.UNRANKED) owner = Tier.BRONZE;
        if (minTier == null) return owner;
        Tier requested;
        try {
            requested = Tier.valueOf(minTier);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.MIN_TIER_EXCEEDS_OWNER);
        }
        if (requested == Tier.UNRANKED || requested.ordinal() > owner.ordinal())
            throw new BusinessException(ErrorCode.MIN_TIER_EXCEEDS_OWNER);
        return requested;
    }

    private LocalDate[] validatePeriod(CreateChallengeRequest.Period period) {
        if (period == null || period.start() == null || period.end() == null)
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        LocalDate start, end;
        try {
            start = LocalDate.parse(period.start());
            end = LocalDate.parse(period.end());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
        if (end.isBefore(start) || start.isBefore(LocalDate.now(KST)))
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        return new LocalDate[]{start, end};
    }

    /**
     * 인증 방식: 방 단위 — 초안이 AUTO(자동 가능 루틴)면 AUTO 유지 또는 MANUAL 전환 허용.
     * 초안이 MANUAL(자동 불가)인데 AUTO 요청은 거부(역방향 불가).
     */
    private VerificationConfig resolveVerification(CreateChallengeRequest.Verification requested,
                                                   DraftView original, RoutineTemplate template) {
        if (requested == null || requested.type() == null)
            throw new BusinessException(ErrorCode.ROUTINE_METHOD_REQUIRED);
        boolean draftAuto = "AUTO".equals(original.verification().type());
        SelectedMethod method;
        try {
            method = SelectedMethod.valueOf(requested.type());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.ROUTINE_METHOD_REQUIRED);
        }
        if (method == SelectedMethod.AUTO && (!draftAuto || template == null || !template.supportsAuto()))
            throw new BusinessException(ErrorCode.ROUTINE_AUTO_NOT_SUPPORTED);
        return (method == SelectedMethod.AUTO)
                ? VerificationConfig.auto(template)
                : VerificationConfig.manual(template);
    }

    private record ParamsResult(Map<String, Object> valueMap, List<DraftView.DraftParam> specs) {}

    /** 목표값: 템플릿 스펙 기준 검증(사용자 값이라 잘못되면 즉시 400 피드백). */
    private ParamsResult validateParams(RoutineTemplate template,
                                        List<CreateChallengeRequest.Param> requested,
                                        List<DraftView.DraftParam> originalSpecs) {
        Map<String, Object> given = new LinkedHashMap<>();
        if (requested != null) {
            for (CreateChallengeRequest.Param p : requested) {
                if (p != null && p.key() != null) given.put(p.key().trim(), p.value());
            }
        }
        if (template == null) {
            // 자동 불가 루틴(매칭 없음) — 목표값 스펙이 없어 자유 입력을 보관만 한다.
            List<DraftView.DraftParam> specs = new ArrayList<>();
            for (var e : given.entrySet()) {
                specs.add(new DraftView.DraftParam(e.getKey(), asString(e.getValue()), null, null, null, null, null));
            }
            return new ParamsResult(given, specs);
        }
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
            if (value != null) values.put(spec.key(), asString(value));
            specs.add(new DraftView.DraftParam(spec.key(), asString(value), asString(spec.defaultValue()),
                    spec.kind().name(), spec.unit(), spec.min(), spec.max()));
        }
        for (String key : given.keySet()) {
            if (!known.contains(key)) throw new BusinessException(ErrorCode.INVALID_ROUTINE_PARAM);
        }
        return new ParamsResult(values, specs);
    }

    /** 이미지: 업로드 API 가 발급한 본인 소유 URL 만 허용. */
    private ChallengeImageUpload validateImage(UUID userId, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        ChallengeImageUpload upload = imageUploadRepository.findByImageUrl(imageUrl)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_IMAGE_URL));
        if (!upload.ownedBy(userId)) throw new BusinessException(ErrorCode.IMAGE_NOT_OWNED);
        return upload;
    }

    private String resolveVisibility(ParticipationType mode, String visibility) {
        if (mode != ParticipationType.GROUP) return null;
        if (visibility == null || visibility.isBlank()) return "PUBLIC";
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility))
            throw new BusinessException(ErrorCode.INVALID_PARTICIPATION_TYPE);
        return visibility;
    }

    // ===== 멱등 스냅샷 =====

    private String writeSnapshot(CreateChallengeResponse response) {
        try {
            return OM.writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("멱등 응답 스냅샷 직렬화 실패", e);
        }
    }

    private CreateChallengeResponse readSnapshot(String json) {
        try {
            return OM.readValue(json, CreateChallengeResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("멱등 응답 스냅샷 파싱 실패", e);
        }
    }

    private String canonicalJson(CreateChallengeRequest req) {
        try {
            return OM.writeValueAsString(req);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("요청 해시 직렬화 실패", e);
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    private static String asString(Object v) {
        return (v == null) ? null : String.valueOf(v);
    }
}
