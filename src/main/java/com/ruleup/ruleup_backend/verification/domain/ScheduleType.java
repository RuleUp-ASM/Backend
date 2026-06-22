package com.ruleup.ruleup_backend.verification.domain;

/**
 * 인증 스케줄 방식 (인증 스펙 §2.12, §4.2).
 *  - FIXED_DAYS : 고정 요일형(repeatDays 기준 매 대상일 1회).
 *  - FREQUENCY  : 빈도형(주/월 N회). 주기 단위 마감.
 */
public enum ScheduleType { FIXED_DAYS, FREQUENCY }
