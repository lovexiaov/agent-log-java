package com.scaffold.agentlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

/**
 * Verifies JDBC auto-instrumentation records db_query auto-events, both through
 * the DataSource/Connection/Statement proxy wrapper and the direct helper.
 */
public class JdbcInstrumentationTests {

    private static DataSource fakeDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                JdbcInstrumentationTests.class.getClassLoader(),
                new Class<?>[] { DataSource.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getConnection":
                            return fakeConnection();
                        case "isWrapperFor":
                            return false;
                        case "unwrap":
                            return null;
                        default:
                            return null;
                    }
                });
    }

    private static Connection fakeConnection() {
        return (Connection) Proxy.newProxyInstance(
                JdbcInstrumentationTests.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "prepareStatement":
                        case "prepareCall":
                            return fakeStatement(args != null && args.length > 0
                                    && args[0] instanceof String ? (String) args[0] : null);
                        case "createStatement":
                            return fakeStatement(null);
                        case "close":
                            return null;
                        case "isWrapperFor":
                            return false;
                        case "unwrap":
                            return null;
                        default:
                            return null;
                    }
                });
    }

    private static Statement fakeStatement(String sql) {
        return (Statement) Proxy.newProxyInstance(
                JdbcInstrumentationTests.class.getClassLoader(),
                new Class<?>[] { java.sql.PreparedStatement.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "execute":
                            return Boolean.TRUE;
                        case "executeQuery":
                            return null;
                        case "executeUpdate":
                            return Integer.valueOf(1);
                        case "executeLargeUpdate":
                            return Long.valueOf(1L);
                        case "close":
                            return null;
                        case "isWrapperFor":
                            return false;
                        case "unwrap":
                            return null;
                        default:
                            return null;
                    }
                });
    }

    @Test
    public void wrapDataSourceRecordsDbQuery() throws SQLException {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("test-svc");
        cfg.setLevel(Level.INFO);
        AgentLog.bootstrap(cfg, sink);

        DataSource ds = JdbcInstrumentation.wrap(fakeDataSource());
        try (Connection conn = ds.getConnection()) {
            try (Statement stmt = conn.prepareStatement("SELECT * FROM t")) {
                stmt.execute("SELECT * FROM t");
            }
        }

        boolean found = false;
        for (String line : sink.lines()) {
            Map<String, Object> o = Json.parseObject(line);
            if ("db_query".equals(o.get("event_type"))) {
                found = true;
                assertEquals(true, o.get("agent_auto"));
                Map<?, ?> p = (Map<?, ?>) o.get("payload");
                assertEquals("execute", p.get("operation"));
                assertEquals("SELECT * FROM t", p.get("statement"));
                assertEquals(true, p.get("success"));
            }
        }
        assertTrue(found, "expected a db_query event from wrapped DataSource");
    }

    @Test
    public void recordDbQueryOnError() {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("test-svc");
        cfg.setLevel(Level.INFO);
        AgentLog.bootstrap(cfg, sink);

        JdbcInstrumentation.recordDbQuery("exec", "UPDATE t SET x = 1", 5, false, 0,
                new SQLException("connection refused"));

        Map<String, Object> o = Json.parseObject(sink.lines().get(0));
        assertEquals("db_query", o.get("event_type"));
        assertEquals(true, o.get("agent_auto"));
        Map<?, ?> p = (Map<?, ?>) o.get("payload");
        assertEquals(false, p.get("success"));
        assertTrue(p.get("error").toString().contains("connection refused"));
    }
}
