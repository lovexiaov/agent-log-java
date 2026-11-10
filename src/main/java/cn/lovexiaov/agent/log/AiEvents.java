package cn.lovexiaov.agent.log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI interaction event helpers (Schema v1.4). Each method emits one of the six
 * standardized AI {@code event_type} values with the canonical snake_case payload.
 * These are explicit, business-side calls ({@code agent_auto=false}) — the
 * framework only prepares the data; the caller decides where/when to record an
 * LLM call.
 */
public final class AiEvents {

    private AiEvents() {
    }

    private static Map<String, Object> payload(Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (extra != null) {
            p.putAll(extra);
        }
        return p;
    }

    private static void record(Level level, CoreTypes.EventType type, String message,
                               Map<String, Object> body) {
        AgentLog.logger().record(level, type.raw(), false, message, "ai", null, body);
    }

    /** Emit {@code ai_request_initiated} for the start of an LLM call. */
    public static void aiRequest(String model, String promptText, Map<String, Object> extra) {
        Map<String, Object> p = payload(extra);
        p.put("model", model);
        p.put("prompt_text", promptText);
        record(Level.INFO, CoreTypes.EventType.AI_REQUEST_INITIATED, "ai request initiated", p);
    }

    /** Emit {@code ai_response_received} with token usage for a completed call. */
    public static void aiResponse(String model, int totalTokens, Map<String, Object> extra) {
        Map<String, Object> p = payload(extra);
        p.put("model", model);
        p.put("total_tokens", totalTokens);
        record(Level.INFO, CoreTypes.EventType.AI_RESPONSE_RECEIVED, "ai response received", p);
    }

    /** Emit {@code ai_tool_call_initiated} describing a tool the model wants to use. */
    public static void aiToolCall(String toolName, Object toolArguments, Map<String, Object> extra) {
        Map<String, Object> p = payload(extra);
        p.put("tool_name", toolName);
        p.put("tool_arguments", toolArguments);
        record(Level.INFO, CoreTypes.EventType.AI_TOOL_CALL_INITIATED, "ai tool call initiated", p);
    }

    /** Emit {@code ai_tool_call_completed} for a finished tool invocation. */
    public static void aiToolResult(String toolName, boolean ok, Map<String, Object> extra) {
        Map<String, Object> p = payload(extra);
        p.put("tool_name", toolName);
        p.put("ok", ok);
        record(Level.INFO, CoreTypes.EventType.AI_TOOL_CALL_COMPLETED, "ai tool call completed", p);
    }

    /** Emit {@code ai_guardrail_triggered} (warn) when a safeguard fires. */
    public static void aiGuardrail(String guardrailType, String reason, Map<String, Object> extra) {
        Map<String, Object> p = payload(extra);
        p.put("guardrail_type", guardrailType);
        p.put("reason", reason);
        record(Level.WARN, CoreTypes.EventType.AI_GUARDRAIL_TRIGGERED, "ai guardrail triggered", p);
    }

    /** Emit {@code ai_error} reusing the v1.3 exception vocabulary. */
    public static void aiError(String errorType, String message, Map<String, Object> extra) {
        Map<String, Object> p = payload(extra);
        p.put("exception", errorType);
        p.put("exception_message", message);
        p.put("mechanism", "handled");
        record(Level.ERROR, CoreTypes.EventType.AI_ERROR, "ai interaction error", p);
    }
}
