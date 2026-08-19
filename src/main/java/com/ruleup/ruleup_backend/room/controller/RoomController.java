package com.ruleup.ruleup_backend.room.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.room.dto.RoomDtos;
import com.ruleup.ruleup_backend.room.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 방 홈 일괄 조회 + 방 안 랭킹. 둘 다 ACTIVE 멤버 전용. */
@Tag(name = "Challenge Room", description = "방 홈 일괄 조회 · 인증 이벤트 스레드 · 방 안 랭킹 — 전부 ACTIVE 멤버 전용")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @Operation(
            summary = "방 홈 일괄 조회",
            description = """
                    방에 들어갔을 때 화면 전체를 한 번에 채우는 조회다. 요약 스탯 · 상위 3 랭킹 · 내 오늘 인증 상태 ·
                    내 역할을 함께 내리므로 진입 시 이 호출 하나면 된다(스레드만 별도 호출).

                    **ACTIVE 멤버 전용**이다. 비멤버는 403 `NOT_CHALLENGE_MEMBER` 이고,
                    비멤버가 보는 화면은 탐색 모듈의 공개 상세(`GET /api/v1/challenges/{challengeId}`)가 담당한다.

                    화면 분기에 쓰는 값은 두 개다.
                    - `myRole` — `OWNER` 면 멤버 관리(초대·강퇴·권한 위임) 진입점을 노출한다.
                    - `ownerType` — `BOT` 이면 방장 자리가 비어 있다는 뜻이라 **"방장 되기"** 버튼을 노출한다
                      (선착순 클레임 — `POST /api/v1/challenges/{challengeId}/owner/claim`).

                    `summary.roomSuccessRate` 는 **판정이 한 건도 없으면 null** 이다. 0.0 이 아니라 null 인 이유는
                    갓 만들어진 방과 "전원이 실패한 방"이 화면에서 같아 보이면 안 되기 때문이다.
                    `capacity` 는 정원 제한이 없으면 null 이다.

                    `topRanking` 은 **10회 이상 참여자만** 등재되므로 초반에는 빈 배열이 정상이다.
                    내가 차단한 사람은 목록에서 빠지지 않고 임시 닉네임 + 기본 이미지로 가려진 채 들어온다.

                    판정 주기는 **1주 고정**이고 특정 요일은 지정하지 않는다. 방 조건은
                    `summary.weeklyCount`(주간 수행 횟수 1~7)로 내려가고, 이번 주 내 진행도는 `myWeekly`
                    (`done` · `weekStart` · `weekEnd` · `judging`)다 — 클라이언트는 이 둘로 "이번 주 2/3"을 그린다.
                    사이클 중간에 들어와 다음 주부터 판정되는 멤버와 아직 시작 전인 방은
                    `judging:false` · `done:0` 이다.

                    `myTodayStatus` 는 화면 어휘 5종이다 — `IN_PROGRESS` · `CHECKING`(00~03시 유예 구간) ·
                    `DONE` · `FAILED` · `NOT_TARGET`. 요일 지정이 없으므로 "요일상 대상이 아닌 날"은 없고,
                    `NOT_TARGET` 은 이번 주 몫을 이미 채웠거나(`myWeekly.done ≥ weeklyCount`)
                    아직 판정 대상이 아닌 경우(`judging:false`)에만 내려간다.

                    **읽음/미읽음 필드는 없다.** 정책상 영구 미제공이라 구 명세의 `unreadNoticeCount` ·
                    `pinnedNotice.isRead` 는 삭제됐다. 고정 공지(`pinnedNotice`)는 클라이언트 호환을 위해
                    필드만 유지하며 Phase 1 동안 항상 null 이다.
                    """
    )
    @ApiErrorCodes({ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/room")
    public ApiResponse<RoomDtos.RoomResponse> room(@AuthenticationPrincipal String userId,
                                                   @Parameter(description = "방 id", required = true)
                                                   @PathVariable UUID challengeId) {
        return ApiResponse.ok(roomService.room(UUID.fromString(userId), challengeId));
    }

    @Operation(
            summary = "방 안 랭킹",
            description = """
                    같은 방 멤버끼리의 순위다. 기준은 **참여일 이후 전체 성공률** 하나이며 주간/월간 탭은 없다.

                    **10회 이상 참여해야 등재된다.** 미달이면 `rank:null` · `ranked:false` · `successRate:null` 로
                    내려가므로 화면에는 "-" 로 표시한다. 표본이 적을 때 100% 가 1위를 먹는 것을 막기 위한 규칙이라,
                    미등재자도 목록에는 들어오되 등재자 뒤에 붙는다.

                    동점은 `successCount`(성공 횟수)가 많은 쪽이 앞이고, 그것도 같으면 먼저 참여한 순이다.
                    같은 순위는 같은 rank 값을 공유한다.

                    `me.gapToFirst` 는 1위와의 성공률 차이다 — 내가 미등재면 null 이다.
                    강퇴·탈퇴한 사람은 목록에 없다. 내가 차단한 사람은 **순위에서 빠지지 않고** 임시 닉네임 +
                    기본 이미지로 가려진 채 남는다(빼버리면 같은 방인데 사람마다 등수가 달라진다).

                    익명 챌린지는 차단 여부와 무관하게 닉네임이 마스킹되고 프로필 사진이 내려가지 않는다.
                    """
    )
    @ApiErrorCodes({ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/ranking")
    public ApiResponse<RoomDtos.RankingResponse> ranking(@AuthenticationPrincipal String userId,
                                                         @Parameter(description = "방 id", required = true)
                                                         @PathVariable UUID challengeId) {
        return ApiResponse.ok(roomService.ranking(UUID.fromString(userId), challengeId));
    }
}
