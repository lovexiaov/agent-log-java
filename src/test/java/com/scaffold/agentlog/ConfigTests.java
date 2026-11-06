package com.scaffold.agentlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

public class ConfigTests {

    @Test
    public void fromJsonDefaults() {
        LoggingConfig cfg = LoggingConfig.fromJson("{}");
        assertEquals("app", cfg.getService());
        assertEquals(Level.INFO, cfg.getLevel());
        assertTrue(cfg.isRedactEnabled());
        assertEquals(LoggingConfig.defaultExportDirectory(), cfg.getExportDirectory());
        assertTrue(cfg.getRedactPaths().isEmpty());
    }

    @Test
    public void fromJsonEmptyStringDefaults() {
        LoggingConfig cfg = LoggingConfig.fromJson("");
        assertEquals("app", cfg.getService());
        assertEquals(Level.INFO, cfg.getLevel());
        assertTrue(cfg.isRedactEnabled());
    }

    @Test
    public void fromJsonOverride() {
        String json = "{"
                + "\"service\":\"my-app\","
                + "\"env\":\"prod\","
                + "\"level\":\"debug\","
                + "\"redactEnabled\":false,"
                + "\"redactPaths\":[\"custom1\",\"custom2\"],"
                + "\"exportDirectory\":\"/tmp/export\""
                + "}";
        LoggingConfig cfg = LoggingConfig.fromJson(json);
        assertEquals("my-app", cfg.getService());
        assertEquals("prod", cfg.getEnv());
        assertEquals(Level.DEBUG, cfg.getLevel());
        assertFalse(cfg.isRedactEnabled());
        assertEquals(List.of("custom1", "custom2"), cfg.getRedactPaths());
        assertEquals("/tmp/export", cfg.getExportDirectory());
    }

    @Test
    public void levelWarningNormalizedToWarn() {
        LoggingConfig cfg = LoggingConfig.fromJson("{\"level\":\"warning\"}");
        assertEquals(Level.WARN, cfg.getLevel());
        assertEquals("warn", cfg.getLevel().wire());
    }

    @Test
    public void serviceOverrideUsedByLogger() {
        MemorySink sink = new MemorySink();
        LoggingConfig cfg = LoggingConfig.fromJson("{\"service\":\"override-svc\"}");
        Logger log = Logger.create(cfg, sink);
        log.info("hi");
        assertEquals("override-svc",
                com.scaffold.agentlog.Json.parseObject(sink.lines().get(0)).get("service"));
    }
}
