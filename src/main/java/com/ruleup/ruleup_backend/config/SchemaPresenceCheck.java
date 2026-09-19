package com.ruleup.ruleup_backend.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 기동 시 스키마 존재 확인 — 엔티티가 매핑한 테이블·컬럼이 DB 에 <b>있는지만</b> 본다.
 *
 * <h4>왜 필요한가</h4>
 * 2026-09-15 stg 에서 코드는 {@code approved_profile_image_url} 을 기대하는데 DB 에는 아직 없어
 * refresh 가 연속 500 이었다(QA AUTH-06·09). 앱은 멀쩡히 떠서 헬스체크를 통과했고, 그래서 ECS 배포
 * 회로차단기(rollback=true)가 이 배포를 되돌릴 이유를 찾지 못했다. 스키마가 모자라면
 * <b>뜨지 않는 것</b>이 맞다 — 기동 실패는 회로차단기가 이전 태스크로 되돌리고, 그 사이 기존 태스크가
 * 계속 요청을 받는다.
 *
 * <h4>왜 {@code ddl-auto: validate} 가 아닌가</h4>
 * 타입까지 비교해서 지금 스키마로도 뜨지 않는다(예: {@code tinyint} 컬럼 ↔ {@code Integer} 필드).
 * 그 차이는 동작에 문제가 없고, 사고를 낸 것은 <b>없는 컬럼</b>이었다. 여기서는 존재만 본다.
 *
 * <p>JdbcTemplate 로만 쓰는 표는 엔티티가 없어 여기서 잡히지 않는다. Flyway 가 같은 기동에서 먼저
 * 돌고, 적용에 실패하면 기동이 멈추므로 새 마이그레이션 자체는 이미 보호된다 — 남는 구멍은
 * 적용된 파일을 고쳐 새 컬럼이 반영되지 않는 경우다(그 규칙은 마이그레이션 쪽에 있다).
 */
@Component
public class SchemaPresenceCheck implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SchemaPresenceCheck.class);

    private final EntityManagerFactory emf;
    private final JdbcTemplate jdbc;

    public SchemaPresenceCheck(EntityManagerFactory emf, JdbcTemplate jdbc) {
        this.emf = emf;
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> missing = missing();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("DB 스키마에 엔티티가 기대하는 테이블·컬럼이 없다 — 마이그레이션이 "
                    + "적용되지 않았거나 적용된 파일이 수정됐다: " + missing);
        }
        log.info("schema_presence_ok");
    }

    /** 없는 {@code 테이블} 또는 {@code 테이블.컬럼}. 대소문자는 구분하지 않는다. */
    public Set<String> missing() {
        Map<String, Set<String>> actual = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()");
        for (Map<String, Object> row : rows) {
            actual.computeIfAbsent(lower(row.get("TABLE_NAME")), k -> new HashSet<>()).add(lower(row.get("COLUMN_NAME")));
        }

        Set<String> missing = new TreeSet<>();
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        sf.getMappingMetamodel().forEachEntityDescriptor((EntityPersister persister) ->
                persister.forEachSelectable((index, selectable) -> {
                    if (selectable.isFormula()) return;
                    String table = unquote(selectable.getContainingTableExpression());
                    Set<String> columns = actual.get(table);
                    if (columns == null) missing.add(table);
                    else if (!columns.contains(unquote(selectable.getSelectionExpression()))) {
                        missing.add(table + "." + unquote(selectable.getSelectionExpression()));
                    }
                }));
        return missing;
    }

    private static String lower(Object value) {
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static String unquote(String name) {
        return name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }
}
