package com.ruleup.ruleup_backend.observability;

import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.stats.MemberProfileService;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 회원 구성 게이지 {@code biz.members.*} — 관리자 콘솔 「회원 구성」과 같은 값({@link MemberProfileService})을 CloudWatch 로.
 *
 * <p>콘솔은 지금 한 장면이고, 이건 <b>구성이 어떻게 변해 가는가</b>를 본다(대시보드 ruleup-&lt;env&gt;-business ④).
 * 칸은 분포를 읽는 데 필요한 것만 둔다 — 태그 조합 하나가 CloudWatch 지표 하나로 과금되므로 앱 버전·OS·국가처럼
 * 값이 열려 있는 칸은 콘솔에만 있다. 지금 31개다.
 * <ul>
 *   <li>{@code biz.members.total} — 회원 수</li>
 *   <li>{@code biz.members.age{band}} — 10s·20s·30s·40s·50s_plus·unknown (만 나이, 10s 는 14~19)</li>
 *   <li>{@code biz.members.gender{gender}} — male·female·non_binary·undisclosed(밝히지 않음 + 미입력)</li>
 *   <li>{@code biz.members.provider{provider}} — kakao·naver·google·apple</li>
 *   <li>{@code biz.members.interest{category}} — 관심사 12종(복수 선택)</li>
 *   <li>{@code biz.members.consent{type}} — 선택 동의(marketing·event)에 동의한 회원 수</li>
 *   <li>{@code biz.members.active{window}} — 최근 1d·7d 안에 활동한 회원 수</li>
 * </ul>
 *
 * <p>구성은 천천히 변하므로 10분마다 센다. 읽기 전용 집계라 태스크마다 같은 값을 내 ShedLock 을 걸지 않는다
 * ({@link VerificationRateMetrics} 와 같다). 대시보드는 Maximum 으로 본다.
 */
@Slf4j
@Component
public class MemberProfileMetrics {

    private static final Map<String, String> AGE_TAGS = Map.of(
            "10", "10s", "20", "20s", "30", "30s", "40", "40s", "50+", "50s_plus", "UNKNOWN", "unknown");
    private static final List<String> GENDER_TAGS = List.of("male", "female", "non_binary", "undisclosed");
    private static final List<String> CONSENT_TAGS = List.of("marketing", "event");

    private final MemberProfileService profileService;
    private final AtomicLong total = new AtomicLong();
    private final Map<String, AtomicLong> age = new LinkedHashMap<>();
    private final Map<String, AtomicLong> gender = new LinkedHashMap<>();
    private final Map<String, AtomicLong> provider = new LinkedHashMap<>();
    private final Map<String, AtomicLong> interest = new LinkedHashMap<>();
    private final Map<String, AtomicLong> consent = new LinkedHashMap<>();
    private final AtomicLong active1d = new AtomicLong();
    private final AtomicLong active7d = new AtomicLong();

    public MemberProfileMetrics(MemberProfileService profileService, MeterRegistry registry) {
        this.profileService = profileService;
        Gauge.builder("biz.members.total", total, AtomicLong::doubleValue)
                .description("회원 수 — 탈퇴·운영자·제외 계정 빼고").register(registry);
        for (String band : AGE_TAGS.values().stream().sorted().toList())
            register(registry, "biz.members.age", "band", band, age, "나잇대별 회원 수(만 나이)");
        for (String g : GENDER_TAGS)
            register(registry, "biz.members.gender", "gender", g, gender, "성별 회원 수");
        for (OAuthProvider p : OAuthProvider.values())
            register(registry, "biz.members.provider", "provider", tag(p), provider, "가입 소셜별 회원 수");
        for (InterestCategory c : InterestCategory.values())
            register(registry, "biz.members.interest", "category", tag(c), interest, "관심사별 회원 수 — 복수 선택");
        for (String t : CONSENT_TAGS)
            register(registry, "biz.members.consent", "type", t, consent, "선택 동의에 동의한 회원 수");
        Gauge.builder("biz.members.active", active1d, AtomicLong::doubleValue)
                .description("최근 N일 안에 활동한 회원 수").tag("window", "1d").register(registry);
        Gauge.builder("biz.members.active", active7d, AtomicLong::doubleValue)
                .description("최근 N일 안에 활동한 회원 수").tag("window", "7d").register(registry);
    }

    private static void register(MeterRegistry registry, String name, String key, String value,
                                 Map<String, AtomicLong> into, String description) {
        AtomicLong holder = new AtomicLong();
        into.put(value, holder);
        Gauge.builder(name, holder, AtomicLong::doubleValue).description(description).tag(key, value).register(registry);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 90_000)
    public void refresh() {
        try {
            apply(profileService.snapshot());
        } catch (RuntimeException e) {
            // 지표 갱신 실패가 아무것도 막으면 안 된다. 다음 주기가 다시 센다.
            log.warn("회원 구성 지표 갱신 실패: {}", e.toString());
        }
    }

    void apply(AdminDtos.MemberProfile p) {
        total.set(p.members());
        p.ageDecades().forEach(b -> set(age, AGE_TAGS.get(b.key()), b.count()));

        Map<String, Long> genders = new LinkedHashMap<>();
        p.genders().forEach(b -> genders.merge(switch (b.key()) {
            case "MALE", "FEMALE", "NON_BINARY" -> b.key().toLowerCase(Locale.ROOT);
            default -> "undisclosed";
        }, b.count(), Long::sum));
        GENDER_TAGS.forEach(g -> set(gender, g, genders.getOrDefault(g, 0L)));

        p.providers().forEach(b -> set(provider, b.key().toLowerCase(Locale.ROOT), b.count()));
        p.interests().forEach(b -> set(interest, b.key().toLowerCase(Locale.ROOT), b.count()));
        p.consents().forEach(c -> set(consent, c.type().toLowerCase(Locale.ROOT), c.agreed()));
        active1d.set(p.activity().last1d());
        active7d.set(p.activity().last7d());
    }

    /** 미리 만든 칸만 채운다 — 열거에 없는 값(옛 값)으로 새 지표가 생기면 과금 단위가 몰래 늘어난다. */
    private static void set(Map<String, AtomicLong> gauges, String key, long value) {
        AtomicLong holder = key == null ? null : gauges.get(key);
        if (holder != null) holder.set(value);
    }

    private static String tag(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}
