package com.scaffold.agentlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class LoggerTests {

    private static Logger makeLogger(Level minLevel) {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("svc");
        cfg.setLevel(minLevel);
        return Logger.create(cfg, sink);
    }

    @Test
    public void childDoesNotMutateParent() {
        Logger parent = makeLogger(Level.INFO);
        Logger child = parent.child(Map.of("userId", "u1", "subsystem", "sub.a"));

        // parent's own event has no user_id / subsystem
        parent.info("parent event");
        Map<String, Object> p = Json.parseObject(((MemorySink) parent.getSink()).lines().get(0));
        assertFalse(p.containsKey("user_id"), "parent must not gain child's userId");
        assertFalse(p.containsKey("subsystem"), "parent must not gain child's subsystem");

        // child's event carries the bound context (last line in the shared sink)
        child.info("child event");
        List<String> sinkLines = ((MemorySink) child.getSink()).lines();
        Map<String, Object> c = Json.parseObject(sinkLines.get(sinkLines.size() - 1));
        assertEquals("u1", c.get("user_id"));
        assertEquals("sub.a", c.get("subsystem"));

        // parent's minLevel/identity unchanged after child creation
        assertEquals(Level.INFO, parent.getLevel());
    }

    @Test
    public void setLevelFiltersManualEvents() {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("svc");
        cfg.setLevel(Level.INFO); // minLevel info
        Logger log = Logger.create(cfg, sink);

        log.debug("d1"); // dropped
        log.info("i1");  // kept
        log.warn("w1");  // kept
        assertEquals(2, sink.size(), "debug must be dropped at minLevel=info");

        log.setLevel(Level.DEBUG);
        log.debug("d2"); // now kept
        assertEquals(3, sink.size(), "debug must appear at minLevel=debug");
    }

    @Test
    public void childContextPropagation() {
        Logger parent = makeLogger(Level.INFO);
        Logger child = parent.child(Map.of("userId", "u1", "env", "prod"));

        child.warn("scoped warn", "auth", "0123456789abcdef0123456789abcdef", Map.of("orderId", "o9"));
        MemorySink sink = (MemorySink) child.getSink();
        Map<String, Object> o = Json.parseObject(sink.lines().get(0));

        assertEquals("u1", o.get("user_id"));
        assertEquals("prod", o.get("env"));
        assertEquals("auth", o.get("category"));
        assertEquals("0123456789abcdef0123456789abcdef", o.get("trace_id"));
        assertTrue(o.get("payload") instanceof Map);
        assertEquals("o9", ((Map<?, ?>) o.get("payload")).get("orderId"));
        assertEquals(false, o.get("agent_auto"));
    }

    @Test
    public void autoEventsAlwaysLogged() {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("svc");
        cfg.setLevel(Level.ERROR); // very high minLevel
        Logger log = Logger.create(cfg, sink);

        log.info("manual info"); // dropped (info < error)
        log.recordAuto("hang", "frozen", "auto", null, Map.of("ms", 5000)); // kept

        assertEquals(1, sink.size(), "only the auto event must be logged");
        Map<String, Object> o = Json.parseObject(sink.lines().get(0));
        assertEquals(true, o.get("agent_auto"));
        assertEquals("hang", o.get("event_type"));
    }

    @Test
    public void payloadRoutedToPayloadNotTopLevel() {
        Logger log = makeLogger(Level.INFO);
        log.info("msg", "app", null, new LinkedHashMap<String, Object>() {{
            put("amount", 100);
            put("orderId", "o1");
        }});
        Map<String, Object> o = Json.parseObject(((MemorySink) log.getSink()).lines().get(0));
        assertFalse(o.containsKey("amount"), "amount must not leak to top level");
        assertFalse(o.containsKey("orderId"), "orderId must not leak to top level");
        assertTrue(o.get("payload") instanceof Map);
        assertEquals(100, ((Number) ((Map<?, ?>) o.get("payload")).get("amount")).intValue());
        assertEquals("o1", ((Map<?, ?>) o.get("payload")).get("orderId"));
    }
}
