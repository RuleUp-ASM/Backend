package com.ruleup.ruleup_backend.challenge.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Challenge extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 방장(OWNER) 식별자. 위임(§7-2)으로 바뀔 수 있어 updatable(자동 언박싱 아님).
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "owner_id", nullable = false)
    private UUID creatorId;

    @Column(name = "title", nullable = false, length = 30)
    private String title;

    /** AI 임시 제목 — 심사 중·거부·신고 시 대체 표시. draft 행에서 서버가 복사(클라 전송 불가). */
    @Column(name = "ai_title", length = 30)
    private String aiTitle;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private ParticipationType participationType;

    @Column(name = "min_manner_temperature", precision = 4, scale = 1)
    private BigDecimal minMannerTemperature;

    // 최대 참여 인원(정원). SOLO는 1 고정, GROUP은 방장이 지정(§3·§4). 방장만 수정, 현재 인원 미만 축소 불가.
    @Column(name = "capacity")
    private Integer maxParticipants;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "repeat_days", nullable = false)
    private List<String> repeatDays = new ArrayList<>();

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // ===== 루틴(인증) =====
    @Column(name = "template_id")
    private Long templateId;                          // 매칭된 루틴 템플릿(직접 입력이면 null)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_config", nullable = false)
    private VerificationConfig verificationConfig;    // 인증 방식 스냅샷

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private Map<String, Object> params = new LinkedHashMap<>();   // 목표값(예: {"distance_km":5})

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "penalty_config", nullable = false)
    private PenaltyConfig penalty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reward_config", nullable = false)
    private RewardConfig reward;

    @Enumerated(EnumType.STRING)
    @Column(name = "anonymity", nullable = false)
    private Anonymity anonymity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChallengeStatus status;

    // ===== 신규 계약 필드 (챌린지 생성·라이프사이클 스펙) =====

    /** 설정 버전 — 수정·가입·탈퇴 등 수정 가능 범위가 바뀔 수 있는 변화마다 +1 (PATCH 낙관 잠금). */
    @Column(name = "version", nullable = false)
    private int version;

    /** 최소 입장 티어(표시 티어 기준). 구 매너온도 게이트 대체. */
    @Enumerated(EnumType.STRING)
    @Column(name = "min_tier")
    private com.ruleup.ruleup_backend.score.domain.Tier minTier;

    /** 그룹 공개 범위(PUBLIC/PRIVATE) — 솔로는 null. */
    @Column(name = "visibility", length = 10)
    private String visibility;

    /** 솔로 랭킹 노출 여부 — 그룹은 null. */
    @Column(name = "ranking_visible")
    private Boolean rankingVisible;

    /** 목표값 스펙 배열(확인·수정 폼 복원용) — [{key,value,defaultValue,kind,unit,min,max}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_specs")
    private List<com.ruleup.ruleup_backend.challenge.draft.DraftView.DraftParam> paramSpecs;

    /** 패널티(서버 강제) — {score, groupShare, watcher}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "penalties")
    private ChallengePenalties penalties;

    // ===== 항목별 심사 상태 + 반복 거부 잠금 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_title", nullable = false)
    private TargetModerationStatus moderationTitle = TargetModerationStatus.EXEMPT;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_description", nullable = false)
    private TargetModerationStatus moderationDescription = TargetModerationStatus.EXEMPT;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_image", nullable = false)
    private TargetModerationStatus moderationImage = TargetModerationStatus.NONE;

    @Column(name = "moderation_locked_until")
    private Instant moderationLockedUntil;          // 1시간 3회 거부 → 1시간 수정 잠금

    @Column(name = "moderation_reject_count", nullable = false)
    private int moderationRejectCount;

    @Column(name = "moderation_reject_window_start")
    private Instant moderationRejectWindowStart;

    // ===== 모더레이션(가시성) 게이트 (§5.1) — lifecycle status 와 독립 축 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false)
    private ChallengeModerationStatus moderationStatus;

    @Column(name = "moderation_decided_at")
    private Instant moderationDecidedAt;       // APPROVED/REJECTED 확정 시각

    @Column(name = "fix_deadline")
    private Instant fixDeadline;               // REJECTED 1시간 수정창 마감(§5.1)

    @Column(name = "ai_assisted", nullable = false)
    private boolean aiAssisted;

    @Column(name = "participant_count", nullable = false)
    private int participantCount;

    // ===== 탐색 역정규화(탐색 스펙 §4) — 배치가 유지, 질의 시점 집계 없음 =====
    @Column(name = "trending_score", nullable = false)
    private double trendingScore;          // 최근 24h 참여 지수감쇠 합(§2.1, 10분 배치)

    @Column(name = "fail_count", nullable = false)
    private int failCount;                 // 방 확정 실패 인원(§3.2.4, 배치)

    @Column(name = "verification_type", length = 10)
    private String verificationType;       // AUTO / MANUAL — verificationConfig.selectedMethod 승격(정렬·필터용)

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private static LocalDate deriveEndDate(LocalDate start, int durationDays) {
        return start.plusDays((long) durationDays - 1);
    }

    public static Challenge create(UUID creatorId, String title, String description, String imageUrl,
                                   String category, ParticipationType participationType,
                                   BigDecimal minMannerTemperature, Integer maxParticipants, List<String> repeatDays,
                                   int durationDays, LocalDate startDate,
                                   Long templateId, VerificationConfig verificationConfig,
                                   Map<String, Object> params,
                                   PenaltyConfig penalty, RewardConfig reward,
                                   Anonymity anonymity, boolean aiAssisted) {
        Challenge c = new Challenge();
        c.id = UuidGenerator.generate();
        c.creatorId = creatorId;
        c.title = title;
        c.description = description;
        c.imageUrl = imageUrl;
        c.category = category;
        c.participationType = participationType;
        c.minMannerTemperature = (participationType == ParticipationType.GROUP) ? minMannerTemperature : null;
        // SOLO는 정원 1 고정, GROUP은 방장이 지정한 값(생성 서비스에서 필수 검증). Integer 유지(자동 언박싱 NPE 방지).
        c.maxParticipants = (participationType == ParticipationType.SOLO) ? Integer.valueOf(1) : maxParticipants;
        c.repeatDays = (repeatDays != null) ? new ArrayList<>(repeatDays) : new ArrayList<>();
        c.durationDays = durationDays;
        c.startDate = startDate;
        c.endDate = deriveEndDate(startDate, durationDays);
        c.templateId = templateId;
        c.verificationConfig = verificationConfig;
        c.params = (params != null) ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
        c.penalty = penalty;
        c.reward = reward;
        c.anonymity = anonymity;
        c.status = ChallengeStatus.UPCOMING;
        // 가시성 게이트는 "이미지" 기준(§3-3): 이미지가 있으면 비동기 SafeSearch 검수 후 공개(PENDING_REVIEW),
        // 이미지가 없으면 검수 대상이 없어 즉시 모집 가능(NONE). 이름(제목/설명)은 별도 검수하지 않는다
        // (LLM draft Step2가 생성 시점에 대체 — 이름 모더레이션 non-goal).
        boolean hasImage = imageUrl != null && !imageUrl.isBlank();
        c.moderationStatus = hasImage
                ? ChallengeModerationStatus.PENDING_REVIEW
                : ChallengeModerationStatus.NONE;
        c.aiAssisted = aiAssisted;
        c.participantCount = 0;
        // 탐색 정렬·필터용 승격 컬럼(인증 방식). verificationConfig 스냅샷의 selectedMethod 를 그대로 반영.
        c.verificationType = (verificationConfig != null && verificationConfig.selectedMethod() != null)
                ? verificationConfig.selectedMethod().name() : null;
        c.trendingScore = 0.0;
        c.failCount = 0;
        return c;
    }

    /**
     * 신규 계약 생성(챌린지 생성·라이프사이클 스펙) — draft 원본 대조를 마친 확정값으로 조립한다.
     *  - ai_title 은 draft 행에서 서버가 복사(대체 표시용), penalties 는 서버 강제값.
     *  - 구 단일 moderationStatus 게이트는 사용하지 않는다(NONE 고정) — 항목별 상태가 대체.
     *  - repeat_days·duration_days·penalty/reward JSON 은 인증·점수 모듈 호환용 내부 값(계약 미노출).
     */
    public static Challenge createFromDraft(UUID ownerId, String title, String aiTitle, String description,
                                            String imageUrl, String category, ParticipationType mode,
                                            String visibility, Boolean rankingVisible, Integer capacity,
                                            com.ruleup.ruleup_backend.score.domain.Tier minTier,
                                            LocalDate startDate, LocalDate endDate, List<String> repeatDays,
                                            Long templateId, VerificationConfig verificationConfig,
                                            Map<String, Object> params,
                                            List<com.ruleup.ruleup_backend.challenge.draft.DraftView.DraftParam> paramSpecs,
                                            ChallengePenalties penalties,
                                            TargetModerationStatus moderationTitle,
                                            TargetModerationStatus moderationDescription,
                                            TargetModerationStatus moderationImage) {
        Challenge c = new Challenge();
        c.id = UuidGenerator.generate();
        c.creatorId = ownerId;
        c.title = title;
        c.aiTitle = aiTitle;
        c.description = description;
        c.imageUrl = imageUrl;
        c.category = category;
        c.participationType = mode;
        c.visibility = (mode == ParticipationType.GROUP) ? visibility : null;
        c.rankingVisible = (mode == ParticipationType.SOLO)
                ? (rankingVisible != null ? rankingVisible : Boolean.TRUE) : null;
        c.maxParticipants = (mode == ParticipationType.SOLO) ? Integer.valueOf(1) : capacity;
        c.minTier = minTier;
        c.startDate = startDate;
        c.endDate = endDate;
        c.durationDays = (int) (endDate.toEpochDay() - startDate.toEpochDay()) + 1;
        c.repeatDays = (repeatDays != null && !repeatDays.isEmpty())
                ? new ArrayList<>(repeatDays)
                : new ArrayList<>(List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"));
        c.templateId = templateId;
        c.verificationConfig = verificationConfig;
        c.params = (params != null) ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
        c.paramSpecs = (paramSpecs != null) ? new ArrayList<>(paramSpecs) : new ArrayList<>();
        c.penalties = penalties;
        // 인증·점수 모듈 호환용 레거시 JSON(계약 미노출) — 점수 반영 여부는 penalties.score 가 진실.
        c.penalty = new PenaltyConfig(BigDecimal.ONE, new PenaltyConfig.SnsShare(false, null), false);
        c.reward = new RewardConfig(BigDecimal.ONE);
        c.anonymity = Anonymity.REAL;
        c.status = ChallengeStatus.UPCOMING;
        c.version = 0;
        c.moderationTitle = moderationTitle;
        c.moderationDescription = moderationDescription;
        c.moderationImage = moderationImage;
        c.moderationStatus = ChallengeModerationStatus.NONE;   // 구 게이트 미사용
        c.aiAssisted = true;
        c.participantCount = 0;
        c.verificationType = (verificationConfig != null && verificationConfig.selectedMethod() != null)
                ? verificationConfig.selectedMethod().name() : null;
        c.trendingScore = 0.0;
        c.failCount = 0;
        return c;
    }

    /** 설정 버전 +1 — 수정·가입·탈퇴·강퇴 등 수정 가능 범위가 바뀔 수 있는 모든 변화에서 호출. */
    public void bumpVersion() { this.version++; }

    // ===== 탐색 역정규화 갱신(배치 전용) =====
    public void applyTrendingScore(double score) { this.trendingScore = Math.max(0.0, score); }
    public void applyFailCount(int count) { this.failCount = Math.max(0, count); }

    public boolean isUpcoming() { return status.isUpcoming(); }

    /**
     * 시작일 도달 → 진행 개시(§2). UPCOMING 일 때만 ACTIVE 로 전환(멱등·방어).
     * ACTIVE 가 되어야 인증 sync(§3.1)가 평가 대상으로 삼는다(VerificationSyncService).
     */
    public void activate() {
        if (this.status == ChallengeStatus.UPCOMING) {
            this.status = ChallengeStatus.ACTIVE;
        }
    }

    /**
     * 종료일 경과 → 종료(§5.5). ACTIVE 일 때만 COMPLETED 로 전환(멱등·방어).
     * 완주율 집계·정산은 인증(VF)/평판 스펙 소관 — 여기선 lifecycle 상태만 마감한다.
     */
    public void complete() {
        if (this.status == ChallengeStatus.ACTIVE) {
            this.status = ChallengeStatus.COMPLETED;
        }
    }

    // ===== 모더레이션 게이트 (§3-3) =====
    public boolean isApproved() { return moderationStatus == ChallengeModerationStatus.APPROVED; }

    /** 모집·노출 허용 상태: 이미지 없음(NONE) 또는 이미지 검수 통과(APPROVED). */
    public boolean isModerationCleared() { return moderationStatus.isPublicVisible(); }

    /** 조회 가시성: OWNER는 항상, 그 외는 모더레이션 통과(NONE/APPROVED)만(아니면 호출부에서 404 처리). */
    public boolean isVisibleTo(UUID viewerId) { return isOwner(viewerId) || isModerationCleared(); }

    /** 검수 통과 → 공개·가입 허용. */
    public void approveModeration(Instant at) {
        this.moderationStatus = ChallengeModerationStatus.APPROVED;
        this.moderationDecidedAt = at;
        this.fixDeadline = null;
    }

    /** 검수 거절 → 1시간 수정창 부여(§5.1). 미수정·경과 시 배치가 영구 닫는다. */
    public void rejectModeration(Instant at, Instant fixDeadline) {
        this.moderationStatus = ChallengeModerationStatus.REJECTED;
        this.moderationDecidedAt = at;
        this.fixDeadline = fixDeadline;
    }

    /** title/imageUrl 변경 시 재검수를 위해 PENDING_REVIEW로 되돌린다(§5.1). */
    public void resubmitModeration() {
        this.moderationStatus = ChallengeModerationStatus.PENDING_REVIEW;
        this.moderationDecidedAt = null;
        this.fixDeadline = null;
    }

    public void changeTitle(String v)        { if (v != null) this.title = v; }
    public void changeImageUrl(String v)     { if (v != null) this.imageUrl = v; }
    public void changeDescription(String v)  { this.description = v; }
    public void changeCategory(String v)     { if (v != null) this.category = v; }
    public void changeRepeatDays(List<String> v) { if (v != null) this.repeatDays = new ArrayList<>(v); }
    public void changeParams(Map<String, Object> v) { if (v != null) this.params = new LinkedHashMap<>(v); }
    public void changePenalty(PenaltyConfig v) { if (v != null) this.penalty = v; }
    public void changeReward(RewardConfig v)   { if (v != null) this.reward = v; }
    public void changeMinMannerTemperature(BigDecimal v) {
        if (v != null && participationType == ParticipationType.GROUP) this.minMannerTemperature = v;
    }

    /** 최대 참여 인원 변경. SOLO(정원 1 고정)는 무시. GROUP만 반영(축소 하한 검증은 서비스에서). */
    public void changeMaxParticipants(Integer v) {
        if (v != null && participationType == ParticipationType.GROUP) this.maxParticipants = v;
    }

    public void changeSchedule(Integer durationDays, LocalDate startDate) {
        if (durationDays != null) this.durationDays = durationDays;
        if (startDate != null)    this.startDate = startDate;
        if (durationDays != null || startDate != null) {
            this.endDate = deriveEndDate(this.startDate, this.durationDays);
        }
    }

    /** 방장 위임 성립(§7-2): creatorId(=OWNER 식별자)를 새 방장으로 교체. 멤버 role swap은 호출부가 함께 수행. */
    public void transferOwnership(UUID newOwnerUserId) { this.creatorId = newOwnerUserId; }

    public void increaseParticipantCount() { this.participantCount++; }
    public void decreaseParticipantCount() { if (this.participantCount > 0) this.participantCount--; }
    public void softDelete() { this.deletedAt = Instant.now(); }
    public boolean isOwner(UUID userId) { return this.creatorId.equals(userId); }
    public boolean isGroup() { return participationType == ParticipationType.GROUP; }
}