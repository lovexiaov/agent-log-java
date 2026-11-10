package cn.lovexiaov.agent.log;

import java.util.List;
import java.util.Map;

/**
 * Language-neutral facade (singleton). Entry point for application code.
 *
 * <p>Quickstart:
 * <pre>{@code
 *   AgentLog.bootstrap(LoggingConfig.fromJson("{\"service\":\"my-app\"}"));
 *   AgentLog.info("hello");
 *   AgentLog.child(Map.of("userId", "u1")).warn("user did x");
 *   AgentLog.redact(List.of("customSecret"));
 *   AgentLog.exportPath(); // path of agent-log.jsonl
 * }</pre>
 */
public final class AgentLog {

    private static volatile Logger logger;
    private static final Object LOCK = new Object();

    private AgentLog() {
    }

    /**
     * Bootstrap the framework with config + default FileSink. Idempotent:
     * re-bootstrapping replaces the active logger.
     */
    public static void bootstrap(LoggingConfig config) {
        synchronized (LOCK) {
            FileSink sink = new FileSink(config.getExportDirectory());
            logger = Logger.create(config, sink);
        }
    }

    /** Bootstrap with an explicit sink (useful for tests). */
    public static void bootstrap(LoggingConfig config, LogSink sink) {
        synchronized (LOCK) {
            logger = Logger.create(config, sink);
        }
    }

    public static Logger logger() {
        if (logger == null) {
            throw new IllegalStateException(
                    "AgentLog not bootstrapped. Call AgentLog.bootstrap(config) first.");
        }
        return logger;
    }

    // ------------------------------------------------------ convenience API

    public static void trace(String message) {
        logger().trace(message);
    }

    public static void debug(String message) {
        logger().debug(message);
    }

    public static void info(String message) {
        logger().info(message);
    }

    public static void warn(String message) {
        logger().warn(message);
    }

    public static void error(String message) {
        logger().error(message);
    }

    public static void critical(String message) {
        logger().critical(message);
    }

    public static void info(String message, String category, String traceId, Map<String, Object> payload) {
        logger().info(message, category, traceId, payload);
    }

    public static void warn(String message, String category, String traceId, Map<String, Object> payload) {
        logger().warn(message, category, traceId, payload);
    }

    public static void error(String message, String category, String traceId, Map<String, Object> payload) {
        logger().error(message, category, traceId, payload);
    }

    public static void debug(String message, String category, String traceId, Map<String, Object> payload) {
        logger().debug(message, category, traceId, payload);
    }

    public static void trace(String message, String category, String traceId, Map<String, Object> payload) {
        logger().trace(message, category, traceId, payload);
    }

    public static void critical(String message, String category, String traceId, Map<String, Object> payload) {
        logger().critical(message, category, traceId, payload);
    }

    public static Logger child(Map<String, Object> context) {
        return logger().child(context);
    }

    public static void setLevel(Level level) {
        logger().setLevel(level);
    }

    public static void redact(List<String> paths) {
        logger().redact(paths);
    }

    public static void recordAuto(String eventType, String message, String category,
                                  String traceId, Map<String, Object> payload) {
        logger().recordAuto(eventType, message, category, traceId, payload);
    }

    // ------------------------------------------------------ AI interaction events (Schema v1.4)

    public static void aiRequest(String model, String promptText, Map<String, Object> extra) {
        AiEvents.aiRequest(model, promptText, extra);
    }

    public static void aiResponse(String model, int totalTokens, Map<String, Object> extra) {
        AiEvents.aiResponse(model, totalTokens, extra);
    }

    public static void aiToolCall(String toolName, Object toolArguments, Map<String, Object> extra) {
        AiEvents.aiToolCall(toolName, toolArguments, extra);
    }

    public static void aiToolResult(String toolName, boolean ok, Map<String, Object> extra) {
        AiEvents.aiToolResult(toolName, ok, extra);
    }

    public static void aiGuardrail(String guardrailType, String reason, Map<String, Object> extra) {
        AiEvents.aiGuardrail(guardrailType, reason, extra);
    }

    public static void aiError(String errorType, String message, Map<String, Object> extra) {
        AiEvents.aiError(errorType, message, extra);
    }

    public static String exportPath() {
        return logger().exportPath();
    }

    /** Install the JVM-wide crash handler (records a crash auto-event). */
    public static void installCrashHandler() {
        CoreTypes.AutoInstrumentation.installCrashHandler(AgentLog::recordAuto);
    }
}
