package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationMute;
import com.ruleup.ruleup_backend.notification.domain.NotificationTab;
import com.ruleup.ruleup_backend.notification.domain.NotificationToggleGroup;
import com.ruleup.ruleup_backend.notification.domain.UserNotificationSetting;
import com.ruleup.ruleup_backend.notification.dto.NotificationResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationSettingDtos;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 알림 센터 조회와 설정 — 발행은 {@link NotificationPublisher}, 발송은 컨슈머가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 보관 6개월. 파기 배치가 지우고 목록에도 나오지 않는다. */
    public static final Duration RETENTION = Duration.ofDays(180);

    /**
     * <b>서버 고정 50.</b> 요청 파라미터로 받지 않는다 — 미읽음 카운터 상한이 {@code 99+} 라
     * 클라이언트는 최대 2페이지만 읽으면 되고, 크기를 협상할 이유가 없다.
     */
    private static final int PAGE_SIZE = 50;

    private final NotificationRepository repository;
    private final NotificationSettingRepository settingRepository;
    private final NotificationMuteRepository muteRepository;
    private final ChallengeMemberRepository challengeMemberRepository;
    private final AgreementService agreementService;
    private final UserRepository userRepository;
    private final AppProperties props;

    // ===== 알림 센터 =====

    /**
     * 알림 센터 목록. 커서는 <b>base64(id) 불투명 문자열</b>이며 클라이언트는 해석하지 않는다.
     *
     * <p>서버는 미읽음을 세지 않는다. 읽음 지점 id 하나만 내리고, 레드닷·챌린지별 카운터·개별
     * 미읽음 표시는 클라이언트가 <b>목록에서의 위치</b>로 계산한다.
     */
    @Transactional(readOnly = true)
    public NotificationResponse list(UUID userId, String rawTab, String rawCursor) {
        NotificationTab tab = tabOf(rawTab);
        UUID cursor = Cursor.decode(rawCursor);

        // 한 건 더 읽어 다음 페이지 유무를 판단한다. 보관 기간 밖은 파기 배치가 아직 못 지웠더라도
        // 내려보내지 않는다 — 응답의 retentionDays 와 실제 목록이 어긋나면 안 된다.
        List<Notification> rows = repository.findInbox(userId, tab.code(), cursor,
                Instant.now().minus(RETENTION), Limit.of(PAGE_SIZE + 1));
        boolean hasNext = rows.size() > PAGE_SIZE;
        List<Notification> page = hasNext ? rows.subList(0, PAGE_SIZE) : rows;

        List<NotificationResponse.Item> items = page.stream()
                .map(n -> new NotificationResponse.Item(
                        n.getId().toString(), n.getType(), n.getTitle(), n.getBody(),
                        n.getDeeplink(),
                        n.getChallengeId() == null ? null : n.getChallengeId().toString(),
                        n.getCreatedAt().toString()))
                .toList();

        UUID lastRead = settingRepository.findById(userId)
                .map(s -> s.readCursor(tab)).orElse(null);

        return new NotificationResponse(items,
                hasNext ? Cursor.encode(page.getLast().getId()) : null,
                (int) RETENTION.toDays(),
                lastRead == null ? null : lastRead.toString());
    }

    /**
     * 진입 시 그 시점 목록 전체를 읽음 처리한다. 개별 읽음 API 는 없다.
     *
     * <p><b>클라이언트가 보낸 id 로만 갱신한다.</b> 현재 시각으로 갱신하면 조회와 갱신 사이에
     * 적재된 알림이 화면에 뜬 적 없이 읽음 처리돼 레드닷이 영영 뜨지 않는다 — 00시 판정 배치나
     * 08:00 큐 소진 구간에서 실제로 생기는 경로다.
     *
     * <p><b>탭은 알림 자신에게서 가져오되, 보내온 탭이 어긋나면 400 이다.</b> 두 가지를 동시에
     * 지켜야 한다 — 클라이언트 주장대로 커서를 움직이면 안 되고(엉뚱한 탭의 레드닷이 꺼진다),
     * 어긋난 요청을 204 로 받아주어도 안 된다(클라이언트가 자기 버그를 모른 채 남는다).
     */
    @Transactional
    public void markRead(UUID userId, NotificationSettingDtos.ReadRequest request) {
        if (request == null || request.lastNotificationId() == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        UUID notificationId = parseUuid(request.lastNotificationId());
        Notification target = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));

        // 커서를 움직일 탭은 알림 자신에게서 가져온다 — 클라이언트가 보낸 값을 그대로 믿으면
        // 커서가 엉뚱한 탭으로 움직인다. 다만 보내온 값이 어긋날 때 조용히 넘기지는 않는다.
        // 무시하면 잘못 부른 클라이언트가 204 를 받고 「공지를 읽었다」고 오해한 채로 남는다.
        NotificationTab tab = target.tabEnum();
        if (request.tab() != null && !request.tab().isBlank() && tabOf(request.tab()) != tab)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);

        settings(userId, Instant.now()).advanceReadCursor(tab, notificationId, Instant.now());
    }

    // ===== 설정 =====

    @Transactional(readOnly = true)
    public NotificationSettingDtos.Response settings(UUID userId) {
        UserNotificationSetting s = settingRepository.findById(userId)
                .orElseGet(() -> UserNotificationSetting.defaults(userId, Instant.now()));
        return toResponse(userId, s);
    }

    /**
     * 마스터·그룹 부분 수정. 받는 키는 {@code pushEnabled} 와 {@code groups} 의 세 키뿐이고
     * 그 외는 400 이다.
     *
     * <p>마케팅 그룹만 부수효과가 있다 — 약관의 수신 동의 상태를 <b>같은 트랜잭션에서</b>
     * 갱신한다. 설정과 동의 이력이 어긋나면 어느 쪽이 진짜인지 알 수 없게 된다.
     */
    @Transactional
    public NotificationSettingDtos.PatchResponse patchSettings(
            UUID userId, NotificationSettingDtos.PatchRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        request.rejectUnknownKeys();

        Instant now = Instant.now();
        UserNotificationSetting s = settings(userId, now);

        if (request.pushEnabled() != null) s.applyMaster(request.pushEnabled(), now);

        Instant marketingSyncedAt = null;
        if (request.groups() != null) {
            NotificationSettingDtos.GroupPatch g = request.groups();
            if (g.account() != null) s.applyGroup(NotificationToggleGroup.ACCOUNT, g.account(), now);
            if (g.challenge() != null)
                s.applyGroup(NotificationToggleGroup.CHALLENGE, g.challenge(), now);
            if (g.marketing() != null) {
                s.applyGroup(NotificationToggleGroup.MARKETING, g.marketing(), now);
                syncMarketingConsent(userId, g.marketing(), now);
                marketingSyncedAt = now;
            }
        }
        return new NotificationSettingDtos.PatchResponse(toResponse(userId, s),
                marketingSyncedAt == null ? null : marketingSyncedAt.toString());
    }

    /** 음소거 등록 — <b>참여 중인 챌린지만</b>. 멱등이다. */
    @Transactional
    public void mute(UUID userId, UUID challengeId) {
        boolean joined = challengeMemberRepository.findByChallengeIdAndUserId(challengeId, userId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .isPresent();
        if (!joined) throw new BusinessException(ErrorCode.CHALLENGE_NOT_JOINED);

        NotificationMute.Key key = new NotificationMute.Key(userId, challengeId);
        if (muteRepository.existsById(key)) return;
        muteRepository.save(NotificationMute.of(userId, challengeId, Instant.now()));
    }

    /**
     * 음소거 해제 — 멱등이며 <b>참여 여부를 따지지 않는다</b>. 탈퇴한 방의 음소거를 못 지우면
     * 목록에 영영 남는다.
     */
    @Transactional
    public void unmute(UUID userId, UUID challengeId) {
        muteRepository.deleteById(new NotificationMute.Key(userId, challengeId));
    }

    // ===== 내부 =====

    /** 설정 행을 확보한다 — 없으면 기본값으로 만들어 저장한다. */
    private UserNotificationSetting settings(UUID userId, Instant now) {
        return settingRepository.findById(userId).orElseGet(() ->
                settingRepository.save(UserNotificationSetting.defaults(userId, now)));
    }

    private NotificationSettingDtos.Response toResponse(UUID userId, UserNotificationSetting s) {
        List<String> muted = muteRepository.findByUserIdOrderByMutedAtAsc(userId).stream()
                .map(m -> m.getChallengeId().toString()).toList();
        return new NotificationSettingDtos.Response(
                s.isPushEnabled(),
                new NotificationSettingDtos.Groups(s.isGroupAccount(), s.isGroupChallenge(),
                        s.isGroupMarketing()),
                muted);
    }

    private void syncMarketingConsent(UUID userId, boolean agreed, Instant now) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
        agreementService.record(user, AgreementType.MARKETING, agreed,
                props.client().termsVersions().marketing(), now);
    }

    private static NotificationTab tabOf(String raw) {
        if (raw == null || raw.isBlank()) return NotificationTab.NOTIFICATION;
        return NotificationTab.find(raw)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /**
     * 커서 — 알림 id 를 base64url 로 감싼 <b>불투명 문자열</b>이다.
     *
     * <p>id 하나로 충분한 이유는 UUIDv7 이기 때문이다. 상위 48비트가 밀리초 타임스탬프라
     * id 순서가 곧 시간 순서이고, 00시 배치가 같은 밀리초에 수만 행을 넣어도 동점이 없다.
     * 구 {@code createdAt|id} 복합 커서는 인덱스에 밀어넣을 수 없어 버렸다.
     */
    private static final class Cursor {

        private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
        private static final Base64.Decoder DEC = Base64.getUrlDecoder();

        static UUID decode(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return UUID.fromString(new String(DEC.decode(raw), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.CURSOR_INVALID);
            }
        }

        static String encode(UUID id) {
            return ENC.encodeToString(id.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
