package com.ruleup.ruleup_backend.notification.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 알림 설정 — 마스터 1개 + 그룹 3종 + 챌린지별 음소거(읽기 전용). 공통 4절·6절.
 *
 * <p>유형별 토글 모델은 폐기됐다(공통 #20). 설정 화면이 20줄이 되는 대신 그룹 3개로 접었다.
 */
public final class NotificationSettingDtos {

    private NotificationSettingDtos() {}

    @Schema(name = "NotificationSettingResponse")
    public record Response(

            @Schema(description = "마스터 토글. OFF 면 그룹이 ON 이어도 푸시가 나가지 않는다.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean pushEnabled,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Groups groups,

            @Schema(description = """
                    챌린지별 음소거 목록 — **읽기 전용**이다. 켜고 끄기는 각 챌린지 방에서
                    `.../notification-settings/mutes/{challengeId}` 로 한다.""",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> mutedChallengeIds) {}

    @Schema(name = "NotificationSettingGroups", description = """
            그룹 토글 3종. 루틴 리마인더는 그룹이 없어(상시) 여기 없고, 마스터와 챌린지 음소거만
            영향을 준다.""")
    public record Groups(
            @Schema(description = "강퇴 · 잠금 · 심사 결과 · 이의 결과 · 권한 재허용") Boolean account,
            @Schema(description = "판정 결과 · 티어 · 감시자 통지 · 방 생명주기") Boolean challenge,
            @Schema(description = "광고성 정보. 끄면 약관의 수신 동의도 함께 철회된다.") Boolean marketing) {}

    @Schema(name = "NotificationSettingPatchRequest", description = """
            **네 키만 받는다** — `pushEnabled` · `groups.account` · `groups.challenge` ·
            `groups.marketing`. 그 외 키는 400 `NOTIFICATION_GROUP_INVALID` 다.

            `mutedChallengeIds` 는 여기로 받지 않는다. 챌린지별 음소거는 별도 API 로만 바꾼다.""")
    public static final class PatchRequest {

        private Boolean pushEnabled;
        private GroupPatch groups;
        private boolean sawUnknownKey;

        public Boolean pushEnabled() {
            return pushEnabled;
        }

        public GroupPatch groups() {
            return groups;
        }

        public void setPushEnabled(Boolean pushEnabled) {
            this.pushEnabled = pushEnabled;
        }

        public void setGroups(GroupPatch groups) {
            this.groups = groups;
        }

        /**
         * 모르는 키를 조용히 무시하지 않는다. 무시하면 클라이언트는 <b>바뀌었다고 믿는데</b>
         * 서버는 아무것도 안 한 상태가 되고, 그 어긋남은 설정 화면에서만 드러난다.
         * 구 계약의 {@code types} · {@code mutedChallengeIds} 도 여기서 걸린다.
         */
        @JsonAnySetter
        public void unknown(String key, Object value) {
            this.sawUnknownKey = true;
        }

        public void rejectUnknownKeys() {
            if (sawUnknownKey || (groups != null && groups.sawUnknownKey))
                throw new BusinessException(ErrorCode.NOTIFICATION_GROUP_INVALID);
        }
    }

    /** 요청측 그룹 — 응답의 {@link Groups} 와 달리 <b>모르는 키를 기억</b>해야 해서 따로 둔다. */
    @Schema(name = "NotificationSettingGroupPatch")
    public static final class GroupPatch {

        private Boolean account;
        private Boolean challenge;
        private Boolean marketing;
        private boolean sawUnknownKey;

        public Boolean account() {
            return account;
        }

        public Boolean challenge() {
            return challenge;
        }

        public Boolean marketing() {
            return marketing;
        }

        public void setAccount(Boolean account) {
            this.account = account;
        }

        public void setChallenge(Boolean challenge) {
            this.challenge = challenge;
        }

        public void setMarketing(Boolean marketing) {
            this.marketing = marketing;
        }

        @JsonAnySetter
        public void unknown(String key, Object value) {
            this.sawUnknownKey = true;
        }
    }

    @Schema(name = "NotificationSettingPatchResponse")
    public record PatchResponse(
            Response settings,
            @Schema(description = """
                    마케팅 그룹을 바꿨을 때만 채워진다 — 약관 수신 동의를 갱신한 시각이다.""")
            String marketingConsentSyncedAt) {}

    @Schema(name = "NotificationReadRequest", description = """
            **`GET /notifications` 첫 페이지 조회 직후에만** 호출한다. 커서 페이징 2페이지 이후는
            id 가 더 작아서 읽음 지점이 과거로 밀린다 — 서버가 무시하지만 호출할 이유도 없다.""")
    public record ReadRequest(
            @Schema(description = """
                    읽음 처리할 탭 — `NOTIFICATION` | `ANNOUNCEMENT`. **필수다.** 다만 커서를 실제로
                    움직일 탭은 알림 자신에게서 가져오며, 보낸 값이 그와 어긋나거나 정의되지 않은
                    값이면 400 이다.""",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String tab,
            @Schema(description = """
                    **응답에 실제로 담겼던 최신 알림의 id.** 서버는 `NOW()` 로 갱신하지 않는다.""",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String lastNotificationId) {}
}
