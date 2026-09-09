package com.ruleup.ruleup_backend.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 2단계 확인 봉투 — 백오피스 공통 5-2-1 §C.
 *
 * <h4>재제시 문구를 클라이언트가 만들지 않는 것이 핵심이다</h4>
 * 되돌리기 어려운 집행(제재 · 직권 폐쇄 · 운영 공지)은 <b>토큰 없이 먼저 호출</b>한다. 서버가
 * 428 과 함께 <b>자신이 계산한 값</b>을 돌려주고, 운영자가 그 값을 확인하면 같은 요청에 토큰을
 * 실어 재호출한다. 클라이언트가 "닉네임 · 1개월 정지"를 스스로 조립하면 그 문장이 서버가
 * 실제로 집행할 내용과 어긋날 수 있고, 어긋난 채로 확인을 받으면 확인이 아무 의미가 없다.
 *
 * @param effectiveUntil <b>null 이면 영구</b>다.
 * @param sideEffects    「영향 인원 수를 먼저 응답」을 겸한다 — 직권 폐쇄의 자동 탈퇴 인원이 여기 실린다.
 */
@Schema(name = "Confirmation", description = "428 CONFIRMATION_REQUIRED 와 함께 내려가는 재확인 요약")
public record Confirmation(

        @Schema(description = "이 요청·이 내용에 한해 유효한 토큰. 짧게 만료되며 만료 후에는 다시 428 이다.")
        String token,

        @Schema(example = "2026-09-09T12:34:56Z") String expiresAt,

        @Schema(description = "대상 — 닉네임 또는 챌린지 제목", example = "런닝왕") String targetLabel,

        @Schema(description = "집행 내용 한 줄", example = "로그인 정지 · 1개월") String actionLabel,

        @Schema(description = "해제 예정 시각. **null 이면 영구**다.") String effectiveUntil,

        @Schema(description = "부수 효과 — 되돌릴 수 없는 것은 irreversible=true")
        List<SideEffect> sideEffects) {

    @Schema(name = "ConfirmationSideEffect")
    public record SideEffect(
            @Schema(example = "전 챌린지 자동 탈퇴") String label,
            @Schema(example = "3") int count,
            boolean irreversible) {}

    public static Confirmation of(String token, java.time.Instant expiresAt, String targetLabel,
                                  String actionLabel, java.time.Instant effectiveUntil,
                                  List<SideEffect> sideEffects) {
        return new Confirmation(token, expiresAt == null ? null : expiresAt.toString(),
                targetLabel, actionLabel,
                effectiveUntil == null ? null : effectiveUntil.toString(),
                sideEffects == null ? List.of() : sideEffects);
    }
}
