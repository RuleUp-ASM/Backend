package com.ruleup.ruleup_backend.verification.dto;

/**
 * 스크린 타임 측정 대상 앱 한 개. 셋업 제출·조회·수정이 공유하는 계약.
 *
 * @param packageName Android 패키지명 — 측정 키. 형식 오류·중복·11개 이상이면 INVALID_APP
 * @param appName     표시용 앱 이름. 스냅샷으로 저장돼 기기에서 앱을 지워도 목록에 남는다
 */
public record AppDto(String packageName, String appName) {}
