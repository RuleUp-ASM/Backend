package com.ruleup.ruleup_backend.reputation;

import com.ruleup.ruleup_backend.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 매너 온도 (ReputationScore 테이블). User와 1:1, PK를 User와 공유(@MapsId).
 * W1에서는 초기값 36.5 저장/표시만. 계산 로직은 이후 스펙.
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

    public static ReputationScore createDefault(User user) {
        ReputationScore r = new ReputationScore();
        r.user = user;                          // userId는 @MapsId가 user.id에서 도출
        r.mannerTemperature = INITIAL_TEMPERATURE;
        return r;
    }
}