package com.scaffold.agentlog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

/**
 * JDBC auto-instrumentation. Wraps a {@link DataSource} (or {@link Connection})
 * so that statement execution is recorded as a {@code db_query} auto-event.
 *
 * <p>The heavy {@code Connection}/{@code Statement} interfaces are satisfied with
 * {@link Proxy}, so only the statement-returning and execute methods need special
 * handling; everything else is delegated transparently.
 */
public final class JdbcInstrumentation {

    private JdbcInstrumentation() {
    }

    /** Wrap a DataSource; its connections (and their statements) are instrumented. */
    public static DataSource wrap(DataSource ds) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcInstrumentation.class.getClassLoader(),
                new Class<?>[] { DataSource.class },
                new DataSourceHandler(ds));
    }

    /** Wrap a single Connection; its statements are instrumented. */
    public static Connection wrap(Connection conn) {
        return (Connection) Proxy.newProxyInstance(
                JdbcInstrumentation.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                new ConnectionHandler(conn));
    }

    /** Emit a db_query auto-event directly (for drivers / custom code paths). */
    public static void recordDbQuery(String operation, String sql, long durationMs,
                                     boolean success, long rowsAffected, Throwable error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("query", sql);
        payload.put("duration_ms", durationMs);
        payload.put("success", success);
        payload.put("rows_affected", rowsAffected);
        payload.put("error", error == null ? null
                : error.getClass().getName() + ": " + error.getMessage());
        AgentLog.recordAuto(CoreTypes.EventType.DB_QUERY.raw(),
                operation + ": " + sql, "auto", null, payload);
    }

    // ----------------------------------------------------------- handlers

    private static final class DataSourceHandler implements InvocationHandler {
        private final DataSource target;

        DataSourceHandler(DataSource target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getConnection".equals(method.getName())) {
                Connection conn = (Connection) method.invoke(target, args);
                return wrap(conn);
            }
            return method.invoke(target, args);
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(target, args);
            if (result instanceof Statement && isStatementFactory(method.getName())
                    && args != null && args.length > 0 && args[0] instanceof String) {
                // PreparedStatement / CallableStatement: SQL is bound at prepare time.
                return wrapStatement((Statement) result, (String) args[0]);
            }
            if (result instanceof Statement && "createStatement".equals(method.getName())) {
                return wrapStatement((Statement) result, null);
            }
            return result;
        }

        private static boolean isStatementFactory(String name) {
            return "prepareStatement".equals(name) || "prepareCall".equals(name);
        }
    }

    private static final class StatementHandler implements InvocationHandler {
        private final Statement target;
        private final String boundSql;

        StatementHandler(Statement target, String boundSql) {
            this.target = target;
            this.boundSql = boundSql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (isExecuteMethod(name)) {
                String sql = boundSql;
                if (sql == null && args != null) {
                    for (Object a : args) {
                        if (a instanceof String) {
                            sql = (String) a;
                            break;
                        }
                    }
                }
                long start = System.nanoTime();
                try {
                    Object r = method.invoke(target, args);
                    long durMs = (System.nanoTime() - start) / 1_000_000;
                    recordDbQuery(name, sql, durMs, true, rowsFrom(name, r), null);
                    return r;
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    long durMs = (System.nanoTime() - start) / 1_000_000;
                    recordDbQuery(name, sql, durMs, false, 0, cause);
                    throw cause;
                }
            }
            return method.invoke(target, args);
        }

        private static boolean isExecuteMethod(String name) {
            return "execute".equals(name)
                    || "executeQuery".equals(name)
                    || "executeUpdate".equals(name)
                    || "executeLargeUpdate".equals(name)
                    || "executeBatch".equals(name);
        }

        private static long rowsFrom(String name, Object r) {
            if (r instanceof Number) {
                return ((Number) r).longValue();
            }
            if (r instanceof int[]) {
                long sum = 0;
                for (int v : (int[]) r) {
                    sum += v;
                }
                return sum;
            }
            if (r instanceof long[]) {
                long sum = 0;
                for (long v : (long[]) r) {
                    sum += v;
                }
                return sum;
            }
            return 0;
        }
    }

    private static Statement wrapStatement(Statement stmt, String boundSql) {
        // Mirror the target's actual interfaces (a real driver returns a
        // PreparedStatement / CallableStatement; a plain Statement returns only
        // Statement). This keeps the wrapper assignable back to the expected type.
        Class<?>[] ifaces = stmt.getClass().getInterfaces();
        return (Statement) Proxy.newProxyInstance(
                JdbcInstrumentation.class.getClassLoader(),
                ifaces,
                new StatementHandler(stmt, boundSql));
    }
}
