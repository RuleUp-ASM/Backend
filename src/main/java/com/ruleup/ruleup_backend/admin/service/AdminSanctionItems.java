package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.sanction.domain.Sanction;

import java.time.Instant;

/**
 * {@link Sanction} → 응답 변환. 한 곳에 모으는 이유는 <b>{@code active} 판정 때문</b>이다.
 *
 * <p>백오피스 공통 부록 A 정정이 세 경우를 못박았다 — 진행 중({@code endsAt} 미래) ·
 * 동결({@code frozenRemainingSec} 있음) · 영구(둘 다 null). <b>셋을 다 담지 않으면 동결된 계정이
 * 백오피스에서 「제재 없음」으로 보인다.</b> 이 판정을 화면마다 따로 쓰면 한 화면에서만 틀리는
 * 형태로 어긋나고, 그건 조회 화면이라 아무도 알아채지 못한다.
 */
final class AdminSanctionItems {

    private AdminSanctionItems() {}

    static AdminDtos.SanctionItem of(Sanction s, String nickname, Instant now) {
        return new AdminDtos.SanctionItem(
                s.getId().toString(),
                s.getUserId().toString(),
                nickname,
                s.getTrack().name(),
                s.getType().name(),
                isPermanent(s),
                s.getFeatureCode() == null ? null : s.getFeatureCode().name(),
                s.getReasonCode().name(),
                s.getReasonText(),
                s.getSource().name(),
                s.getSourceId() == null ? null : s.getSourceId().toString(),
                s.getStartsAt().toString(),
                s.getEndsAt() == null ? null : s.getEndsAt().toString(),
                s.getFrozenRemainingSec(),
                s.getRevokedAt() == null ? null : s.getRevokedAt().toString(),
                s.isAppealUsed(),
                s.getNotifiedAt() == null ? null : s.getNotifiedAt().toString(),
                s.isActiveAt(now));
    }

    /**
     * 영구 여부. <b>동결과 구분해야 한다</b> — 둘 다 {@code endsAt} 이 null 이지만 동결은
     * 잔여 시간이 남아 있고 영구는 애초에 없다.
     */
    private static boolean isPermanent(Sanction s) {
        return s.getEndsAt() == null && s.getFrozenRemainingSec() == null;
    }
}
