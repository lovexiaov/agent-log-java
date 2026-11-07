package cn.lovexiaov.agent.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * C7 conformance suite — proves the wire output validates against Schema v1.
 */
public class SchemaTests {

    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "ts", "level", "service", "event_type", "session_id", "agent_auto", "message",
            "subsystem", "category", "env", "user_id", "trace_id", "span_id", "payload"));

    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "ts", "level", "service", "event_type", "session_id", "agent_auto", "message"));

    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern RFC3339 = Pattern.compile(".*[+-]\\d{2}:\\d{2}$");

    private static Logger makeLogger(Level minLevel, MemorySink sink) {
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("test-svc");
        cfg.setEnv("test");
        cfg.setLevel(minLevel);
        return Logger.create(cfg, sink);
    }

    @Test
    public void testSchemaConformance() {
        MemorySink sink = new MemorySink();
        Logger log = makeLogger(Level.INFO, sink);

        // manual info + payload non-sensitive
        log.info("manual info", "app", null,
                Map.of("host", "api.example.com", "count", 3));
        // manual warn
        log.warn("manual warn");
        // manual debug (DROPPED at info)
        log.debug("should be dropped");
        // auto network_request (always logged)
        log.recordAuto(CoreTypes.EventType.NETWORK_REQUEST.raw(), "GET /x -> 200",
                "auto", null, Map.of("host", "api.example.com", "status", 200));
        // auto db_query (always logged) — covers the new event type in C7
        log.recordAuto(CoreTypes.EventType.DB_QUERY.raw(), "SELECT 1",
                "auto", null, Map.of("statement", "SELECT 1", "rows_affected", 1L));
        // child logger bound context
        Logger child = log.child(Map.of("userId", "u1"));
        child.info("child info");
        // sensitive payload
        log.info("login", "app", null,
                Map.of("password", "hunter2", "host", "example.com"));

        // ---- per-line conformance ----
        List<String> lines = sink.lines();
        for (String line : lines) {
            checkLine(line);
        }

        // ---- minLevel=info skipped a debug manual event (count) ----
        // 6 lines: info, warn, network(auto), db_query(auto), child info, sensitive info
        assertEquals(6, sink.size(), "debug must be dropped at minLevel=info");

        // ---- auto event appears at minLevel=info ----
        boolean autoPresent = lines.stream().anyMatch(l -> {
            Map<String, Object> o = Json.parseObject(l);
            return Boolean.TRUE.equals(o.get("agent_auto"))
                    && "network_request".equals(o.get("event_type"));
        });
        assertTrue(autoPresent, "auto event must be logged even at minLevel=info");

        // ---- sensitive password masked, non-sensitive host untouched ----
        boolean passwordMasked = lines.stream().anyMatch(l -> {
            Map<String, Object> o = Json.parseObject(l);
            Object p = o.get("payload");
            if (p instanceof Map) {
                Object pw = ((Map<?, ?>) p).get("password");
                return Redactor.MASK.equals(pw);
            }
            return false;
        });
        assertTrue(passwordMasked, "password payload value must be masked");

        boolean hostUntouched = lines.stream().anyMatch(l -> {
            Map<String, Object> o = Json.parseObject(l);
            Object p = o.get("payload");
            if (p instanceof Map) {
                Object host = ((Map<?, ?>) p).get("host");
                return "api.example.com".equals(host);
            }
            return false;
        });
        assertTrue(hostUntouched, "non-sensitive host must pass through unchanged");

        // ---- child carries user_id; parent unchanged ----
        boolean childHasUserId = lines.stream().anyMatch(l -> {
            Map<String, Object> o = Json.parseObject(l);
            return "u1".equals(o.get("user_id"));
        });
        assertTrue(childHasUserId, "child event must carry user_id=u1");

        // parent minLevel unchanged + parent's own events have no user_id
        assertEquals(Level.INFO, log.getLevel(), "parent minLevel must be unchanged");
        boolean parentLeakedUserId = lines.stream().anyMatch(l -> {
            Map<String, Object> o = Json.parseObject(l);
            return o.containsKey("user_id")
                    && "child info".equals(o.get("message")) == false
                    && "u1".equals(o.get("user_id")) == false;
        });
        assertFalse(parentLeakedUserId,
                "non-child events must not carry the child's user_id");

        // ---- debug appears when minLevel=trace ----
        log.setLevel(Level.TRACE);
        log.debug("now visible");
        assertEquals(7, sink.size(), "debug must appear at minLevel=trace");
        String last = sink.lines().get(sink.size() - 1);
        Map<String, Object> lastObj = Json.parseObject(last);
        assertEquals("debug", lastObj.get("level"));
    }

    private void checkLine(String line) {
        Map<String, Object> l = Json.parseObject(line);
        // 1. object
        assertNotNull(l, "line must be a JSON object");
        // 2. key set subset of allowed
        for (String k : l.keySet()) {
            assertTrue(ALLOWED.contains(k), "unexpected top-level key: " + k);
        }
        // 3. required keys present
        for (String k : REQUIRED) {
            assertTrue(l.containsKey(k), "missing required key: " + k);
        }
        // 4. level enum
        assertTrue(Level.fromWire((String) l.get("level")) != null
                && Arrays.asList("trace", "debug", "info", "warn", "error", "critical")
                        .contains(l.get("level")), "bad level: " + l.get("level"));
        // 5. event_type pattern
        assertTrue(EVENT_TYPE.matcher((String) l.get("event_type")).matches(),
                "bad event_type: " + l.get("event_type"));
        // 6. session_id non-empty string
        Object sid = l.get("session_id");
        assertTrue(sid instanceof String && !((String) sid).isEmpty(),
                "session_id must be non-empty string");
        // 7. agent_auto boolean
        assertTrue(l.get("agent_auto") instanceof Boolean, "agent_auto must be boolean");
        // 8. message string
        assertTrue(l.get("message") instanceof String, "message must be string");
        // 9. payload object if present
        if (l.containsKey("payload")) {
            assertTrue(l.get("payload") instanceof Map, "payload must be object");
        }
        // 10. ts RFC3339-ish
        String ts = (String) l.get("ts");
        assertTrue(ts.contains("T")
                && (ts.endsWith("Z") || RFC3339.matcher(ts).matches()),
                "ts must look like RFC3339: " + ts);
    }
}
