package com.scaffold.agentlog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core logger. Conforms to the language-neutral contract:
 * <ul>
 *   <li>{@link #emit(LogEvent)} — filter, redact, write.</li>
 *   <li>{@link #child(Map)} — returns a NEW logger merging scope fields; parent unchanged.</li>
 *   <li>{@link #setLevel(Level)} — set minLevel.</li>
 *   <li>{@link #redact(List)} — APPEND-only sensitive keys.</li>
 * </ul>
 */
public final class Logger {

    private final String service;
    private final String sessionId;
    private final Map<String, String> context; // scope: env, subsystem, userId, traceId
    private final Redactor redactor;
    private final LogSink sink;
    private final boolean redactEnabled;

    private Level minLevel;

    Logger(String service,
           String sessionId,
           Map<String, String> context,
           Level minLevel,
           Redactor redactor,
           LogSink sink,
           boolean redactEnabled) {
        this.service = service;
        this.sessionId = sessionId;
        this.context = context;
        this.minLevel = minLevel;
        this.redactor = redactor;
        this.sink = sink;
        this.redactEnabled = redactEnabled;
    }

    /** Bootstrap a root logger from config + sink. */
    public static Logger create(LoggingConfig config, LogSink sink) {
        Redactor redactor = new Redactor();
        if (config.getRedactPaths() != null) {
            redactor.addPaths(config.getRedactPaths());
        }
        Map<String, String> ctx = new LinkedHashMap<>();
        if (config.getEnv() != null) {
            ctx.put("env", config.getEnv());
        }
        return new Logger(
                config.getService(),
                CoreTypes.Session.id(),
                ctx,
                config.getLevel(),
                redactor,
                sink,
                config.isRedactEnabled());
    }

    // ----------------------------------------------------------- core: emit

    /**
     * Filter (manual events only), redact (if enabled), and write the event.
     */
    public void emit(LogEvent event) {
        if (!event.isAgentAuto() && event.getLevel().weight() < minLevel.weight()) {
            return; // dropped by minLevel
        }
        if (redactEnabled) {
            String msg = redactor.redactString(event.getMessage());
            event.setMessage(msg);
            if (event.getPayload() != null) {
                event.setPayload(redactor.redactMap(event.getPayload()));
            }
        }
        sink.write(event.toJsonLine());
    }

    // --------------------------------------------------------- convenience

    public void trace(String message) {
        log(Level.TRACE, message, "app", null, null);
    }

    public void debug(String message) {
        log(Level.DEBUG, message, "app", null, null);
    }

    public void info(String message) {
        log(Level.INFO, message, "app", null, null);
    }

    public void warn(String message) {
        log(Level.WARN, message, "app", null, null);
    }

    public void error(String message) {
        log(Level.ERROR, message, "app", null, null);
    }

    public void critical(String message) {
        log(Level.CRITICAL, message, "app", null, null);
    }

    public void info(String message, String category, String traceId, Map<String, Object> payload) {
        log(Level.INFO, message, category, traceId, payload);
    }

    public void warn(String message, String category, String traceId, Map<String, Object> payload) {
        log(Level.WARN, message, category, traceId, payload);
    }

    public void error(String message, String category, String traceId, Map<String, Object> payload) {
        log(Level.ERROR, message, category, traceId, payload);
    }

    public void debug(String message, String category, String traceId, Map<String, Object> payload) {
        log(Level.DEBUG, message, category, traceId, payload);
    }

    public void trace(String message, String category, String traceId, Map<String, Object> payload) {
        log(Level.TRACE, message, category, traceId, payload);
    }

    public void critical(String message, String category, String traceId, Map<String, Object> payload) {
        log(Level.CRITICAL, message, category, traceId, payload);
    }

    private void log(Level level, String message, String category,
                     String traceId, Map<String, Object> payload) {
        emit(buildEvent(level, CoreTypes.EventType.LOG.raw(), false, message,
                category, traceId, payload));
    }

    /**
     * Record an auto-instrumented event ({@code agent_auto=true}, level=info).
     * Auto events are ALWAYS logged regardless of minLevel.
     */
    public void recordAuto(String eventType, String message, String category,
                           String traceId, Map<String, Object> payload) {
        emit(buildEvent(Level.INFO, eventType, true, message, category, traceId, payload));
    }

    private LogEvent buildEvent(Level level, String eventType, boolean agentAuto,
                                String message, String category, String traceId,
                                Map<String, Object> payload) {
        LogEvent e = new LogEvent();
        e.setTs(now());
        e.setLevel(level);
        e.setService(service);
        e.setEventType(eventType);
        e.setSessionId(sessionId);
        e.setAgentAuto(agentAuto);
        e.setMessage(message == null ? "" : message);

        if (category != null && !category.isEmpty()) {
            e.setCategory(category);
        }
        String effectiveTraceId = traceId != null ? traceId : context.get("traceId");
        if (effectiveTraceId != null && !effectiveTraceId.isEmpty()) {
            e.setTraceId(effectiveTraceId);
        }
        String env = context.get("env");
        if (env != null && !env.isEmpty()) {
            e.setEnv(env);
        }
        String subsystem = context.get("subsystem");
        if (subsystem != null && !subsystem.isEmpty()) {
            e.setSubsystem(subsystem);
        }
        String userId = context.get("userId");
        if (userId != null && !userId.isEmpty()) {
            e.setUserId(userId);
        }
        if (payload != null && !payload.isEmpty()) {
            e.setPayload(new LinkedHashMap<>(payload));
        }
        return e;
    }

    private static String now() {
        // RFC3339 / ISO8601: "2026-11-07T13:37:21.123Z"
        return java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .toString();
    }

    // ------------------------------------------------------------- child

    /**
     * Return a NEW logger that merges {@code context} into the bound scope.
     * The parent is NOT mutated.
     *
     * @param context reserved scope keys (camelCase): userId, sessionId,
     *                traceId, subsystem, env, category. Every other key is ignored
     *                here (it belongs in payload at log time).
     */
    public Logger child(Map<String, Object> context) {
        Map<String, String> merged = new LinkedHashMap<>(this.context);
        if (context != null) {
            putIfString(merged, "userId", context.get("userId"));
            putIfString(merged, "sessionId", context.get("sessionId"));
            putIfString(merged, "traceId", context.get("traceId"));
            putIfString(merged, "subsystem", context.get("subsystem"));
            putIfString(merged, "env", context.get("env"));
        }
        return new Logger(service, sessionId, merged, minLevel, redactor, sink, redactEnabled);
    }

    private static void putIfString(Map<String, String> m, String key, Object value) {
        if (value instanceof String && !((String) value).isEmpty()) {
            m.put(key, (String) value);
        }
    }

    // ---------------------------------------------------------- mutators

    public void setLevel(Level level) {
        this.minLevel = level;
    }

    public Level getLevel() {
        return minLevel;
    }

    /** Append-only: add sensitive keys/paths. Defaults are never removed. */
    public void redact(List<String> paths) {
        redactor.addPaths(paths);
    }

    public boolean isRedactEnabled() {
        return redactEnabled;
    }

    public String getSessionId() {
        return sessionId;
    }

    public LogSink getSink() {
        return sink;
    }

    /** Convenience: if the sink is a FileSink, return its export path. */
    public String exportPath() {
        if (sink instanceof FileSink) {
            return ((FileSink) sink).exportPath();
        }
        return null;
    }
}
