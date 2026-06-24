package com.ruleup.ruleup_backend.verification.domain;
import com.ruleup.ruleup_backend.common.verification.*;

/**
 * 인증 확정 경로(테크스펙 v2 §9). VerificationStatus는 5종 유지하고 이 필드로 폴백을 구분(경량화).
 *  - AUTO            : 신호 기반 자동 판정.
 *  - MANUAL          : 정규 수동(PHOTO/SELF_CHECK).
 *  - MANUAL_FALLBACK : 예비 수동 폴백(자동인데 오늘 신호 부재·권한 먹통 등). 주1회·잠정성공·이의윈도우.
 */
public enum VerifiedVia { AUTO, MANUAL, MANUAL_FALLBACK }
