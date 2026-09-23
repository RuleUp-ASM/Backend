package com.ruleup.ruleup_backend.verification.domain;

/**
 * 인증 확정 경로. 상태(VerificationStatus)는 그대로 두고 "어떻게 확정됐는지"만 이 필드로 구분한다.
 *  - AUTO   : 신호 기반 자동 판정.
 *  - MANUAL : 수동 인증 챌린지에서 사용자가 당일 직접 체크.
 *  - APPEAL : 실패 확정 뒤 이의가 형식 요건을 통과해 완료로 정정됨(정상 성공과 같은 점수).
 *
 * <p>구 정책의 MANUAL_FALLBACK(방장 승인 예비 폴백)은 폐기됐다 — 방장은 인증을 판정하지 않는다.
 */
public enum VerifiedVia { AUTO, MANUAL, APPEAL }
