package com.ruleup.ruleup_backend.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 이의 제기 현황(GET /users/me/appeals).
 *
 * <p><b>구제권이 아니다.</b> 횟수 한도가 없으므로 잔여 구제권·리셋 시각·사용 여부가 존재하지 않고
 * (챌린지 정책 §7.2), 접수된 건은 즉시 인용되므로 계류·기각 상태도 없다. 형식 미달(10자 미만)은
 * 접수 자체가 되지 않아 이력에도 남지 않는다 — 그래서 이 목록은 <b>전건이 ACCEPTED</b> 다.
 */
@Schema(name = "MyAppealsResponse", description = "내가 낸 이의 이력(최신순). 전건 인용이라 상태 필터가 없다.")
public record MyAppealsResponse(

        @Schema(description = "신청 이력 (최신순)")
        List<Item> history) {

    @Schema(name = "MyAppealItem")
    public record Item(
            @Schema(description = "이의 ID") String appealId,
            @Schema(description = "신청일 (KST)", example = "2026-07-20") String date,
            @Schema(description = "대상 챌린지") String challengeId,
            @Schema(description = "대상 챌린지 제목. 삭제된 방이면 null") String routineTitle,
            @Schema(description = "제출한 사유") String reason,
            @Schema(description = """
                    이의 트랙. 이의가 자동 인용 한 경로로 통합돼(2026-08-25) 실제로는 항상 `B` 다 —
                    클라이언트 분기를 깨지 않으려고 필드는 유지한다.""", example = "B") String track,
            @Schema(description = "`ACCEPTED` 고정 — 기각 상태가 존재하지 않는다", example = "ACCEPTED")
            String result) {}
}
