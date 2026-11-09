package cn.lovexiaov.agent.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * OTLP mapping conformance: severity table, deterministic ids, LogRecord shape,
 * HTTP/DB span semantics, resource grouping, and the batch/sink entry points.
 *
 * <p>Every assertion here mirrors a rule in
 * {@code docs/logging-framework/OTLP-MAPPING.md}; the Python and Go suites assert
 * the same invariants so the three implementations stay byte-comparable.
 */
public class OtlpMapperTests {

    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String SPAN = "a1b2c3d4e5f60718";
    private static final String TS = "2026-11-07T10:00:00.123456789Z";

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> event(String type, String level, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", TS);
        m.put("level", level);
        m.put("service", "demo-app");
        m.put("event_type", type);
        m.put("session_id", "sess-1");
        m.put("agent_auto", false);
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> eventWithPayload(String type, Map<String, Object> payload) {
        Map<String, Object> m = event(type, "info", "op");
        m.put("payload", payload);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertTrue(value instanceof Map, "expected a map, got " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertTrue(value instanceof List, "expected a list, got " + value);
        return (List<Object>) value;
    }

    /** Attribute lookup: returns the raw AnyValue map for {@code key}. */
    private static Object attr(List<Object> attributes, String key) {
        for (Object raw : attributes) {
            Map<String, Object> kv = map(raw);
            if (key.equals(kv.get("key"))) {
                return kv.get("value");
            }
        }
        return null;
    }

    private static List<String> attrKeys(List<Object> attributes) {
        List<String> keys = new ArrayList<>();
        for (Object raw : attributes) {
            keys.add(String.valueOf(map(raw).get("key")));
        }
        return keys;
    }

    /** All LogRecords of the first resource group in a logs body. */
    private static List<Object> logRecords(Map<String, Object> logsBody) {
        Map<String, Object> resourceLogs = map(list(logsBody.get("resourceLogs")).get(0));
        Map<String, Object> scopeLogs = map(list(resourceLogs.get("scopeLogs")).get(0));
        return list(scopeLogs.get("logRecords"));
    }

    /** All spans of the first resource group in a traces body. */
    private static List<Object> spans(Map<String, Object> tracesBody) {
        Map<String, Object> resourceSpans = map(list(tracesBody.get("resourceSpans")).get(0));
        Map<String, Object> scopeSpans = map(list(resourceSpans.get("scopeSpans")).get(0));
        return list(scopeSpans.get("spans"));
    }

    // ------------------------------------------------------------------ severity

    @Test
    public void severityTableMatchesSpec() {
        String[][] expected = {
            {"trace", "TRACE", "1"},
            {"debug", "DEBUG", "5"},
            {"info", "INFO", "9"},
            {"warn", "WARN", "13"},
            {"error", "ERROR", "17"},
            {"critical", "FATAL", "21"},
        };
        for (String[] row : expected) {
            OtlpMapper.Severity severity = OtlpMapper.levelToSeverity(row[0]);
            assertEquals(row[1], severity.text, "severityText for " + row[0]);
            assertEquals(Integer.parseInt(row[2]), severity.number, "severityNumber for " + row[0]);
        }
    }

    @Test
    public void warningIsNotCanonicalAndUnknownLevelDegradeToInfo() {
        assertEquals("INFO", OtlpMapper.levelToSeverity("warning").text);
        assertEquals(9, OtlpMapper.levelToSeverity("warning").number);
        assertEquals("INFO", OtlpMapper.levelToSeverity("nonsense").text);
        assertEquals(9, OtlpMapper.levelToSeverity(null).number);
    }

    // --------------------------------------------------------------------- types

    @Test
    public void anyValueFollowsProtoJsonMapping() {
        assertEquals(Boolean.TRUE, map(OtlpMapper.toAnyValue(true)).get("boolValue"));
        // bool is checked before numbers: `true` must not become intValue.
        assertFalse(map(OtlpMapper.toAnyValue(true)).containsKey("intValue"));
        // int64 is a STRING per the protobuf JSON mapping.
        assertEquals("42", map(OtlpMapper.toAnyValue(42)).get("intValue"));
        assertEquals("42", map(OtlpMapper.toAnyValue(42L)).get("intValue"));
        assertEquals(0.5d, map(OtlpMapper.toAnyValue(0.5d)).get("doubleValue"));
        assertEquals("hi", map(OtlpMapper.toAnyValue("hi")).get("stringValue"));
        assertEquals("x", map(OtlpMapper.toAnyValue(new Object() {
            @Override
            public String toString() {
                return "x";
            }
        })).get("stringValue"));
    }

    @Test
    public void nestedContainersStayStructuredAndNullsAreSkipped() {
        Map<String, Object> value = map(OtlpMapper.toAnyValue(List.of(1, "two")));
        List<Object> items = list(map(value.get("arrayValue")).get("values"));
        assertEquals(2, items.size());
        assertEquals("1", map(items.get(0)).get("intValue"));
        assertEquals("two", map(items.get(1)).get("stringValue"));

        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("a", 1);
        inner.put("skip", null);
        List<Object> kvs = list(map(map(OtlpMapper.toAnyValue(inner)).get("kvlistValue")).get("values"));
        assertEquals(1, kvs.size(), "null values are dropped, not exported");
    }

    // --------------------------------------------------------------------- time

    @Test
    public void timestampParsesOffsetsAndTruncatesSubNanosecond() {
        long utc = OtlpMapper.parseTimestampNanos("2026-11-07T10:00:00Z");
        assertEquals(OtlpMapper.parseTimestampNanos("2026-11-07T18:00:00+08:00"), utc);
        assertEquals(OtlpMapper.parseTimestampNanos("2026-11-07T18:00:00+0800"), utc);
        assertEquals(OtlpMapper.parseTimestampNanos("2026-11-07 10:00:00"), utc);
        // Sub-nanosecond digits are truncated, not rounded.
        assertEquals(utc + 123456789L, OtlpMapper.parseTimestampNanos(TS));
        assertEquals(utc + 123456789L, OtlpMapper.parseTimestampNanos("2026-11-07T10:00:00.1234567899Z"));
        assertThrows(IllegalArgumentException.class, () -> OtlpMapper.parseTimestampNanos("not-a-time"));
    }

    // ---------------------------------------------------------------------- ids

    @Test
    public void traceIdPassthroughAndDropOfNonHex() {
        Map<String, Object> m = event("log", "info", "hi");
        m.put("trace_id", TRACE.toUpperCase(java.util.Locale.ROOT));
        assertEquals(TRACE, OtlpMapper.traceIdHex(m));

        // Schema v1.2: non-hex values are rejected — no hashing, no id invention.
        for (Object bad : new Object[] {"opaque-trace", "0123456789abcdef0123456789abcde", 42}) {
            m.put("trace_id", bad);
            assertNull(OtlpMapper.traceIdHex(m), String.valueOf(bad));
        }

        m.remove("trace_id");
        assertNull(OtlpMapper.traceIdHex(m));
    }

    @Test
    public void spanIdPrefersExplicitV11Value() {
        Map<String, Object> m = event("log", "info", "hi");
        m.put("span_id", SPAN.toUpperCase(java.util.Locale.ROOT));
        assertEquals(SPAN, OtlpMapper.spanIdHex(m));
    }

    @Test
    public void spanIdDerivationIsDeterministicAndIgnoresInvalidInput() {
        Map<String, Object> m = event("log", "info", "hi");
        String derived = OtlpMapper.spanIdHex(m);
        assertEquals(16, derived.length());
        assertTrue(derived.matches("^[0-9a-f]{16}$"));
        assertEquals(derived, OtlpMapper.spanIdHex(m));

        // A malformed span_id must not be passed through — it falls back to derivation.
        m.put("span_id", "not-a-span-id");
        assertEquals(derived, OtlpMapper.spanIdHex(m));
    }

    @Test
    public void spanWithoutTraceFallsBackToSessionTrace() {
        Map<String, Object> m = event("network_request", "info", "HTTP GET");
        assertNull(OtlpMapper.traceIdHex(m), "no trace_id on the event");
        String spanTrace = OtlpMapper.spanTraceId(m);
        assertEquals(32, spanTrace.length());
        // Same session -> same synthetic trace, so spans do not scatter.
        assertEquals(spanTrace, OtlpMapper.spanTraceId(event("db_query", "info", "SQL")));
    }

    // ---------------------------------------------------------------- logrecord

    @Test
    public void logRecordCarriesSpecFieldsAndAttributes() {
        Map<String, Object> m = event("log", "error", "boom");
        m.put("subsystem", "auth");
        m.put("category", "app");
        m.put("user_id", "u-1");
        m.put("trace_id", TRACE);
        m.put("span_id", SPAN);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt", 2);
        payload.put("ok", false);
        m.put("payload", payload);

        Map<String, Object> record = OtlpMapper.eventToLogRecord(m);
        assertEquals(String.valueOf(OtlpMapper.parseTimestampNanos(TS)), record.get("timeUnixNano"));
        assertEquals(record.get("timeUnixNano"), record.get("observedTimeUnixNano"));
        assertEquals(17, record.get("severityNumber"));
        assertEquals("ERROR", record.get("severityText"));
        assertEquals("boom", map(record.get("body")).get("stringValue"));
        assertEquals(TRACE, record.get("traceId"));
        assertEquals(SPAN, record.get("spanId"));

        List<Object> attrs = list(record.get("attributes"));
        assertEquals("log", map(attr(attrs, "agent.event.type")).get("stringValue"));
        assertEquals(false, map(attr(attrs, "agent.auto")).get("boolValue"));
        assertEquals("auth", map(attr(attrs, "agent.subsystem")).get("stringValue"));
        assertEquals("app", map(attr(attrs, "agent.category")).get("stringValue"));
        assertEquals("u-1", map(attr(attrs, "enduser.id")).get("stringValue"));
        assertEquals("2", map(attr(attrs, "payload.attempt")).get("intValue"));
        assertEquals(false, map(attr(attrs, "payload.ok")).get("boolValue"));
    }

    @Test
    public void logRecordWithoutTraceOmitsIds() {
        Map<String, Object> record = OtlpMapper.eventToLogRecord(event("log", "info", "hi"));
        assertFalse(record.containsKey("traceId"));
        assertFalse(record.containsKey("spanId"));
    }

    @Test
    public void operationLogRecordSharesSpanIdWithItsSpan() {
        Map<String, Object> m = eventWithPayload("db_query", Map.of("statement", "SELECT 1"));
        m.put("trace_id", TRACE);
        m.put("span_id", SPAN);

        Map<String, Object> record = OtlpMapper.eventToLogRecord(m);
        Map<String, Object> span = OtlpMapper.eventToSpan(m);
        assertEquals(SPAN, record.get("spanId"));
        assertEquals(SPAN, span.get("spanId"));
        assertEquals(TRACE, span.get("traceId"));
    }

    @Test
    public void plainLogRecordNeverReferencesAMissingSpan() {
        // A non-operation event without an explicit span_id gets no spanId at all,
        // because no span is emitted for it.
        Map<String, Object> m = event("log", "info", "hi");
        m.put("trace_id", TRACE);
        Map<String, Object> record = OtlpMapper.eventToLogRecord(m);
        assertEquals(TRACE, record.get("traceId"));
        assertFalse(record.containsKey("spanId"));
    }

    // -------------------------------------------------------------------- spans

    @Test
    public void httpSpanUsesSemanticConventions() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", "GET");
        payload.put("url", "https://api.example.com:8443/orders?page=1");
        payload.put("status_code", 200);
        payload.put("duration_ms", 12);
        Map<String, Object> m = eventWithPayload("network_request", payload);
        m.put("trace_id", TRACE);

        Map<String, Object> span = OtlpMapper.eventToSpan(m);
        assertEquals("GET https://api.example.com:8443/orders?page=1", span.get("name"));
        assertEquals(3, span.get("kind"));
        long start = OtlpMapper.parseTimestampNanos(TS);
        assertEquals(String.valueOf(start), span.get("startTimeUnixNano"));
        assertEquals(String.valueOf(start + 12000000L), span.get("endTimeUnixNano"));
        assertFalse(span.containsKey("status"), "success keeps status UNSET (no OK noise)");

        List<Object> attrs = list(span.get("attributes"));
        assertEquals("GET", map(attr(attrs, "http.request.method")).get("stringValue"));
        assertEquals("200", map(attr(attrs, "http.response.status_code")).get("intValue"));
        assertEquals("api.example.com", map(attr(attrs, "server.address")).get("stringValue"));
        assertNull(attr(attrs, "error.type"));
    }

    @Test
    public void httpFailureSetsErrorStatus() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", "POST");
        payload.put("url", "http://localhost:8080/pay");
        payload.put("status_code", 503);
        Map<String, Object> span = OtlpMapper.eventToSpan(eventWithPayload("network_request", payload));

        assertEquals("POST http://localhost:8080/pay", span.get("name"));
        Map<String, Object> status = map(span.get("status"));
        assertEquals(2, status.get("code"));
        assertEquals("POST http://localhost:8080/pay", status.get("message"));
        List<Object> attrs = list(span.get("attributes"));
        assertEquals("503", map(attr(attrs, "error.type")).get("stringValue"));
    }

