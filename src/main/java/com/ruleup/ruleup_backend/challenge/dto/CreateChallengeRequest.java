package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 3.2 생성 요청 — 추천을 수정·확정한 최종값.
 *  - 인증은 루틴에서: templateId(추천에서 받은 루틴, 매칭 실패면 null) + selectedMethod(AUTO/MANUAL)
 *    + params(이 챌린지의 목표값).
 *  - 권한은 두 갈래(§5.1): 즉시형(알림·FINE위치·활동인식·HC·카메라)은 생성 버튼 시점에 팝업으로 받아
 *    grantedPermissions 로 보내면 AUTO 인증에서 검증한다. 설정형(백그라운드 위치·사용정보 접근)은
 *    가입 후 최초 진입 셋업(§11.4 /setup)에서 등록 → 생성 시엔 강제하지 않는다.
 *  - endDate는 보내지 않는다(서버가 startDate + durationDays로 파생).
 */
public record CreateChallengeRequest(
        String title,
        String description,
        String imageUrl,
        String category,
        String participationType,
        BigDecimal minMannerTemperature,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        Long templateId,
        String selectedMethod,
        Map<String, Object> params,
        List<String> grantedPermissions,
        PenaltyConfig penalty,
        RewardConfig reward,
        String anonymity
) {
    public Map<String, Object> paramsOrEmpty() {
        return (params != null) ? params : Map.of();
    }

    /** AUTO 선택 시 클라가 확보했다고 보낸 권한 목록(검증용, 저장 안 함 §5.6). */
    public List<String> grantedPermissionsOrEmpty() {
        return (grantedPermissions != null) ? grantedPermissions : List.of();
    }
}