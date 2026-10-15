package com.ruleup.ruleup_backend.perf;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 시험 전용 — 요청 하나가 DB 에 무엇을 몇 번 하는지 센다(커넥션 대여·트랜잭션·SQL 문장).
 * 데이터소스를 감싸 Connection·Statement 호출을 기록한다. 운영 코드에는 없다.
 */
public final class SqlRecorder {

    public record Entry(String thread, String sql) {}

    private static final List<Entry> SQL = new ArrayList<>();
    private static final AtomicInteger CONNECTIONS = new AtomicInteger();
    private static final AtomicInteger COMMITS = new AtomicInteger();
    private static volatile boolean on;

    private SqlRecorder() {}

    public static synchronized void start() { SQL.clear(); CONNECTIONS.set(0); COMMITS.set(0); on = true; }
    public static synchronized List<Entry> stop() { on = false; return List.copyOf(SQL); }
    public static int connections() { return CONNECTIONS.get(); }
    public static int commits() { return COMMITS.get(); }

    private static synchronized void record(String sql) {
        if (on) SQL.add(new Entry(Thread.currentThread().getName(), sql.replaceAll("\\s+", " ").trim()));
    }

    @TestConfiguration
    public static class Config {
        @Bean
        static BeanPostProcessor sqlRecordingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof DataSource ds && !(bean instanceof Recording)) return new Recording(ds);
                    return bean;
                }
            };
        }
    }

    static final class Recording extends DelegatingDataSource {
        Recording(DataSource target) { super(target); }

        @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return wrap(super.getConnection(u, p)); }

        private static Connection wrap(Connection c) {
            if (on) CONNECTIONS.incrementAndGet();
            InvocationHandler h = (proxy, m, args) -> {
                String n = m.getName();
                if (("prepareStatement".equals(n) || "prepareCall".equals(n)) && args != null && args[0] instanceof String sql) record(sql);
                if ("commit".equals(n) && on) COMMITS.incrementAndGet();
                Object r = invoke(m, c, args);
                if (r instanceof Statement st && "createStatement".equals(n)) return statement(st);
                return r;
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
        }

        private static Statement statement(Statement st) {
            Set<String> exec = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (p, m, a) -> {
                if (exec.contains(m.getName()) && a != null && a.length > 0 && a[0] instanceof String sql) record(sql);
                return invoke(m, st, a);
            });
        }

        private static Object invoke(java.lang.reflect.Method m, Object target, Object[] args) throws Throwable {
            try { return m.invoke(target, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        }
    }
}
