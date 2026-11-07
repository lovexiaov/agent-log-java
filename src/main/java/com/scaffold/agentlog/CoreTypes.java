package com.scaffold.agentlog;

import java.util.UUID;

/**
 * Language-neutral core types: stable session id, event-type vocabulary, and
 * the auto-instrumentation helper used by the facade.
 */
public final class CoreTypes {

    private CoreTypes() {
    }

    /** Event-type raw strings (snake_case). */
    public enum EventType {
        SCREEN_VIEW("screen_view"),
        UI_INTERACTION("ui_interaction"),
        NETWORK_REQUEST("network_request"),
        CRASH("crash"),
        HANG("hang"),
        DB_QUERY("db_query"),
        LOG("log");

        private final String raw;

        EventType(String raw) {
            this.raw = raw;
        }

        public String raw() {
            return raw;
        }

        /** Resolve from a raw wire string; unknown defaults to {@code log}. */
        public static EventType fromRaw(String raw) {
            if (raw == null) {
                return LOG;
            }
            for (EventType t : values()) {
                if (t.raw.equals(raw)) {
                    return t;
                }
            }
            return LOG;
        }
    }

    /**
     * Schema v1.1 span-id helpers: 16 lowercase hex chars, the same shape as the
     * W3C {@code traceparent} span-id.
     */
    public static final class SpanId {

        private SpanId() {
        }

        /**
         * Normalize a candidate span id.
         *
         * @return the lowercase 16-hex id, or {@code null} when the input is not a
         *         valid span id (callers drop invalid values instead of writing them,
         *         so a bad value can never produce a schema-violating line).
         */
        public static String normalize(String raw) {
            if (raw == null) {
                return null;
            }
            String value = raw.trim();
            if (value.length() != 16) {
                return null;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) {
                    return null;
                }
            }
            return value.toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Per-process stable session id. Generated once; identical across all loggers
     * in the same JVM process.
     */
    public static final class Session {
        private static final String ID = UUID.randomUUID().toString();

        private Session() {
        }

        public static String id() {
            return ID;
        }
    }

    /**
     * Sink for recording auto-events (decouples auto-instrumentation from the
     * facade to avoid a compile-time cycle).
     */
    @FunctionalInterface
    public interface AutoEventSink {
        void record(String eventType, String message, String category,
                    String traceId, java.util.Map<String, Object> payload);
    }

    /**
     * Auto-instrumentation helpers (the {@code agent_auto=true} path).
     */
    public static final class AutoInstrumentation {
        private AutoInstrumentation() {
        }

        /**
         * Install a JVM-wide uncaught-exception handler that records a
         * {@code crash} auto-event via the supplied sink. Must be called once
         * (typically from {@code AgentLog.bootstrap}).
         */
        public static void installCrashHandler(AutoEventSink sink) {
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                if (throwable == null) {
                    return;
                }
                StringBuilder stack = new StringBuilder();
                StackTraceElement[] frames = throwable.getStackTrace();
                int limit = Math.min(frames.length, 5);
                for (int i = 0; i < limit; i++) {
                    stack.append(frames[i].toString());
                    if (i < limit - 1) {
                        stack.append('\n');
                    }
                }
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("thread", thread.getName());
                payload.put("exception", throwable.getClass().getName());
                payload.put("stack", stack.toString());
                sink.record(EventType.CRASH.raw(),
                        throwable.getMessage() == null
                                ? throwable.getClass().getName()
                                : throwable.getMessage(),
                        "auto", null, payload);
            });
        }
    }
}
