package com.ruleup.ruleup_backend.routine.service;

import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.match.RoutineCandidate;
import com.ruleup.ruleup_backend.routine.repository.RoutineTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 루틴 템플릿 카탈로그(메모리 캐시).
 *
 * 템플릿은 자동 인증 가능 루틴만 남긴 65개 수준의 정적 데이터라 매 요청마다 DB 를 칠 이유가 없다(V18).
 * 처음 쓸 때 한 번 통째로 읽어 캐시하고, 이후엔 메모리에서 본다(목표 2만 사용자 규모에 충분).
 * 시드를 갱신했으면 앱 재시작으로 캐시를 비운다.
 *
 * (의미 기반 매칭으로 키우려면: 여기서 FULLTEXT/임베딩으로 후보를 좁힌 뒤 LLM 에 넘기면 된다.
 *  지금은 전체를 후보로 준다 — 105개면 프롬프트도 작고 매칭 품질이 더 안정적이다.)
 */
@Component
@RequiredArgsConstructor
public class RoutineCatalog {

    private final RoutineTemplateRepository templateRepository;

    private volatile Map<Long, RoutineTemplate> byId;   // null = 아직 안 읽음

    /** id 로 템플릿 조회(캐시). */
    public Optional<RoutineTemplate> findById(Long id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(cache().get(id));
    }

    /** LLM 에 줄 후보 목록(전체 템플릿). */
    public List<RoutineCandidate> candidates() {
        // 자동 인증이 가능한 루틴만 후보다. 초안 프롬프트가 이 목록을 "자동 인증이 가능한 루틴의
        // 전체 목록"으로 제시하므로, 수동 전용 템플릿이 섞이면 그 전제가 깨진다.
        return cache().values().stream()
                .filter(RoutineTemplate::supportsAuto)
                .map(t -> new RoutineCandidate(
                        t.getId(), t.getName(), t.getCategory().name(), t.getDescription(),
                        t.paramSpecs().stream().map(s -> s.key()).toList()))
                .toList();
    }

    /** 캐시 무효화 — 시드/템플릿 변경 시(운영 반영·테스트 픽스처) 다음 조회에서 다시 읽는다. */
    public void evict() {
        this.byId = null;
    }

    private Map<Long, RoutineTemplate> cache() {
        Map<Long, RoutineTemplate> local = byId;
        if (local == null) {
            synchronized (this) {
                local = byId;
                if (local == null) {
                    local = load();
                    byId = local;
                }
            }
        }
        return local;
    }

    @Transactional(readOnly = true)
    protected Map<Long, RoutineTemplate> load() {
        return templateRepository.findAllWithVerification().stream()   // 인증 정의까지 fetch join
                .collect(Collectors.toMap(RoutineTemplate::getId, Function.identity(),
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }
}