    @Test
    public void dbSpanPrefersCanonicalStatementKey() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "select");
        payload.put("statement", "SELECT 1");
        payload.put("sql", "SELECT legacy");
        payload.put("db_system", "postgresql");
        Map<String, Object> span = OtlpMapper.eventToSpan(eventWithPayload("db_query", payload));

        assertEquals("select SELECT 1", span.get("name"));
        List<Object> attrs = list(span.get("attributes"));
        assertEquals("SELECT 1", map(attr(attrs, "db.statement")).get("stringValue"));
        assertEquals("select", map(attr(attrs, "db.operation")).get("stringValue"));
        assertEquals("postgresql", map(attr(attrs, "db.system")).get("stringValue"));
    }

    @Test
    public void dbSpanIgnoresLegacySqlAndQueryKeys() {
        Map<String, Object> legacySql = new LinkedHashMap<>();
        legacySql.put("operation", "exec");
        legacySql.put("sql", "INSERT 1");
        assertEquals("exec ", OtlpMapper.eventToSpan(eventWithPayload("db_query", legacySql)).get("name"));

        Map<String, Object> legacyQuery = new LinkedHashMap<>();
        legacyQuery.put("operation", "exec");
        legacyQuery.put("query", "INSERT 2");
        assertEquals("exec ", OtlpMapper.eventToSpan(eventWithPayload("db_query", legacyQuery)).get("name"));
    }

    @Test
    public void dbFailureRecordsErrorType() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "exec");
        payload.put("statement", "INSERT 1");
        payload.put("error", "duplicate key");
        Map<String, Object> span = OtlpMapper.eventToSpan(eventWithPayload("db_query", payload));

        assertEquals(2, map(span.get("status")).get("code"));
        assertEquals("duplicate key", map(attr(list(span.get("attributes")), "error.type")).get("stringValue"));
    }

    @Test
    public void nonOperationEventsProduceNoSpan() {
        for (String type : new String[] {"log", "ui_interaction", "screen_view", "crash", "hang"}) {
            assertNull(OtlpMapper.eventToSpan(event(type, "info", "x")), type + " must stay in the logs signal");
        }
    }

    @Test
    public void crashCarriesExceptionAttributes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("thread", "main");
        payload.put("exception", "java.lang.IllegalStateException");
        payload.put("message", "boom in billing");
        payload.put("stack", "at Example.main(Example.java:1)");
        Map<String, Object> m = eventWithPayload("crash", payload);
        m.put("agent_auto", true);

        Map<String, Object> record = OtlpMapper.eventToLogRecord(m);
        List<Object> attrs = list(record.get("attributes"));
        assertEquals("java.lang.IllegalStateException", map(attr(attrs, "exception.type")).get("stringValue"));
        assertEquals("boom in billing", map(attr(attrs, "exception.message")).get("stringValue"));
        assertEquals("at Example.main(Example.java:1)",
                map(attr(attrs, "exception.stacktrace")).get("stringValue"));
        // payload.* is still flattened, and auto events are flagged.
        assertEquals(true, map(attr(attrs, "agent.auto")).get("boolValue"));
        assertEquals("main", map(attr(attrs, "payload.thread")).get("stringValue"));
    }

    // -------------------------------------------------------------------- batch

    @Test
    public void resourceGroupsByServiceEnvAndSession() {
        Map<String, Object> a = event("log", "info", "a");
        a.put("env", "prod");
        Map<String, Object> b = event("log", "info", "b");
        b.put("env", "prod");
        Map<String, Object> c = event("log", "info", "c");
        c.put("env", "dev");
        c.put("session_id", "sess-2");

        Map<String, Object> body = OtlpMapper.toOtlpLogs(List.of(a, b, c), null);
        List<Object> resourceLogs = list(body.get("resourceLogs"));
        assertEquals(2, resourceLogs.size(), "two distinct (service, env, session) groups");

        Map<String, Object> first = map(resourceLogs.get(0));
        List<Object> attrs = list(map(first.get("resource")).get("attributes"));
        assertEquals("demo-app", map(attr(attrs, "service.name")).get("stringValue"));
        assertEquals("prod", map(attr(attrs, "deployment.environment")).get("stringValue"));
        assertEquals("sess-1", map(attr(attrs, "session.id")).get("stringValue"));
        assertEquals("agent-log", map(attr(attrs, "telemetry.sdk.name")).get("stringValue"));
        assertEquals("java", map(attr(attrs, "telemetry.sdk.language")).get("stringValue"));
        assertEquals(OtlpMapper.SDK_VERSION, map(attr(attrs, "telemetry.sdk.version")).get("stringValue"));

        Map<String, Object> scopeLogs = map(list(first.get("scopeLogs")).get(0));
        assertEquals("agentlog.otlp", map(scopeLogs.get("scope")).get("name"));
        assertEquals(2, list(scopeLogs.get("logRecords")).size(), "input order preserved inside a group");
    }

    @Test
    public void extraResourceCannotOverrideBuiltins() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("service.name", "hijacked");
        extra.put("service.version", "2.1.0");

        Map<String, Object> body = OtlpMapper.toOtlpLogs(List.of(event("log", "info", "x")), extra);
        List<Object> attrs = list(map(map(list(body.get("resourceLogs")).get(0)).get("resource")).get("attributes"));

        assertEquals("demo-app", map(attr(attrs, "service.name")).get("stringValue"));
        assertEquals("2.1.0", map(attr(attrs, "service.version")).get("stringValue"));
        assertEquals(1, attrKeys(attrs).stream().filter("service.name"::equals).count());
    }

    @Test
    public void tracesBodySkipsGroupsWithoutOperationEvents() {
        Map<String, Object> plain = event("log", "info", "x");
        Map<String, Object> op = eventWithPayload("network_request", Map.of("method", "GET", "url", "http://a/b"));

        Map<String, Object> body = OtlpMapper.toOtlpSpans(List.of(plain, op), null);
        List<Object> resourceSpans = list(body.get("resourceSpans"));
        // Both events share one resource group, and it does contain a span.
        assertEquals(1, resourceSpans.size());
        List<Object> spans = list(map(list(map(resourceSpans.get(0)).get("scopeSpans")).get(0)).get("spans"));
        assertEquals(1, spans.size(), "the plain log produces no span");

        Map<String, Object> onlyLogs = OtlpMapper.toOtlpSpans(List.of(plain), null);
        assertEquals(0, list(onlyLogs.get("resourceSpans")).size());
    }

    @Test
    public void toOtlpReturnsBothSignalsAndSerializes() {
        Map<String, Object> op = eventWithPayload("db_query", Map.of("statement", "SELECT 1"));
        Map<String, Object> both = OtlpMapper.toOtlp(List.of(op), null);
        assertNotNull(both.get("logs"));
        assertNotNull(both.get("traces"));

        String json = OtlpMapper.toOtlpJson(both.get("logs"));
        assertFalse(json.contains(", "), "compact JSON has no separator whitespace");
        assertFalse(json.contains(": "), "compact JSON has no key/value whitespace");
        Map<String, Object> reparsed = Json.parseObject(json);
        assertEquals(1, list(reparsed.get("resourceLogs")).size());
        // Round-trip is stable: the serializer is order-preserving and lossless.
        assertEquals(json, OtlpMapper.toOtlpJson(reparsed));
    }

    @Test
    public void acceptsMapJsonLineAndLogEventInputs() {
        LogEvent event = new LogEvent();
        event.setTs(TS);
        event.setLevel(Level.INFO);
        event.setService("demo-app");
        event.setEventType("log");
        event.setSessionId("sess-1");
        event.setMessage("from LogEvent");

        assertEquals("from LogEvent",
                map(OtlpMapper.eventToLogRecord(OtlpMapper.eventOf(event)).get("body")).get("stringValue"));

        String line = event.toJsonLine();
        assertEquals("from LogEvent",
                map(OtlpMapper.eventToLogRecord(OtlpMapper.eventOf(line)).get("body")).get("stringValue"));

        assertThrows(IllegalArgumentException.class, () -> OtlpMapper.eventOf(42));
    }

    // --------------------------------------------------------------------- sink

    @Test
    public void sinkBuffersAndDrainsToOtlp() {
        OtlpMapper.OtlpSink sink = new OtlpMapper.OtlpSink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("demo-app");
        cfg.setLevel(Level.TRACE);
        Logger logger = Logger.create(cfg, sink);

        logger.info("first");
        logger.recordAuto("network_request", "GET /x", "auto", null, Map.of("method", "GET", "url", "http://h/x"));
        assertEquals(2, sink.size());

        Map<String, Object> batch = sink.drain();
        assertEquals(0, sink.size(), "drain clears the buffer");

        Map<String, Object> logs = map(batch.get("logs"));
        assertEquals(2, logRecords(logs).size(), "both buffered lines became LogRecords");

        Map<String, Object> traces = map(batch.get("traces"));
        List<Object> resourceSpans = list(traces.get("resourceSpans"));
        assertEquals(1, resourceSpans.size());
        assertEquals(1, spans(traces).size(), "only the network_request becomes a span");
    }

    // ---------------------------------------------------------------- constants

    @Test
    public void sdkVersionMatchesProjectVersion() {
        assertEquals("1.0.0", OtlpMapper.SDK_VERSION);
        assertEquals(3, OtlpMapper.SPAN_KIND_CLIENT);
        assertEquals(2, OtlpMapper.STATUS_CODE_ERROR);
    }
}
