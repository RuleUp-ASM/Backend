package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 커서 인코딩 — 백오피스 공통 5-2-1 A #1 「커서는 <b>불투명 문자열</b>」.
 *
 * <h4>왜 불투명해야 하나</h4>
 * 커서에 정렬 키가 그대로 드러나면 클라이언트가 그 값을 조립하기 시작하고, 그 순간 정렬 키를
 * 바꾸는 것이 <b>클라이언트 배포를 요구하는 변경</b>이 된다. 여기서 바꿀 여지를 남겨 두려면
 * 커서는 서버가 준 것을 그대로 되돌려 보내는 값이어야 한다.
 *
 * <h4>offset 이 아니라 keyset 이다</h4>
 * offset 페이징은 페이지를 넘기는 사이에 새 건이 들어오면 <b>이미 본 건을 다시 보여주거나
 * 못 본 건을 건너뛴다</b>. 검토 큐에서 건너뛰는 건은 그대로 미처리로 남으므로, 정렬 키를
 * 그대로 이어받는 keyset 으로 간다.
 */
final class AdminCursors {

    private AdminCursors() {}

    static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // 위조된 커서를 500 으로 흘리지 않는다 — 잘못된 입력이지 서버 오류가 아니다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    /** 시각 한 축으로 정렬하는 큐(신고·문의)의 커서. */
    static String ofInstant(Instant at) {
        return at == null ? null : encode(String.valueOf(at.toEpochMilli()));
    }

    static Instant toInstant(String cursor) {
        String raw = decode(cursor);
        if (raw == null) return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    /** (점수, 탐지 시각) 두 축으로 정렬하는 이상탐지 큐의 커서. */
    record ScoreCursor(int score, Instant detectedAt) {}

    static String ofScore(int score, Instant detectedAt) {
        return encode(score + ":" + detectedAt.toEpochMilli());
    }

    static ScoreCursor toScore(String cursor) {
        String raw = decode(cursor);
        if (raw == null) return null;
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        try {
            return new ScoreCursor(Integer.parseInt(parts[0]),
                    Instant.ofEpochMilli(Long.parseLong(parts[1])));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
