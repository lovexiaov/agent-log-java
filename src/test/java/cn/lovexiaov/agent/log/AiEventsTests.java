package cn.lovexiaov.agent.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
public class AiEventsTests {

    private static MemorySink sink() {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("svc");
        AgentLog.bootstrap(cfg, sink);
        return sink;
    }

    private static Map<String, Object> line(MemorySink sink, int idx) {
        return Json.parseObject(sink.lines().get(idx));
    }

    private static Map<String, Object> payload(Map<String, Object> line) {
        return (Map<String, Object>) line.get("payload");
    }

    @Test
    public void requestUsesStandardEventTypeAndPayload() {
        MemorySink sink = sink();
        AiEvents.aiRequest("gpt-4o", "summarize this", null);
        Map<String, Object> l = line(sink, 0);
        assertEquals("ai_request_initiated", l.get("event_type"));
        assertEquals("info", l.get("level"));
        assertEquals(false, l.get("agent_auto"));
        Map<String, Object> p = payload(l);
        assertEquals("gpt-4o", p.get("model"));
        assertEquals("summarize this", p.get("prompt_text"));
        assertFalse(p.containsKey("promptText"));
    }

    @Test
    public void responseTokens() {
        MemorySink sink = sink();
        AiEvents.aiResponse("gpt-4o", 530, Map.of("request_id", "r1", "prompt_tokens", 320));
        Map<String, Object> l = line(sink, 0);
        assertEquals("ai_response_received", l.get("event_type"));
        Map<String, Object> p = payload(l);
        assertEquals(530, ((Number) p.get("total_tokens")).intValue());
        assertEquals("r1", p.get("request_id"));
    }

    @Test
    public void toolCallAndResult() {
        MemorySink sink = sink();
        AiEvents.aiToolCall("search", Map.of("q", "x"), null);
        assertEquals("ai_tool_call_initiated", line(sink, 0).get("event_type"));
        AiEvents.aiToolResult("search", true, Map.of("tool_call_latency_ms", 12));
        Map<String, Object> l = line(sink, 1);
        assertEquals("ai_tool_call_completed", l.get("event_type"));
        assertEquals(true, payload(l).get("ok"));
    }

    @Test
    public void guardrailIsWarn() {
        MemorySink sink = sink();
        AiEvents.aiGuardrail("toxicity", "blocked", null);
        Map<String, Object> l = line(sink, 0);
        assertEquals("ai_guardrail_triggered", l.get("event_type"));
        assertEquals("warn", l.get("level"));
    }

    @Test
    public void errorUsesExceptionVocab() {
        MemorySink sink = sink();
        AiEvents.aiError("RateLimitError", "429 too many requests", Map.of("request_id", "r9"));
        Map<String, Object> l = line(sink, 0);
        assertEquals("ai_error", l.get("event_type"));
        assertEquals("error", l.get("level"));
        assertEquals(false, l.get("agent_auto"));
        Map<String, Object> p = payload(l);
        assertEquals("RateLimitError", p.get("exception"));
        assertEquals("handled", p.get("mechanism"));
    }

    @Test
    public void payloadSnakeCase() {
        MemorySink sink = sink();
        AiEvents.aiResponse("gpt-4o", 10, null);
        Map<String, Object> p = payload(line(sink, 0));
        assertFalse(p.containsKey("promptText"));
        assertFalse(p.containsKey("tokenUsage"));
    }
}
