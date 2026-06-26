package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/** §11.4 응답. READY면 평가 대상 진입, PENDING_SETUP이면 missing에 안 받은 권한·항목. */
public record SetupResponse(String setupStatus, List<String> missing) {}
