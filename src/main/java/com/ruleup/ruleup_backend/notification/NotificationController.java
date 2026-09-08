package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationSettingDtos;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private static final String SETTINGS = "/api/v1/users/me/notification-settings";

    private final NotificationService service;

    @Operation(summary = "알림 센터 목록", description = """
            커서 페이징. **페이지 크기는 서버 고정 50**이라 `size` 를 받지 않는다 — 미읽음 카운터
            상한이 `99+` 라 클라이언트는 최대 2페이지만 읽으면 된다.

            커서는 base64(id) **불투명 문자열**이다. UUIDv7 이라 id 순서가 곧 시간 순서이므로,
            00시 판정 배치가 같은 밀리초에 수만 행을 넣어도 페이지 경계가 어긋나지 않는다.

            운영자 공지는 `tab=ANNOUNCEMENT` 로 이 API 가 함께 담당한다(구 `GET /announcements`
            폐기). 기본값이 `NOTIFICATION` 이라 미지정 조회에 공지가 섞이지 않는다.

            **모든 알림은 푸시 발송 여부·설정·시각과 무관하게 여기 적재**되며 `createdAt` 이
            고지 성립 시각이다. 보관 6개월이고 **유저 개별 삭제 기능은 없다**.

            ⚠️ **잠금 계정도 열람할 수 있다** — 제재 고지가 여기 쌓이기 때문이다.
            """)
    @GetMapping("/api/v1/notifications")
    public ApiResponse<NotificationResponse> list(@AuthenticationPrincipal String userId,
                                                  @RequestParam(required = false) String tab,
                                                  @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(service.list(UUID.fromString(userId), tab, cursor));
    }

    @Operation(summary = "알림 센터 전체 읽음", description = """
            진입 시 그 시점 목록 전체를 읽음 처리한다. **개별 읽음 API 는 없다.**

            `GET /notifications` **첫 페이지 조회 직후에만** 호출한다 — 2페이지 이후는 id 가 더
            작아 읽음 지점이 과거로 밀린다(서버가 무시한다).

            ⚠️ 서버는 `NOW()` 로 갱신하지 않고 **보낸 id 로만** 갱신한다. 현재 시각으로 갱신하면
            조회와 갱신 사이에 적재된 알림이 화면에 뜬 적 없이 읽음 처리돼 레드닷이 영영 뜨지 않는다.

            멱등이다. 읽음 지점은 **탭별로 따로** 보관한다.
            """)
    @PutMapping("/api/v1/notifications/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal String userId,
                                         @RequestBody NotificationSettingDtos.ReadRequest request) {
        service.markRead(UUID.fromString(userId), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "알림 설정 조회", description = """
            마스터 토글 + 그룹 3종 + 챌린지별 음소거 목록.

            **설정한 적이 없으면 행이 없고, 이때 마스터·그룹은 전부 `true`** 로 내린다.
            가입 시 백필하지 않는다. 설정은 **푸시에만 적용**되며 어느 계층으로 막혀도
            알림 센터에는 그대로 쌓인다.

            OS 푸시 권한 상태는 포함되지 않는다 — 서버가 알 수 없는 값이라 발송 판정에 쓰지 않는다.
            """)
    @GetMapping(SETTINGS)
    public ApiResponse<NotificationSettingDtos.Response> settings(
            @AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.settings(UUID.fromString(userId)));
    }

    @Operation(summary = "알림 설정 변경", description = """
            보낸 필드만 바꾼다. 받는 키는 `pushEnabled` · `groups.account` · `groups.challenge` ·
            `groups.marketing` **네 개뿐**이고 그 외는 400 `NOTIFICATION_GROUP_INVALID` 다.

            `mutedChallengeIds` 는 여기로 받지 않는다 — 챌린지별 음소거는 별도 API 로만 바꾼다.

            `groups.marketing` 변경만 부수효과가 있다: 약관의 마케팅 수신 동의를 **같은
            트랜잭션에서** 갱신하고 `marketingConsentSyncedAt` 을 내린다.

            **끌 수 없는 푸시는 없다.** 계정 그룹을 끄면 강퇴·잠금 고지 푸시도 나가지 않는다.
            다만 알림 센터 적재는 그대로이므로 고지는 성립한다.
            """)
    @PatchMapping(SETTINGS)
    public ApiResponse<NotificationSettingDtos.PatchResponse> patchSettings(
            @AuthenticationPrincipal String userId,
            @RequestBody NotificationSettingDtos.PatchRequest request) {
        return ApiResponse.ok(service.patchSettings(UUID.fromString(userId), request));
    }

    @Operation(summary = "챌린지 음소거 등록", description = """
            설정 3계층 중 가장 좁은 계층이며 **가장 제한적인 것이 이긴다**. 그 챌린지에서 발생하는
            전 알림의 푸시가 막히고, **그 방의 루틴은 리마인더 집계에서도 빠진다** — 참여 챌린지를
            전부 음소거하면 리마인더 자체가 발송되지 않는다.

            **참여 중인 챌린지만** 등록할 수 있다. 멱등 204.
            """)
    @PutMapping(SETTINGS + "/mutes/{challengeId}")
    public ResponseEntity<Void> mute(@AuthenticationPrincipal String userId,
                                     @PathVariable UUID challengeId) {
        service.mute(UUID.fromString(userId), challengeId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "챌린지 음소거 해제", description = """
            멱등 204. **참여 여부를 따지지 않는다** — 탈퇴한 방의 음소거를 못 지우면 목록에 영영 남는다.
            """)
    @DeleteMapping(SETTINGS + "/mutes/{challengeId}")
    public ResponseEntity<Void> unmute(@AuthenticationPrincipal String userId,
                                       @PathVariable UUID challengeId) {
        service.unmute(UUID.fromString(userId), challengeId);
        return ResponseEntity.noContent().build();
    }
}
