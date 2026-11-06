package com.scaffold.agentlog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single structured log event. Internal fields use camelCase; serialization
 * maps reserved keys to snake_case wire format per Schema v1.
 *
 * <p>Required top-level keys: ts, level, service, event_type, session_id,
 * agent_auto, message. Optional: subsystem, category, env, user_id, trace_id,
 * payload. No other top-level keys are allowed ({@code additionalProperties:false}).
 */
public final class LogEvent {

    private String ts;
    private Level level;
    private String service;
    private String eventType;
    private String sessionId;
    private boolean agentAuto;
    private String message;

    private String subsystem;
    private String category;
    private String env;
    private String userId;
    private String traceId;
    private Map<String, Object> payload;

    public String getTs() {
        return ts;
    }

    public LogEvent setTs(String ts) {
        this.ts = ts;
        return this;
    }

    public Level getLevel() {
        return level;
    }

    public LogEvent setLevel(Level level) {
        this.level = level;
        return this;
    }

    public String getService() {
        return service;
    }

    public LogEvent setService(String service) {
        this.service = service;
        return this;
    }

    public String getEventType() {
        return eventType;
    }

    public LogEvent setEventType(String eventType) {
        this.eventType = eventType;
        return this;
    }

    public String getSessionId() {
        return sessionId;
    }

    public LogEvent setSessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public boolean isAgentAuto() {
        return agentAuto;
    }

    public LogEvent setAgentAuto(boolean agentAuto) {
        this.agentAuto = agentAuto;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public LogEvent setMessage(String message) {
        this.message = message;
        return this;
    }

    public String getSubsystem() {
        return subsystem;
    }

    public LogEvent setSubsystem(String subsystem) {
        this.subsystem = subsystem;
        return this;
    }

    public String getCategory() {
        return category;
    }

    public LogEvent setCategory(String category) {
        this.category = category;
        return this;
    }

    public String getEnv() {
        return env;
    }

    public LogEvent setEnv(String env) {
        this.env = env;
        return this;
    }

    public String getUserId() {
        return userId;
    }

    public LogEvent setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public String getTraceId() {
        return traceId;
    }

    public LogEvent setTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public LogEvent setPayload(Map<String, Object> payload) {
        this.payload = payload;
        return this;
    }

    /**
     * Serialize to a single JSONL line (Schema v1 wire format).
     * Optional top-level keys are emitted only when present/non-empty.
     */
    public String toJsonLine() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("ts", ts);
        m.put("level", level.wire());
        m.put("service", service);
        m.put("event_type", eventType);
        m.put("session_id", sessionId);
        m.put("agent_auto", agentAuto);
        m.put("message", message);

        if (subsystem != null && !subsystem.isEmpty()) {
            m.put("subsystem", subsystem);
        }
        if (category != null && !category.isEmpty()) {
            m.put("category", category);
        }
        if (env != null && !env.isEmpty()) {
            m.put("env", env);
        }
        if (userId != null && !userId.isEmpty()) {
            m.put("user_id", userId);
        }
        if (traceId != null && !traceId.isEmpty()) {
            m.put("trace_id", traceId);
        }
        if (payload != null && !payload.isEmpty()) {
            m.put("payload", payload);
        }
        return Json.stringify(m);
    }
}
