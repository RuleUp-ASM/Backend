package com.ruleup.ruleup_backend.verification.domain;
import com.ruleup.ruleup_backend.common.verification.*;

/**
 * 인증 확정 경로(테크스펙 v2 §9). VerificationStatus는 5종 유지하고 이 필드로 폴백을 구분(경량화).
 *  - AUTO            : 신호 기반 자동 판정.
 *  - MANUAL          : 정규 수동(PHOTO/SELF_CHECK).
 *  - MANUAL_FALLBACK : 예비 수동 폴백(자동인데 오늘 신호 부재·권한 먹통 등). 월3회·그룹 승인.
 *  - OBJECTION       : 잠정 실패에 대한 이의 제기 승인으로 SUCCESS 확정(§8.7).
 */
public enum VerifiedVia { AUTO, MANUAL, MANUAL_FALLBACK, OBJECTION }
