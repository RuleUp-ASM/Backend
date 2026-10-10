package com.ruleup.ruleup_backend.admin.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 관리자 요청을 처리하는 동안 실제로 실행된 SQL 에서 「테이블 × 권한」을 모은다.
 *
 * <p>관리자 서비스의 DB 계정 권한(infra/admin-split/db/admin-grants.txt)을 <b>추측이 아니라 실행 근거로</b>
 * 정하고, 이후 관리자 기능이 새 테이블을 건드리면 시험이 깨져 권한 목록을 갱신하게 만든다
 * ({@link #assertWithinGrants()}). 커밋 직후 콜백(알림 적재·아웃박스 즉시 발행)도 같은 스레드에서 돌므로 함께 잡힌다.
 *
 * <p>잡는 범위: {@code /api/v1/admin/**} 요청의 핸들러 실행 구간. 인증 필터의 사용자 조회는 users SELECT 라
 * 목록에 어차피 들어 있다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AdminSqlCapture {

    static final Path GRANTS = Path.of("infra/admin-split/db/admin-grants.txt");

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    /** 테이블 → 권한(SELECT·INSERT·UPDATE·DELETE). 스위트 전체에서 누적한다. */
    static final Map<String, Set<String>> CAPTURED = new ConcurrentHashMap<>();

    private static final Pattern INSERT = Pattern.compile("^\\s*(?:insert|replace)\\s+(?:ignore\\s+)?into\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE = Pattern.compile("^\\s*update\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE = Pattern.compile("^\\s*delete\\s+(?:\\w+\\s+)?from\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern READ = Pattern.compile("\\b(?:from|join)\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DDL = Pattern.compile("^\\s*(?:create|alter|drop|truncate|rename|grant)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IGNORED = Set.of("dual", "information_schema", "select", "json_table", "lateral");

    @Bean
    static BeanPostProcessor adminSqlCaptureDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof CapturingDataSource)) return new CapturingDataSource(ds);
                return bean;
            }
        };
    }

    @Bean
    WebMvcConfigurer adminSqlCaptureScope() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                        ACTIVE.set(true);
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object h, Exception e) {
                        ACTIVE.remove();
                    }
                }).addPathPatterns("/api/v1/admin/**");
            }
        };
    }

    static void record(String sql) {
        if (!ACTIVE.get() || sql == null) return;
        // 테이블명은 원문 그대로 둔다 — RDS(Linux)의 MySQL 은 테이블명 대소문자를 구분하므로 GRANT 도 원문 이름이어야 한다.
        String s = sql.replaceAll("\\s+", " ");
        String lower = s.toLowerCase(Locale.ROOT);
        if (DDL.matcher(s).find()) {
            CAPTURED.computeIfAbsent("!DDL!", k -> ConcurrentHashMap.newKeySet()).add(s);
            return;
        }
        add(INSERT, s, "INSERT");
        if (lower.contains(" on duplicate key update ")) add(INSERT, s, "UPDATE");
        add(UPDATE, s, "UPDATE");
        add(DELETE, s, "DELETE");
        Matcher m = READ.matcher(s);
        while (m.find()) put(m.group(1), "SELECT");
        // SELECT ... FOR UPDATE 는 MySQL 8 에서 SELECT 와 함께 UPDATE(또는 DELETE·LOCK TABLES)를 요구한다
        if (lower.startsWith("select") && lower.contains(" for update")) {
            Matcher r = READ.matcher(s);
            while (r.find()) put(r.group(1), "UPDATE");
        }
    }

    private static void add(Pattern p, String s, String privilege) {
        Matcher m = p.matcher(s);
        if (m.find()) put(m.group(1), privilege);
    }

    private static void put(String table, String privilege) {
        if (IGNORED.contains(table.toLowerCase(Locale.ROOT))) return;
        CAPTURED.computeIfAbsent(table, k -> ConcurrentHashMap.newKeySet()).add(privilege);
    }

    /** 잡힌 권한이 권한 목록을 넘지 않는지. 넘으면 무엇을 추가해야 하는지 그대로 알려 준다. */
    public static void assertWithinGrants() throws IOException {
        StringBuilder dump = new StringBuilder();
        new TreeMap<>(CAPTURED).forEach((t, ps) -> dump.append(t).append(": ").append(String.join(",", new TreeSet<>(ps))).append('\n'));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/admin-sql-capture.txt"), dump);
        Map<String, Set<String>> granted = readGrants();
        Map<String, Set<String>> missing = new TreeMap<>();
        CAPTURED.forEach((table, privileges) -> {
            for (String p : privileges) {
                if (!granted.getOrDefault(table, Set.of()).contains(p))
                    missing.computeIfAbsent(table, k -> new TreeSet<>()).add(p);
            }
        });
        if (!missing.isEmpty()) {
            throw new AssertionError("관리자 기능이 " + GRANTS + " 에 없는 권한을 썼다(DDL 은 절대 불가) — 기능이 맞다면 목록에 추가하고 "
                    + "infra/admin-split/db/grants.sql 을 다시 만든다: " + missing);
        }
    }

    static Map<String, Set<String>> readGrants() throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String line : Files.readAllLines(GRANTS)) {
            String l = line.replaceAll("#.*", "").trim();
            if (l.isEmpty()) continue;
            String[] parts = l.split(":");
            Set<String> ps = new TreeSet<>();
            for (String p : parts[1].split(",")) ps.add(p.trim().toUpperCase(Locale.ROOT));
            out.put(parts[0].trim(), ps);
        }
        return out;
    }

    /** 커넥션에서 나가는 SQL 문자열만 엿본다. 나머지는 그대로 위임한다. */
    static final class CapturingDataSource extends DelegatingDataSource implements AutoCloseable {
        CapturingDataSource(DataSource target) {
            super(target);
        }

        /** 컨텍스트가 닫힐 때 원본 풀도 닫히게 — 감싼 쪽에 close 가 없으면 스프링이 풀을 닫지 않아 커넥션이 샌다. */
        @Override
        public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable c) c.close();
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        private static Connection wrap(Connection c) {
            return (Connection) Proxy.newProxyInstance(AdminSqlCapture.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String n = method.getName();
                        if ((n.equals("prepareStatement") || n.equals("prepareCall")) && args != null && args[0] instanceof String sql)
                            record(sql);
                        Object result;
                        try {
                            result = method.invoke(c, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        if (n.equals("createStatement") && result instanceof Statement st) return wrapStatement(st);
                        return result;
                    });
        }

        private static Statement wrapStatement(Statement st) {
            return (Statement) Proxy.newProxyInstance(AdminSqlCapture.class.getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("execute") && args != null && args.length > 0 && args[0] instanceof String sql)
                            record(sql);
                        if (method.getName().equals("addBatch") && args != null && args[0] instanceof String sql) record(sql);
                        try {
                            return method.invoke(st, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
