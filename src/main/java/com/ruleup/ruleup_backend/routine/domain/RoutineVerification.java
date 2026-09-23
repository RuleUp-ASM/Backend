package com.ruleup.ruleup_backend.routine.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * 루틴 인증 정의 (RoutineVerification 테이블). RoutineTemplate과 1:1 — templateId가 PK이자 FK.
 *
 * <p>인증 방식·신호 출처·필요 권한·method는 전부 여기가 "진실"이다(신뢰 경계).
 * RoutineTemplate에서 위임 게터로 접근하므로 호출부는 이 분리를 몰라도 된다.
 * 정적 카탈로그라 시드/운영이 관리하며 앱에서 insert 하지 않는다(읽기 전용).
 */
@Entity
@Table(name = "RoutineVerification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineVerification {

    @Id
    @Column(name = "templateId")
    private Long templateId;

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

    // ===== 인증 method (신호 + name/rationale 키워드 규칙으로 백필) =====
    @Column(name = "verificationMethod", length = 20)
    private String verificationMethod;
}
