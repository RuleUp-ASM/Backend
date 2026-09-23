package com.ruleup.ruleup_backend.challenge.domain;

import java.math.BigDecimal;

/**
 * 보상 설정. challenges.reward_config(JSON) 컬럼에 직렬화 저장.
 *  - mannerGain : 성공 시 매너 가산(필수, 설정 저장까지만)
 */
public record RewardConfig(BigDecimal mannerGain) {}