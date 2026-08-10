package com.ruleup.ruleup_backend.routine;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.routine.domain.ParamSpec;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.repository.RoutineTemplateRepository;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 루틴 카탈로그 시드(V9) 계약 테스트.
 *
 * <p>시드는 「루틴 테이블」 문서에서 <b>현재 자동 인증이 가능한 판정 모델(§1~§7)</b>만 담는다.
 * 판정기·신호 확장이 필요한 모델(§8~§13)과 자동 인증에 부적합한 루틴(§14)이 섞이면
 * 초안 프롬프트가 후보 표를 "자동 인증이 가능한 루틴의 전체 목록"으로 제시하는 전제가 깨지고,
 * 사용자는 판정되지 않는 방을 만들게 된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoutineCatalogSeedIT {

    /** 시드가 쓰는 id 대역(1001~). 다른 테스트가 넣는 픽스처와 섞이지 않게 이 범위만 본다. */
    private static final long SEED_FROM = 1001L;
    private static final long SEED_TO = 1699L;

    /** verificationMethod → 그 판정기가 실제로 읽는 목표값 키(VerificationConfigFactory 기준). */
    private static final Map<String, Set<String>> EXPECTED_PARAM_KEYS = Map.of(
            "GPS_PRESENCE", Set.of("duration_min"),
            "GPS_AVOID", Set.of("duration_min"),
            "SCREEN_TIME_MAX", Set.of("duration_min"),
            "SCREEN_TIME_MIN", Set.of("duration_min"),
            "WAKE", Set.of("target_time"),
            "HEALTH", Set.of("steps", "distance_km"),
            "SLEEP", Set.of("bedtime_before", "sleep_hours"));

    @Autowired RoutineTemplateRepository templateRepository;

    private List<RoutineTemplate> seeded() {
        return templateRepository.findAllWithVerification().stream()
                .filter(t -> t.getId() >= SEED_FROM && t.getId() <= SEED_TO)
                .toList();
    }

    @Test
    @Transactional(readOnly = true)
    @DisplayName("문서 §1~§7 의 79건이 전부 자동 인증 가능한 상태로 들어간다")
    void seedIsAllAutoVerifiable() {
        List<RoutineTemplate> seeded = seeded();
        assertThat(seeded).hasSize(79);
        assertThat(seeded).allSatisfy(t -> {
            assertThat(t.supportsAuto())
                    .withFailMessage("자동 인증 정의가 없는 루틴이 시드에 섞였다: %s", t.getName())
                    .isTrue();
            assertThat(t.getAutoRequiredPermissions())
                    .withFailMessage("자동 인증인데 필요 권한이 비었다: %s", t.getName())
                    .isNotEmpty();
        });
    }

    @Test
    @Transactional(readOnly = true)
    @DisplayName("목표값 키가 그 판정기가 실제로 읽는 키와 일치한다")
    void paramKeysMatchEvaluator() {
        for (RoutineTemplate t : seeded()) {
            Set<String> allowed = EXPECTED_PARAM_KEYS.get(t.getVerificationMethod());
            assertThat(allowed)
                    .withFailMessage("판정기가 모르는 verificationMethod: %s (%s)",
                            t.getVerificationMethod(), t.getName())
                    .isNotNull();
            List<String> keys = t.paramSpecs().stream().map(ParamSpec::key).toList();
            assertThat(keys)
                    .withFailMessage("%s 의 목표값 키 %s 가 %s 판정기와 맞지 않는다",
                            t.getName(), keys, t.getVerificationMethod())
                    .isNotEmpty()
                    .allMatch(allowed::contains);
        }
    }

    @Test
    @Transactional(readOnly = true)
    @DisplayName("카테고리는 관심 카테고리 12종 안에 있다")
    void categoriesAreTheTwelve() {
        assertThat(seeded()).allSatisfy(t ->
                assertThat(InterestCategory.values()).contains(t.getCategory()));
    }

    @Test
    @Transactional(readOnly = true)
    @DisplayName("판정기·신호 확장이 필요하거나 자동 인증에 부적합한 루틴은 들어가지 않는다")
    void excludedRoutinesAreAbsent() {
        // §10 위치+시간대(문서 주석대로 단순 방문이 아님) · §6 스누즈 판정 불가 · §14 신호 없음
        List<String> mustNotExist = List.of(
                "정시 출근하기", "일찍 귀가하기", "알람 한 번에 일어나기",
                "물 2L 마시기", "영양제·약 챙겨 먹기", "종이책 30분 읽기",
                "밤 12시 이후 핸드폰 안 하기", "기상 후 1시간 SNS 안 보기");
        List<String> names = seeded().stream().map(RoutineTemplate::getName).toList();
        assertThat(names).doesNotContainAnyElementsOf(mustNotExist);
    }
}
