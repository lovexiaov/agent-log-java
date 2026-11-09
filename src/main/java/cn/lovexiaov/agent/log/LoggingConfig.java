package cn.lovexiaov.agent.log;

import java.util.List;
import java.util.Map;

/**
 * Language-neutral logging configuration.
 *
 * <p>Reads a subset of {@code config/logging.config.json} via the hand-rolled
 * JSON parser. Defaults (per contract):
 * <ul>
 *   <li>service = "app"</li>
 *   <li>level = "info"</li>
 *   <li>redactEnabled = true</li>
 *   <li>exportDirectory = system temp dir + "/agent-log"</li>
 * </ul>
 */
public final class LoggingConfig {

    public static final String DEFAULT_SERVICE = "app";
    public static final Level DEFAULT_LEVEL = Level.INFO;
    public static final boolean DEFAULT_REDACT_ENABLED = true;
    public static final String DEFAULT_EXPORT_SUBDIR = "agent-log";

    private String service = DEFAULT_SERVICE;
    private Level level = DEFAULT_LEVEL;
    private boolean redactEnabled = DEFAULT_REDACT_ENABLED;
    private List<String> redactPaths = java.util.Collections.emptyList();
    private String exportDirectory = defaultExportDirectory();
    private String env;
    private String release;

    public static String defaultExportDirectory() {
        return System.getProperty("java.io.tmpdir") + java.io.File.separator + DEFAULT_EXPORT_SUBDIR;
    }

    /** Parse from a JSON string (hand-rolled, no third-party deps). */
    public static LoggingConfig fromJson(String json) {
        LoggingConfig cfg = new LoggingConfig();
        if (json == null || json.trim().isEmpty()) {
            return cfg;
        }
        Map<String, Object> root = Json.parseObject(json);
        Object svc = root.get("service");
        if (svc instanceof String && !((String) svc).isEmpty()) {
            cfg.service = (String) svc;
        }
        Object lv = root.get("level");
        if (lv instanceof String) {
            cfg.level = Level.fromName((String) lv);
        }
        Object re = root.get("redactEnabled");
        if (re instanceof Boolean) {
            cfg.redactEnabled = (Boolean) re;
        }
        Object rd = root.get("redactPaths");
        if (rd instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) rd;
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            cfg.redactPaths = out;
        }
        Object ed = root.get("exportDirectory");
        if (ed instanceof String && !((String) ed).isEmpty()) {
            cfg.exportDirectory = (String) ed;
        }
        Object ev = root.get("env");
        if (ev instanceof String) {
            cfg.env = (String) ev;
        }
        Object rel = root.get("release");
        if (rel instanceof String && !((String) rel).isEmpty()) {
            cfg.release = (String) rel;
        }
        return cfg;
    }

    public String getService() {
        return service;
    }

    public LoggingConfig setService(String service) {
        this.service = service;
        return this;
    }

    public Level getLevel() {
        return level;
    }

    public LoggingConfig setLevel(Level level) {
        this.level = level;
        return this;
    }

    public boolean isRedactEnabled() {
        return redactEnabled;
    }

    public LoggingConfig setRedactEnabled(boolean redactEnabled) {
        this.redactEnabled = redactEnabled;
        return this;
    }

    public List<String> getRedactPaths() {
        return redactPaths;
    }

    public LoggingConfig setRedactPaths(List<String> redactPaths) {
        this.redactPaths = redactPaths;
        return this;
    }

    public String getExportDirectory() {
        return exportDirectory;
    }

    public LoggingConfig setExportDirectory(String exportDirectory) {
        this.exportDirectory = exportDirectory;
        return this;
    }

    public String getEnv() {
        return env;
    }

    public LoggingConfig setEnv(String env) {
        this.env = env;
        return this;
    }

    public String getRelease() {
        return release;
    }

    public LoggingConfig setRelease(String release) {
        this.release = release;
        return this;
    }
}
