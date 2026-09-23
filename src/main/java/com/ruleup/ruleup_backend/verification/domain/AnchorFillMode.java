package com.ruleup.ruleup_backend.verification.domain;

/**
 * 앵커 채우기 모드(테크스펙 v2 §5.2). 평가 로직은 동일(anchors[] OR), 채우는 UX만 다름.
 *  - MANUAL       : 사용자가 검색에서 직접 다중 선택(헬스장 등).
 *  - NEARBY_BRAND : 브랜드+중심좌표+반경 → Naver Local 1회 호출로 반경 내 동일 브랜드 일괄(카페 등).
 */
public enum AnchorFillMode { MANUAL, NEARBY_BRAND }
