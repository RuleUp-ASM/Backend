package com.ruleup.ruleup_backend.challenge.domain;

import java.math.BigDecimal;

/**
 * 패널티 설정. challenges.penalty_config(JSON) 컬럼에 통째로 직렬화 저장.
 *  - mannerDeduction : 실패 시 매너 차감(필수, 본 스펙은 설정 저장까지만. 실제 가감은 VF/PN 스펙)
 *  - snsShare        : SNS 공유 사용 여부 + 공유받을 번호
 *  - groupShare      : 그룹 내 공유 여부
 * record라 Jackson이 그대로 JSON 직렬화/역직렬화한다.
 */
public record PenaltyConfig(
        BigDecimal mannerDeduction,
        SnsShare snsShare,
        boolean groupShare
) {
    public record SnsShare(boolean enabled, String phone) {}
}