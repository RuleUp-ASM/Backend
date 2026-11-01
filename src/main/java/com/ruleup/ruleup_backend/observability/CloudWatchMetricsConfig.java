package com.ruleup.ruleup_backend.observability;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 경보에 쓰는 앱 지표만 CloudWatch 로 내보낸다 (노션 「RuleUp 모니터링 설계」 2절 Micrometer).
 *
 * <p><b>허용 목록 방식</b>이다. CloudWatch 사용자 지정 지표는 이름·태그 조합마다 과금되는데, 앱은
 * JVM·HTTP 자동 지표까지 수백 개를 만든다. 그래서 {@link #EXPORTED} 에 적은 이름만 보내고 나머지는
 * 이 레지스트리에서만 거절한다 — 전역 {@code MeterFilter} 빈으로 막으면 {@code /actuator/metrics}
 * 에서도 사라지므로, 필터는 이 레지스트리 인스턴스에만 건다.
 *
 * <p>레지스트리를 빈으로 올리면 스프링 부트의 기본 {@link SimpleMeterRegistry} 가 물러나므로 둘 다 만든다.
 * 레지스트리가 둘이면 부트가 둘을 묶은 {@code CompositeMeterRegistry} 를 주 빈으로 만들어 주입한다.
 *
 * <p>네임스페이스는 {@code RuleUp/App/<프로파일>} — 인스턴스 차원이 없어서 두 태스크가 같은 시계열에
 * 쓴다. 경보는 카운터를 Sum, 게이지를 Maximum 으로 본다.
 *
 * <p>서비스 지표({@value #BUSINESS_PREFIX}*)는 prod 에서만 보낸다. stg 의 가입·로그인·인증 수는 QA 가 만든
 * 값이라 볼 일이 없고, 이름마다 과금만 된다. stg 대시보드는 서버 지표(ruleup-stg-ops)만 둔다.
 * 회원 구성(biz.members.*)도 마찬가지다 — prod 반영 전 확인용으로 stg 에서도 내보내던 예외는 확인이 끝나 지웠다.
 */
@Configuration
@Profile({"prod", "stg"})
@ConditionalOnProperty(name = "app.observability.cloudwatch.enabled", havingValue = "true", matchIfMissing = true)
public class CloudWatchMetricsConfig {

    /** CloudWatch 로 보내는 지표. 새 경보를 만들 때만 늘린다(이름 하나가 곧 월 과금 단위다). */
    static final Set<String> EXPORTED = Set.of(
            "outbox.pending.oldest_age_seconds",       // Outbox 적체 — 가장 오래 못 나간 작업
            "outbox.dead_lettered.count",              // Outbox 재시도 끝에 포기한 작업
            "verification.finalize.overdue",           // 확정 시각이 한참 지났는데 남은 판정
            "verification.finalize.no_signal",         // 신호 없이 실패로 확정된 판정
            "verification.finalize.failed",            // 건별 확정 실패
            "verification.materialize.failed",         // 무신호 귀속일 채우기 실패
            "verification.sync.failed",                // 인증 데이터 접수(sync) 서버 오류
            "llm.call",                                // LLM 호출 수·지연
            "llm.call.failed",                         // LLM 호출 최종 실패(폴백까지 실패)
            // 서비스 지표 — 서버 지표와 섞이지 않게 biz. 로 시작한다. 태그는 고정 열거뿐이다.
            "biz.signup",                              // 가입 결과(result=success|failure)
            "biz.login",                               // 로그인 결과(outcome=existing|new_user|failure)
            "biz.verification.attempt",                // 인증 시도(method=sync|manual)
            "biz.verification.today",                  // 오늘 귀속분 판정 수(status=success|pending)
            "biz.verification.finalized",              // 확정 끝난 그제 판정 수(status=success|failed) — 성공률
            "biz.verification.finalized_by_type",      // 같은 값을 인증 방식별로(type=auto|manual)
            "biz.challenge.draft",                     // 초안(origin=ai|template|clone, result=success|blocked|failure)
            "biz.challenge.created",                   // 생성(origin, edited=yes|no) — 초안 수정률
            "biz.challenge.joined",                    // 가입(source=explore|invite)
            // 회원 구성(MemberProfileMetrics) — 태그는 고정 열거, 합쳐서 37개
            "biz.members.total",                       // 회원 수
            "biz.members.age",                         // 나잇대(band=10s|20s|30s|40s|50s_plus|unknown)
            "biz.members.gender",                      // 성별(gender=male|female)
            "biz.members.provider",                    // 가입 소셜(provider=kakao|naver|google|apple)
            "biz.members.interest",                    // 관심사 12종(category)
            "biz.members.consent",                     // 선택 동의(type=marketing|event)
            "biz.members.active",                      // 최근 활동(window=1d|7d)
            "biz.members.rooms",                       // 방 참여 경로(path=created_only|joined_only|both) — 사람 수
            "biz.members.rooms_per_member",            // 참여 중인 방 개수(rooms=1|2|3|4|5_plus)
            // 서비스 이용(UsageMetrics) — 태그는 고정 열거
            "biz.rooms.count",                         // 지금 있는 방 수(mode × verification × status, 8)
            "biz.rooms.memberships",                   // 그 방들의 참여 인원 합(mode × verification, 4)
            "biz.rooms.people_by_mode",                // 그룹·솔로 방에 참여 중인 사람 수(2)
            "biz.rooms.people_by_verification",        // 자동·수동 방에 참여 중인 사람 수(2)
            "biz.auto.people",                         // 자동 인증 방 사람 수·최근 sync 보낸 사람(state, 3)
            "biz.auto.stall",                          // 자동 인증 멤버십 진행 상태(reason, 6)
            "biz.auto.judgments",                      // 확정된 최근 7일 자동 판정 결과(result, 4)
            "biz.auto.judged_people");                 // 측정된 판정이 있는 사람의 성공 경험(result, 2)

    static final String BUSINESS_PREFIX = "biz.";

    @Bean
    public SimpleMeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CloudWatchAsyncClient cloudWatchAsyncClient(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return CloudWatchAsyncClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public CloudWatchMeterRegistry cloudWatchMeterRegistry(CloudWatchAsyncClient client,
                                                           @Value("${spring.profiles.active}") String profile) {
        Map<String, String> props = Map.of(
                "cloudwatch.namespace", "RuleUp/App/" + profile,
                "cloudwatch.step", Duration.ofMinutes(1).toString());
        CloudWatchConfig config = props::get;
        CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(config, Clock.SYSTEM, client);
        registry.config()
                .meterFilter(MeterFilter.denyUnless(id -> EXPORTED.contains(id.getName())
                        && ("prod".equals(profile) || !id.getName().startsWith(BUSINESS_PREFIX))));
        return registry;
    }
}
