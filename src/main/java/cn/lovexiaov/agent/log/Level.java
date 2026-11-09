package cn.lovexiaov.agent.log;

/**
 * Log levels. Weights follow the language-neutral contract:
 * TRACE=0, DEBUG=1, INFO=2, WARN=3, ERROR=4, CRITICAL=5.
 *
 * <p>Wire values are exactly these lowercase strings; there are no aliases.
 */
public enum Level {
    TRACE("trace", 0),
    DEBUG("debug", 1),
    INFO("info", 2),
    WARN("warn", 3),
    ERROR("error", 4),
    CRITICAL("critical", 5);

    private final String wire;
    private final int weight;

    Level(String wire, int weight) {
        this.wire = wire;
        this.weight = weight;
    }

    /** Wire string written to JSONL (always lowercase, never "warning"). */
    public String wire() {
        return wire;
    }

    /** Numeric weight used for minLevel filtering. */
    public int weight() {
        return weight;
    }

    /**
     * Parse a level from a human/code name (e.g. "INFO", "warn").
     * Unknown names (including "warning") default to INFO.
     */
    public static Level fromName(String name) {
        if (name == null) {
            return INFO;
        }
        String n = name.trim().toLowerCase();
        switch (n) {
            case "trace":
                return TRACE;
            case "debug":
                return DEBUG;
            case "info":
                return INFO;
            case "warn":
                return WARN;
            case "error":
                return ERROR;
            case "critical":
            case "fatal":
                return CRITICAL;
            default:
                return INFO;
        }
    }

    /**
     * Parse a level from a wire string (what was read from JSONL).
     * Only the canonical 6 values are accepted; no aliases.
     */
    public static Level fromWire(String name) {
        return fromName(name);
    }
}
