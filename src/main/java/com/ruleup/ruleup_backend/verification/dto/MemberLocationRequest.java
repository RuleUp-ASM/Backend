package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/** §11.5 내 인증 장소 수정. 본인 앵커 교체. */
public record MemberLocationRequest(String fillMode, List<SetupRequest.AnchorDto> anchors) {}
