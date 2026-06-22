package com.ruleup.ruleup_backend.verification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 인증 확정 배치용 스케줄링 활성화(§1 확정 배치). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
