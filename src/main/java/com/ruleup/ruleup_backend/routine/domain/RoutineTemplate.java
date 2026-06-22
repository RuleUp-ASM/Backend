package com.ruleup.ruleup_backend.routine.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 루틴 템플릿(지식베이스). 운영이 시드/관리하는 읽기 전용 카탈로그라 수정 메서드가 없다.
 *
 * 자동 인증이 불가능한 루틴은 auto_* 가 전부 NULL 이고 hasAuto()=false 다.
 * (구 has_auto·default_method STORED 생성컬럼은 제거 → Java 계산 메서드로 대체)
 * 인증 방식·필요 권한·신호 출처는 전부 이 테이블이 "진실"이며,
 * LLM 은 어떤 템플릿인지 고르고 목표값만 뽑을 뿐 이 값들을 만들지 않는다(신뢰 경계).
 */
@Entity
@Table(name = "RoutineTemplate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineTemplate {

    @Id
    @Column(name = "id")
    private Long id;                                 // BIGINT auto_increment (정적 카탈로그, 앱 insert 없음)

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private RoutineCategory category;

    // ===== 자동 인증 옵션 (자동 불가 루틴은 전부 null) =====
    @Enumerated(EnumType.STRING)
    @Column(name = "autoVerificationType")
    private VerificationType autoVerificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "autoSignalSource")
    private SignalSource autoSignalSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "autoWearableReq")
    private WearableRequirement autoWearableReq;

    @Column(name = "autoExternalService", length = 40)
    private String autoExternalService;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "autoRequiredPermissions")
    private List<String> autoRequiredPermissions = new ArrayList<>();

    // ===== 수동 인증 옵션 (항상 존재) =====
    @Enumerated(EnumType.STRING)
    @Column(name = "manualSignalSource", nullable = false)
    private SignalSource manualSignalSource;

    // ===== 목표 파라미터 정의 =====
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "paramSchema")
    private Map<String, Object> paramSchema = new LinkedHashMap<>();

    @Column(name = "rationale", length = 255)
    private String rationale;

    @Column(name = "verificationMethod", length = 20)
    private String verificationMethod;

    /**
     * paramSchema(JSON) → ParamSpec 목록. 순서 보존(LinkedHashMap).
     * 잘못된 항목(맵이 아님)은 건너뛴다.
     */
    @SuppressWarnings("unchecked")
    public List<ParamSpec> paramSpecs() {
        List<ParamSpec> specs = new ArrayList<>();
        if (paramSchema == null) return specs;
        for (var entry : paramSchema.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> spec) {
                specs.add(ParamSpec.parse(entry.getKey(), (Map<String, Object>) spec));
            }
        }
        return specs;
    }

    // ===== 구 생성컬럼(has_auto·default_method) → Java 계산으로 대체 =====

    /** 자동 옵션 보유 여부 (구 has_auto 생성컬럼). autoVerificationType 존재 == 자동 가능. */
    public boolean supportsAuto() {
        return autoVerificationType != null;
    }

    /** 기본 선택 인증 방식 (구 default_method 생성컬럼): 자동 가능하면 AUTO, 아니면 MANUAL. */
    public SelectedMethod defaultMethod() {
        return supportsAuto() ? SelectedMethod.AUTO : SelectedMethod.MANUAL;
    }
}