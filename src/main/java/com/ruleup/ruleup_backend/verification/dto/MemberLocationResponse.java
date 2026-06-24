package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/** §11.5 응답. 적용된 앵커 + 적용 시작일(윈도우 중 변경 시 익일). */
public record MemberLocationResponse(List<SetupRequest.AnchorDto> anchors, String appliedFrom) {}
