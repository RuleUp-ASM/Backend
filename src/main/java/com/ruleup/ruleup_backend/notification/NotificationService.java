package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.notification.domain.*;
import com.ruleup.ruleup_backend.notification.dto.NotificationResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationSettingDtos;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 알림함 조회·삭제와 설정 — 발송 파이프라인은 {@link NotificationPublisher} 가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 보관 6개월. 이 기간을 넘긴 건은 정리 배치가 지우고 목록에도 나오지 않는다. */
    public static final Duration RETENTION = Duration.ofDays(180);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final NotificationRepository repository;
    private final NotificationSettingRepository settingRepository;
    private final NotificationMuteRepository muteRepository;
    private final AgreementService agreementService;
    private final AppProperties props;
    private final UserRepository userRepository;

    // ===== 알림함 =====

    /**
     * 커서 페이징. 커서는 {@code createdAt|id} 복합값이다 — 같은 밀리초에 여러 건이 적재되는
     * 00시 판정 피크에서 단일 id 커서를 쓰면 페이지 경계 항목이 빠지거나 겹친다.
     */
    @Transactional(readOnly = true)
    public NotificationResponse list(UUID userId, String cursor, Integer requestedSize) {
        int size = (requestedSize == null) ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        Cursor c = Cursor.parse(cursor);

        // 한 건 더 읽어 다음 페이지 유무를 판단한다.
        List<Notification> rows = repository.findInbox(userId, c.at(), c.id(), Limit.of(size + 1));
        boolean hasNext = rows.size() > size;
        List<Notification> page = hasNext ? rows.subList(0, size) : rows;

        List<NotificationResponse.Item> items = page.stream()
                .map(n -> new NotificationResponse.Item(
                        n.getId().toString(), n.getCategory(), n.getType(), n.getTitle(),
                        n.getBody(), n.getDeeplink(), n.isRead(), n.getCreatedAt().toString()))
                .toList();

        String next = hasNext && !page.isEmpty()
                ? Cursor.encode(page.getLast()) : null;
        return new NotificationResponse(items, unread(userId), (int) RETENTION.toDays(), next);
    }

    @Transactional
    public NotificationResponse.ReadResponse markRead(UUID userId, UUID notificationId) {
        find(userId, notificationId).markRead(Instant.now());
        repository.flush();
        return new NotificationResponse.ReadResponse(true, unread(userId));
    }

    @Transactional
    public NotificationResponse.ReadAllResponse markAllRead(UUID userId) {
        Instant now = Instant.now();
        List<Notification> all = repository.findInbox(userId, null, null, Limit.unlimited());
        long changed = all.stream().filter(n -> !n.isRead()).peek(n -> n.markRead(now)).count();
        repository.flush();
        return new NotificationResponse.ReadAllResponse(changed, unread(userId));
    }

    /** 소프트 삭제 — 목록에서만 빠지고 <b>고지 기록 자체는 남는다</b>. */
    @Transactional
    public NotificationResponse.DeleteResponse delete(UUID userId, UUID notificationId) {
        find(userId, notificationId).delete(Instant.now());
        return new NotificationResponse.DeleteResponse(true);
    }

    private Notification find(UUID userId, UUID notificationId) {
        return repository.findByIdAndUserIdAndDeletedAtIsNull(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    private long unread(UUID userId) {
        return repository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId);
    }

    // ===== 설정 =====

    /** 필수(A) 타입은 응답에 넣지 않는다 — 존재를 인지시키지 않는 것이 정책이다. */
    @Transactional(readOnly = true)
    public NotificationSettingDtos.Response settings(UUID userId) {
        Map<String, Boolean> stored = new HashMap<>();
        settingRepository.findByUserId(userId)
                .forEach(s -> stored.put(s.getType(), s.isEnabled()));

        List<NotificationSettingDtos.TypeToggle> types = Arrays.stream(NotificationType.values())
                .filter(t -> t.category() == NotificationCategory.B)
                .map(t -> new NotificationSettingDtos.TypeToggle(
                        t.name(), stored.getOrDefault(t.name(), true)))   // 행이 없으면 기본 ON
                .toList();

        List<String> muted = muteRepository.findByUserId(userId).stream()
                .map(m -> m.getChallengeId().toString()).toList();

        return new NotificationSettingDtos.Response(types, muted,
                agreementService.hasIndividualConsent(userId, AgreementType.MARKETING));
    }

    /**
     * 설정 변경. 마케팅(C)은 <b>약관 동의 상태까지 같은 트랜잭션에서 갱신</b>한다 —
     * 알림 설정과 동의 이력이 어긋나면 어느 쪽이 진짜인지 알 수 없게 된다.
     */
    @Transactional
    public NotificationSettingDtos.Response patchSettings(UUID userId,
                                                          NotificationSettingDtos.PatchRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Instant now = Instant.now();

        if (request.types() != null) {
            for (NotificationSettingDtos.TypeToggle toggle : request.types()) {
                NotificationType type = NotificationType.find(toggle.type())
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
                // 필수(A)는 끌 수 없다. 정상 경로에선 토글 자체가 미노출이므로 여기 오면 버그다.
                if (!type.isTogglable())
                    throw new BusinessException(ErrorCode.NOTIFICATION_TYPE_NOT_TOGGLABLE);
                settingRepository.findById(new NotificationSetting.Key(userId, type.name()))
                        .ifPresentOrElse(
                                s -> s.apply(toggle.enabled(), now),
                                () -> settingRepository.save(
                                        NotificationSetting.of(userId, type, toggle.enabled(), now)));
            }
        }

        if (request.mutedChallengeIds() != null) replaceMutes(userId, request.mutedChallengeIds(), now);

        if (request.marketing() != null) {
            User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
            agreementService.record(user, AgreementType.MARKETING, request.marketing(),
                    props.client().termsVersions().marketing(), now);
        }
        return settings(userId);
    }

    /** 음소거는 전체 교체다 — 부분 갱신으로 두면 해제가 누락됐는지 클라이언트가 알 수 없다. */
    private void replaceMutes(UUID userId, List<String> challengeIds, Instant now) {
        muteRepository.deleteAll(muteRepository.findByUserId(userId));
        muteRepository.flush();
        for (String raw : challengeIds) {
            try {
                muteRepository.save(NotificationMute.of(userId, UUID.fromString(raw), now));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // ===== 커서 =====

    /**
     * {@code createdAt(epochMilli):id} 복합 커서를 base64url 로 감싼 불투명 문자열.
     *
     * <p>구분자를 날것으로 노출하면 클라이언트마다 URL 인코딩을 다르게 처리해 조용히 깨진다.
     * 감싸 두면 쿼리스트링에 그대로 실을 수 있고, 형식이 바뀌어도 클라이언트가 영향을 받지 않는다.
     */
    private record Cursor(Instant at, UUID id) {

        private static final java.util.Base64.Encoder ENC = java.util.Base64.getUrlEncoder().withoutPadding();
        private static final java.util.Base64.Decoder DEC = java.util.Base64.getUrlDecoder();

        static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) return new Cursor(null, null);
            try {
                String decoded = new String(DEC.decode(raw), java.nio.charset.StandardCharsets.UTF_8);
                int sep = decoded.indexOf(':');
                if (sep < 0) throw new IllegalArgumentException("구분자 없음");
                return new Cursor(Instant.ofEpochMilli(Long.parseLong(decoded.substring(0, sep))),
                        UUID.fromString(decoded.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.CURSOR_INVALID);
            }
        }

        static String encode(Notification last) {
            String raw = last.getCreatedAt().toEpochMilli() + ":" + last.getId();
            return ENC.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
