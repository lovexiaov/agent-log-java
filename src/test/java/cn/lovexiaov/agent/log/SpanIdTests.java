package cn.lovexiaov.agent.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Schema v1.1 span_id: bound scope propagation, normalization, and the rule that
 * an invalid id is dropped rather than written (C7 must never see a bad line).
 */
public class SpanIdTests {

    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String SPAN = "a1b2c3d4e5f60718";

    private static MemorySink sinkOf(Logger logger) {
        return (MemorySink) logger.getSink();
    }

    private static Logger makeLogger() {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = new LoggingConfig();
        cfg.setService("svc");
        cfg.setLevel(Level.INFO);
        return Logger.create(cfg, sink);
    }

    @Test
    public void normalizeAcceptsOnly16HexChars() {
        assertEquals(SPAN, CoreTypes.SpanId.normalize("A1B2C3D4E5F60718"));
        assertEquals(SPAN, CoreTypes.SpanId.normalize("  a1b2c3d4e5f60718 "));
        assertNull(CoreTypes.SpanId.normalize(null));
        assertNull(CoreTypes.SpanId.normalize("a1b2c3d4e5f6071"));
        assertNull(CoreTypes.SpanId.normalize("a1b2c3d4e5f607189"));
        assertNull(CoreTypes.SpanId.normalize("g1b2c3d4e5f60718"));
        assertNull(CoreTypes.SpanId.normalize(""));
    }

    @Test
    public void childBindsSpanIdAndNormalizesCase() {
        Logger logger = makeLogger();
        logger.child(Map.of("traceId", TRACE, "spanId", "A1B2C3D4E5F60718"))
                .info("inside span");

        Map<String, Object> event = Json.parseObject(sinkOf(logger).lines().get(0));
        assertEquals(SPAN, event.get("span_id"));
        assertEquals(TRACE, event.get("trace_id"));
    }

    @Test
    public void childAcceptsSnakeCaseSpanId() {
        Logger logger = makeLogger();
        logger.child(Map.of("trace_id", TRACE, "span_id", SPAN)).info("snake case");

        Map<String, Object> event = Json.parseObject(sinkOf(logger).lines().get(0));
        assertEquals(SPAN, event.get("span_id"));
        assertEquals(TRACE, event.get("trace_id"));
    }

    @Test
    public void withSpanConvenienceBindsBothIds() {
        Logger logger = makeLogger();
        logger.withSpan(TRACE, SPAN).info("via withSpan");

        Map<String, Object> event = Json.parseObject(sinkOf(logger).lines().get(0));
        assertEquals(SPAN, event.get("span_id"));
        assertEquals(TRACE, event.get("trace_id"));
    }

    @Test
    public void parentDoesNotInheritChildSpan() {
        Logger logger = makeLogger();
        logger.withSpan(TRACE, SPAN).info("inside");
        logger.info("outside");

        Map<String, Object> inside = Json.parseObject(sinkOf(logger).lines().get(0));
        Map<String, Object> outside = Json.parseObject(sinkOf(logger).lines().get(1));
        assertEquals(SPAN, inside.get("span_id"));
        assertFalse(outside.containsKey("span_id"));
        assertFalse(outside.containsKey("trace_id"));
    }

    @Test
    public void invalidSpanIdIsDropped() {
        for (String bad : new String[] {"not-a-span", "a1b2c3d4e5f6071", "zzzzzzzzzzzzzzzz"}) {
            Logger logger = makeLogger();
            logger.withSpan(TRACE, bad).info("bad span");

            Map<String, Object> event = Json.parseObject(sinkOf(logger).lines().get(0));
            assertFalse(event.containsKey("span_id"), "span_id " + bad + " should be dropped");
            // The trace id is still valid and stays.
            assertEquals(TRACE, event.get("trace_id"));
        }
    }

    @Test
    public void spanIdNeverLeaksIntoPayload() {
        Logger logger = makeLogger();
        logger.child(Map.of("span_id", SPAN)).info("x");

        Map<String, Object> event = Json.parseObject(sinkOf(logger).lines().get(0));
        if (event.get("payload") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            assertFalse(payload.containsKey("span_id"), "span_id must not be duplicated into payload");
        }
    }
}
