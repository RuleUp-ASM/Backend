package com.ruleup.ruleup_backend.user.domain;

/**
 * 닉네임 심사 상태 (users.nickname_status).
 *  - PENDING  : 심사 중 — 본인에겐 신청값, 타인에겐 승인 닉네임(approved_nickname) 노출
 *  - APPROVED : 승인 — approved_nickname = 신청값
 *  - REJECTED : 거절 — approved_nickname 은 직전 승인본(없으면 임시 닉네임) 유지
 *  - CONFLICT : 승인 직전(또는 탈퇴 복원 시) 타인이 선점해 충돌 — 재설정 유도, 클라는 홈 진입 차단
 *
 * ⚠️ 어떤 상태도 가입/기능 이용을 막지 않는다(심사 중 기능 제한 없음 — 회원 정책 §4).
 */
public enum NicknameStatus {
    PENDING, APPROVED, REJECTED, CONFLICT
}
