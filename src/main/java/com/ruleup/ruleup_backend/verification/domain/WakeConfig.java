package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 기상 인증 파라미터(§4.3). 「beforeTime - leadMinutes 부터 beforeTime + graceMinutes 까지 minSteps 걸음 이상」이면 성공.
 *
 * <p>예전에는 하루 첫 잠금 해제 시각으로 기상을 봤다. 잠금 해제는 누워서 알람만 끄고 다시 자도
 * 잡히고, 반대로 일어나서 휴대폰을 안 보면 실패한다 — 「일어났다」가 아니라 「휴대폰을 봤다」를
 * 쟀다. 2026-10-15 부터 <b>일어나서 움직인 흔적(걸음)</b>으로 본다.
 *
 * @param beforeTime     목표 기상 시각 "HH:mm"(루틴의 target_time)
 * @param leadMinutes    목표 시각 앞으로 걸음을 보는 시간(분). 하루 시작부터 보면 새벽에 잠깐 깨서 걸은 것도 기상이
 *                       된다 — 07:00 목표에 00:30 화장실 걸음으로 성공했다(2026-10-23)
 * @param graceMinutes   목표 시각 뒤로 걸음을 더 기다리는 시간(분). 일어나 바로 걷지 않거나 걸음 기록이
 *                       조금 늦게 잡혀도 인정한다
 * @param minSteps       창 안에서 필요한 걸음 수
 * @param trustedOrigins 걸음 기록을 인정하는 출처(비면 게이트 미적용)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WakeConfig(
        String beforeTime,
        int leadMinutes,
        int graceMinutes,
        int minSteps,
        List<String> trustedOrigins,
        Polarity polarity,            // ACHIEVEMENT
        int maxSignalLagHours
) {}
