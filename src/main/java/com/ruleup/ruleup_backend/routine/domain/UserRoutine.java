package com.ruleup.ruleup_backend.routine.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 사용자가 실제로 만든 루틴.
 *  - 선택한 인증 방식을 "스냅샷"으로 박아둔다 → 나중에 템플릿이 바뀌어도 이 루틴은 그대로 유지.
 *  - 매칭 실패(직접 입력) 시 templateId=null + 수동 인증.
 *  - 생성은 정적 팩토리(auto/manual)로만. 스냅샷 구성 규칙을 한곳에 모은다.
 */
@Entity
@Table(name = "user_routine")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRoutine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // BIGINT AUTO_INCREMENT
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)                          // users.id 와 동일 전략(UUID → CHAR(36))
    @Column(name = "user_id", nullable = false, updatable = false, length = 36)
    private UUID userId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "template_id")
    private Long templateId;                              // 매칭 실패 시 null

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_method", nullable = false)
    private SelectedMethod selectedMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_type", nullable = false)
    private VerificationType verificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_source", nullable = false)
    private SignalSource signalSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "wearable_req", nullable = false)
    private WearableRequirement wearableReq;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_permissions")
    private List<String> requiredPermissions = new ArrayList<>();

    @Column(name = "external_service", length = 40)
    private String externalService;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params")
    private Map<String, Object> params = new LinkedHashMap<>();

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 자동 인증 루틴. 호출 전 template.supportsAuto() 가 보장돼야 한다. */
    public static UserRoutine auto(UUID userId, String title, String description,
                                   RoutineTemplate template, Map<String, Object> params) {
        UserRoutine r = base(userId, title, description, params);
        r.templateId = template.getId();
        r.selectedMethod = SelectedMethod.AUTO;
        r.verificationType = template.getAutoVerificationType();
        r.signalSource = template.getAutoSignalSource();
        r.wearableReq = (template.getAutoWearableReq() != null)
                ? template.getAutoWearableReq() : WearableRequirement.NONE;
        r.requiredPermissions = new ArrayList<>(template.getAutoRequiredPermissions());
        r.externalService = template.getAutoExternalService();
        return r;
    }

    /** 수동 인증 루틴. template 은 null 가능(매칭 실패 = 직접 입력). */
    public static UserRoutine manual(UUID userId, String title, String description,
                                     RoutineTemplate template, Map<String, Object> params) {
        UserRoutine r = base(userId, title, description, params);
        r.templateId = (template != null) ? template.getId() : null;
        r.selectedMethod = SelectedMethod.MANUAL;
        r.verificationType = VerificationType.MANUAL;
        r.signalSource = (template != null) ? template.getManualSignalSource() : SignalSource.PHOTO;
        r.wearableReq = WearableRequirement.NONE;
        r.externalService = null;
        // 사진 인증은 카메라 권한 필요, 그룹 체크는 권한 없음.
        r.requiredPermissions = (r.signalSource == SignalSource.PHOTO)
                ? new ArrayList<>(List.of("CAMERA")) : new ArrayList<>();
        return r;
    }

    private static UserRoutine base(UUID userId, String title, String description,
                                    Map<String, Object> params) {
        UserRoutine r = new UserRoutine();
        r.userId = userId;
        r.title = title;
        r.description = description;
        r.params = (params != null) ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
        return r;
    }
}