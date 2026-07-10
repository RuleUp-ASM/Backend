package com.ruleup.ruleup_backend.reputation.domain;

import com.ruleup.ruleup_backend.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 매너 온도 (ReputationScore 테이블). User와 1:1, PK를 User와 공유(@MapsId).
 *
 * <p>온도(mannerTemperature) = 밴드 매핑 T=f(V+B). 일일 배치({@code ReputationCalculationService})가
 * 볼륨 항 V(volumeIndex)·연차 B(tenureBonus)·자격일(qualifyingDays)을 하루 1스텝 갱신하고 온도를 재계산한다.
 * 신규 가입자는 36.5로 시작(V=B=0). 계산 로직은 온도 계산 테크스펙 V1 참고.
 */
@Entity
@Table(name = "ReputationScore")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReputationScore {

    /** 신규 가입자의 시작 온도 (모든 사용자 공통 기본값) */
    public static final BigDecimal INITIAL_TEMPERATURE = new BigDecimal("36.5");

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)  // userId도 User.id처럼 BINARY(16)로 바인딩
    private UUID userId;            // 값은 아래 @MapsId가 user.id에서 자동으로 채움

    @MapsId                         // "이 1:1의 PK = user의 PK" 라는 뜻 (PK 공유)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId")
    private User user;

    @Column(name = "mannerTemperature", nullable = false)
    private BigDecimal mannerTemperature;

    // ===== 온도 계산 상태(일일 배치 유지, 테크스펙 §5) =====
    @Column(name = "volumeIndex", nullable = false)
    private BigDecimal volumeIndex = BigDecimal.ZERO;        // V: 볼륨 지수이동평균

    @Column(name = "tenureBonus", nullable = false)
    private BigDecimal tenureBonus = BigDecimal.ZERO;        // B: 연차 보너스 [0,6]

    @Column(name = "qualifyingDays", nullable = false)
    private int qualifyingDays = 0;                          // 누적 자격일(s_d ≥ 0.6)

    @Column(name = "lastQualifyingDate")
    private LocalDate lastQualifyingDate;                    // 마지막 자격일(7일 유예 기준)

    @Column(name = "lastCalculatedDate")
    private LocalDate lastCalculatedDate;                    // 배치 멱등 가드

    public static ReputationScore createDefault(User user) {
        ReputationScore r = new ReputationScore();
        r.user = user;                          // userId는 @MapsId가 user.id에서 도출
        r.mannerTemperature = INITIAL_TEMPERATURE;
        return r;
    }

    /** 이미 오늘자 계산이 반영됐는지(멱등 가드). */
    public boolean isCalculatedFor(LocalDate today) {
        return lastCalculatedDate != null && !lastCalculatedDate.isBefore(today);
    }

    /** 배치가 하루치 스텝을 모두 적용한 뒤 결과 상태를 반영한다(불변식: 온도 5~93). */
    public void applyCalculation(double volumeIndex, double tenureBonus, int qualifyingDays,
                                 LocalDate lastQualifyingDate, BigDecimal temperature,
                                 LocalDate calculatedDate) {
        this.volumeIndex = BigDecimal.valueOf(volumeIndex).setScale(4, java.math.RoundingMode.HALF_UP);
        this.tenureBonus = BigDecimal.valueOf(tenureBonus).setScale(4, java.math.RoundingMode.HALF_UP);
        this.qualifyingDays = qualifyingDays;
        this.lastQualifyingDate = lastQualifyingDate;
        this.mannerTemperature = temperature;
        this.lastCalculatedDate = calculatedDate;
    }
}