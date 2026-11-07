package com.scaffold.agentlog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OTLP export adapter: Schema v1 event -> OpenTelemetry OTLP/JSON.
 *
 * <p>This is the D3 ("OTel alignment") capability: it does not change the wire
 * format, it <em>translates</em> already-emitted Schema v1 events into OTLP so
 * they can be shipped to Jaeger / Tempo / Loki / Grafana / Datadog through any
 * OTLP-compatible Collector.
 *
 * <p>Mapping summary (authoritative in {@code docs/logging-framework/OTLP-MAPPING.md}):
 *
 * <ul>
 *   <li>every event -> one OTLP {@code LogRecord};</li>
 *   <li>{@code network_request} -> additionally one {@code SPAN_KIND_CLIENT} span
 *       using the HTTP semantic conventions;</li>
 *   <li>{@code db_query} -> additionally one {@code SPAN_KIND_CLIENT} span using
 *       the Database semantic conventions;</li>
 *   <li>{@code crash} / {@code hang} -> LogRecord only, with {@code exception.*}
 *       attributes;</li>
 *   <li>other event types -> LogRecord only.</li>
 * </ul>
 *
 * <p>Design constraints:
 *
 * <ul>
 *   <li><b>Zero dependencies.</b> The mapper produces plain {@code Map}/{@code List}
 *       structures (OTLP/JSON, protobuf JSON mapping) serialized by {@link Json}.</li>
 *   <li><b>Deterministic ids.</b> OTLP requires a 16-byte {@code traceId} and an
 *       8-byte {@code spanId} while Schema v1 only carries an opaque
 *       {@code trace_id}. Ids are derived (SHA-256), never random, so exporting the
 *       same event twice yields the same ids and retries stay idempotent.</li>
 *   <li><b>Payload flattened one level.</b> {@code payload} entries become
 *       {@code payload.<key>} attributes; nested objects/arrays stay as
 *       {@code kvlistValue}/{@code arrayValue}.</li>
 * </ul>
 *
 * <p>NOTE: some OTel semantic-convention keys are still marked <em>Experimental</em>.
 * They are centralized in the {@code SEMCONV_*} constants so a future revision only
 * needs a change in one place.
 */
public final class OtlpMapper {

    /** Keep in sync with the project version (asserted by the test suite). */
    public static final String SDK_VERSION = "1.0.0";

    /** OTel {@code SpanKind.SPAN_KIND_CLIENT}. */
    public static final int SPAN_KIND_CLIENT = 3;

    /** OTel {@code Status.StatusCode.STATUS_CODE_ERROR}. */
    public static final int STATUS_CODE_ERROR = 2;

    // OTel semantic-convention keys (stable ones first).
    private static final String SEMCONV_SERVICE_NAME = "service.name";
    private static final String SEMCONV_DEPLOYMENT_ENV = "deployment.environment";
    private static final String SEMCONV_SESSION_ID = "session.id";
    private static final String SEMCONV_ENDUSER_ID = "enduser.id";
    private static final String SEMCONV_HTTP_METHOD = "http.request.method";
    private static final String SEMCONV_URL_FULL = "url.full";
    private static final String SEMCONV_HTTP_STATUS = "http.response.status_code";
    private static final String SEMCONV_SERVER_ADDRESS = "server.address";
    private static final String SEMCONV_DB_SYSTEM = "db.system";
    private static final String SEMCONV_DB_OPERATION = "db.operation";
    private static final String SEMCONV_DB_STATEMENT = "db.statement";
    private static final String SEMCONV_ERROR_TYPE = "error.type";
    private static final String SEMCONV_EXCEPTION_TYPE = "exception.type";
    private static final String SEMCONV_EXCEPTION_MESSAGE = "exception.message";
    private static final String SEMCONV_EXCEPTION_STACKTRACE = "exception.stacktrace";

    // agent-log specific attribute namespace (avoids colliding with semconv).
    private static final String ATTR_EVENT_TYPE = "agent.event.type";
    private static final String ATTR_AUTO = "agent.auto";
    private static final String ATTR_SUBSYSTEM = "agent.subsystem";
    private static final String ATTR_CATEGORY = "agent.category";

    // payload key -> semconv key, for the operation spans only.
    private static final String[] HTTP_METHOD_KEYS = {"method", "http_method", "http.method"};
    private static final String[] URL_KEYS = {"url", "url_full", "url.full"};
    private static final String[] STATUS_KEYS = {"status_code", "status", "statusCode", "http.status_code"};
    private static final String[] DURATION_KEYS = {"duration_ms", "durationMs", "duration"};
    // The canonical SQL key is `statement` (unified across the six SDKs in Schema
    // v1.1). `sql` / `query` are accepted on READ only, so events already written
    // by older Go/Java builds still map correctly.
    private static final String[] SQL_KEYS = {"statement", "sql", "query"};
    private static final String[] DB_SYSTEM_KEYS = {"db_system", "db.system", "system"};
    private static final String[] DB_OPERATION_KEYS = {"operation", "op"};

    private static final Pattern TS_RE = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})"
                    + "(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern HEX32_RE = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern URL_HOST_RE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)");

    private OtlpMapper() {
    }

    /** A log level translated to the OTel logs data model. */
    public static final class Severity {
        public final String text;
        public final int number;

        Severity(String text, int number) {
            this.text = text;
            this.number = number;
        }
    }

    // ------------------------------------------------------------- normalization

    /**
     * Accept a {@code Map}, a JSONL line, or a {@link LogEvent} and return the
     * event as a map. Centralizing this keeps every public entry point uniform.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> eventOf(Object event) {
        if (event instanceof Map) {
            return (Map<String, Object>) event;
        }
        if (event instanceof String) {
            return Json.parseObject((String) event);
        }
        if (event instanceof LogEvent) {
            return Json.parseObject(((LogEvent) event).toJsonLine());
        }
        throw new IllegalArgumentException("unsupported event type: " + event);
    }

    // -------------------------------------------------------- value conversion

    /**
     * Convert a value to an OTLP {@code AnyValue} (proto3 JSON form).
     *
     * <p>{@code int64} is rendered as a string, as mandated by the protobuf JSON
     * mapping: JSON numbers are doubles and cannot represent 64-bit integers
     * exactly.
     */
    public static Map<String, Object> toAnyValue(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Boolean) {
            out.put("boolValue", value);
        } else if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            out.put("intValue", String.valueOf(value));
        } else if (value instanceof Number) {
            out.put("doubleValue", ((Number) value).doubleValue());
        } else if (value instanceof String) {
            out.put("stringValue", value);
        } else if (value instanceof List) {
            List<Object> items = new ArrayList<>();
            for (Object item : (List<?>) value) {
                items.add(toAnyValue(item));
            }
            Map<String, Object> arrayValue = new LinkedHashMap<>();
            arrayValue.put("values", items);
            out.put("arrayValue", arrayValue);
        } else if (value instanceof Map) {
            Map<String, Object> kvlistValue = new LinkedHashMap<>();
            kvlistValue.put("values", keyValues((Map<?, ?>) value, ""));
            out.put("kvlistValue", kvlistValue);
        } else {
            // bytes / date / custom objects: fall back to their string form so a
            // stray payload value never breaks an export.
            out.put("stringValue", String.valueOf(value));
        }
        return out;
    }

    /** Build one OTLP {@code KeyValue}. */
    public static Map<String, Object> toKeyValue(String key, Object value) {
        Map<String, Object> kv = new LinkedHashMap<>();
        kv.put("key", key);
        kv.put("value", toAnyValue(value));
        return kv;
    }

    /** Flatten a mapping into a list of {@code KeyValue} ({@code null} skipped). */
    private static List<Map<String, Object>> keyValues(Map<?, ?> mapping, String prefix) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (mapping == null) {
            return out;
        }
        for (Map.Entry<?, ?> entry : mapping.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            out.add(toKeyValue(prefix + entry.getKey(), entry.getValue()));
        }
        return out;
    }

    // --------------------------------------------------------------------- time

    /**
     * Parse an RFC3339 / ISO8601 timestamp into epoch nanoseconds.
     *
     * <p>Sub-nanosecond precision is truncated. A timestamp without an explicit
     * offset is interpreted as UTC (matching the other SDKs).
     */
    public static long parseTimestampNanos(String ts) {
        if (ts == null) {
            throw new IllegalArgumentException("not an RFC3339 timestamp: null");
        }
        Matcher m = TS_RE.matcher(ts.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not an RFC3339 timestamp: " + ts);
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));

        String frac = m.group(7) == null ? "" : m.group(7);
        long nanos = 0L;
        if (!frac.isEmpty()) {
            String padded = (frac + "000000000").substring(0, 9);
            nanos = Long.parseLong(padded);
        }

        ZoneOffset offset = parseOffset(m.group(8));
        long epochSecond = LocalDateTime.of(year, month, day, hour, minute, second)
                .toEpochSecond(offset);
        return epochSecond * 1000000000L + nanos;
    }

    private static ZoneOffset parseOffset(String raw) {
        if (raw == null || "Z".equals(raw) || "z".equals(raw)) {
            return ZoneOffset.UTC;
        }
        int sign = raw.charAt(0) == '-' ? -1 : 1;
        String digits = raw.substring(1).replace(":", "");
        int hours = Integer.parseInt(digits.substring(0, 2));
        int minutes = digits.length() >= 4 ? Integer.parseInt(digits.substring(2, 4)) : 0;
        return ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60));
    }

    // ---------------------------------------------------------------------- ids

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JRE; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Return the 32-hex-char OTLP {@code traceId} for an event, or {@code null}.
     *
     * <p>Schema v1 only requires {@code trace_id} to be opaque and
     * traceparent-aligned; a 32-hex value is used verbatim, anything else is hashed
     * deterministically so all events sharing one logical id stay in one trace.
     */
    public static String traceIdHex(Map<String, Object> event) {
        String raw = str(event.get("trace_id"));
        if (raw.isEmpty()) {
            return null;
        }
        if (HEX32_RE.matcher(raw).matches()) {
            return raw.toLowerCase(java.util.Locale.ROOT);
        }
        return sha256Hex("agentlog:trace:" + raw).substring(0, 32);
    }

    /**
     * Return the 16-hex-char OTLP {@code spanId} for an event.
     *
     * <p>A Schema v1.1 {@code span_id} wins when present and valid, so the log
     * record and the span point at the same real span. Otherwise the id is derived
     * deterministically (idempotent across retries).
     */
    public static String spanIdHex(Map<String, Object> event) {
        String explicit = CoreTypes.SpanId.normalize(str(event.get("span_id")));
        if (explicit != null) {
            return explicit;
        }
        String seed = String.join("|",
                "agentlog:span",
                str(event.get("session_id")),
                str(event.get("trace_id")),
                str(event.get("event_type")),
                str(event.get("ts")),
                str(event.get("message")));
        return sha256Hex(seed).substring(0, 16);
    }

    /**
     * Trace id for a span, falling back to a per-session id when absent.
     *
     * <p>Spans cannot exist outside a trace, so events without {@code trace_id}
     * still need one. Hashing the session id keeps every span of one process in a
     * single synthetic trace instead of inventing one trace per span.
     */
    public static String spanTraceId(Map<String, Object> event) {
        String resolved = traceIdHex(event);
        if (resolved != null) {
            return resolved;
        }
        return sha256Hex("agentlog:session:" + str(event.get("session_id"))).substring(0, 32);
    }

    // -------------------------------------------------------------------- level

    /**
     * Map an agent-log level to {@code severityText}/{@code severityNumber}.
     *
     * <p>The read-side alias {@code warning} is normalized to {@code warn}; an
     * unknown level degrades to INFO rather than failing the whole export.
     */
    public static Severity levelToSeverity(String level) {
        Level canonical = Level.fromWire(level);
        switch (canonical) {
            case TRACE:
                return new Severity("TRACE", 1);
            case DEBUG:
                return new Severity("DEBUG", 5);
            case WARN:
                return new Severity("WARN", 13);
            case ERROR:
                return new Severity("ERROR", 17);
            case CRITICAL:
                return new Severity("FATAL", 21);
            case INFO:
            default:
                return new Severity("INFO", 9);
        }
    }

    // ----------------------------------------------------------------- resource

    private static Map<String, Object> scope() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", "agentlog.otlp");
        scope.put("version", SDK_VERSION);
        return scope;
    }

    private static List<Map<String, Object>> resourceAttributes(Map<String, Object> event) {
        List<Map<String, Object>> attrs = new ArrayList<>();
        String service = str(event.get("service"));
        attrs.add(toKeyValue(SEMCONV_SERVICE_NAME, service.isEmpty() ? "app" : service));
        String env = str(event.get("env"));
        if (!env.isEmpty()) {
            attrs.add(toKeyValue(SEMCONV_DEPLOYMENT_ENV, env));
        }
        String sessionId = str(event.get("session_id"));
        if (!sessionId.isEmpty()) {
            attrs.add(toKeyValue(SEMCONV_SESSION_ID, sessionId));
        }
        attrs.add(toKeyValue("telemetry.sdk.name", "agent-log"));
        attrs.add(toKeyValue("telemetry.sdk.language", "java"));
        attrs.add(toKeyValue("telemetry.sdk.version", SDK_VERSION));
        return attrs;
    }

    /** Group events by the resource-defining fields, preserving input order. */
    private static List<List<Map<String, Object>>> groupByResource(List<?> events) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Object raw : events) {
            Map<String, Object> event = eventOf(raw);
            String service = str(event.get("service"));
            String key = (service.isEmpty() ? "app" : service)
                    + '\u0000' + str(event.get("env"))
                    + '\u0000' + str(event.get("session_id"));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        }
        return new ArrayList<>(groups.values());
    }

    private static List<Map<String, Object>> mergeExtraResource(
            List<Map<String, Object>> resource, Map<String, Object> extraResource) {
        if (extraResource == null || extraResource.isEmpty()) {
            return resource;
        }
        // Duplicate keys in OTLP are resolved arbitrarily by backends (usually
        // last-wins), so the spec rule "agent-log's own fields win on conflict"
        // has to be enforced here by dropping the extra key entirely.
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Map<String, Object> kv : resource) {
            taken.add(str(kv.get("key")));
        }
        for (Map<String, Object> kv : keyValues(extraResource, "")) {
            String key = str(kv.get("key"));
            if (taken.contains(key)) {
                continue;
            }
            resource.add(kv);
            taken.add(key);
        }
        return resource;
    }

    // ----------------------------------------------------------------- logrecord

    /** Translate the non-resource event fields into OTLP attributes. */
    public static List<Map<String, Object>> eventAttributes(Map<String, Object> event) {
        List<Map<String, Object>> attrs = new ArrayList<>();
        Object eventType = event.get("event_type");
        if (eventType != null) {
            attrs.add(toKeyValue(ATTR_EVENT_TYPE, eventType));
        }
        attrs.add(toKeyValue(ATTR_AUTO, Boolean.TRUE.equals(event.get("agent_auto"))));
        String subsystem = str(event.get("subsystem"));
        if (!subsystem.isEmpty()) {
            attrs.add(toKeyValue(ATTR_SUBSYSTEM, subsystem));
        }
        String category = str(event.get("category"));
        if (!category.isEmpty()) {
            attrs.add(toKeyValue(ATTR_CATEGORY, category));
        }
        String userId = str(event.get("user_id"));
        if (!userId.isEmpty()) {
            // Indirect identifier only — Schema v1 forbids direct PII here.
            attrs.add(toKeyValue(SEMCONV_ENDUSER_ID, userId));
        }
        Map<String, Object> payload = asMap(event.get("payload"));
        attrs.addAll(keyValues(payload, "payload."));
        String type = str(eventType);
        if ("crash".equals(type) || "hang".equals(type)) {
            attrs.addAll(exceptionAttributes(payload));
        }
        return attrs;
    }

    private static List<Map<String, Object>> exceptionAttributes(Map<String, Object> payload) {
        List<Map<String, Object>> attrs = new ArrayList<>();
        String excType = firstOf(payload, "exception", "exception_type");
        if (excType != null) {
            attrs.add(toKeyValue(SEMCONV_EXCEPTION_TYPE, excType));
        }
        // The crash payload reuses `message` for the exception text; keep both
        // spellings so either SDK variant maps cleanly.
        String excMessage = firstOf(payload, "exception_message", "message");
        if (excMessage != null) {
            attrs.add(toKeyValue(SEMCONV_EXCEPTION_MESSAGE, excMessage));
        }
        String stack = firstOf(payload, "stack", "stacktrace", "trace");
        if (stack != null) {
            attrs.add(toKeyValue(SEMCONV_EXCEPTION_STACKTRACE, stack));
        }
        return attrs;
    }

    /** Convert one Schema v1 event into an OTLP {@code LogRecord}. */
    public static Map<String, Object> eventToLogRecord(Map<String, Object> event) {
        Severity severity = levelToSeverity(str(event.get("level")));
        String timeNanos = Long.toString(parseTimestampNanos(str(event.get("ts"))));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timeUnixNano", timeNanos);
        record.put("observedTimeUnixNano", timeNanos);
        record.put("severityNumber", severity.number);
        record.put("severityText", severity.text);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stringValue", str(event.get("message")));
        record.put("body", body);
        record.put("attributes", eventAttributes(event));

        String traceId = traceIdHex(event);
        if (traceId != null) {
            record.put("traceId", traceId);
            // Prefer the event's own (Schema v1.1) span id — it points at a real
            // span. Otherwise only operation events get an id, matching the span
            // emitted for them, so a log record never references a missing span.
            String explicitSpan = CoreTypes.SpanId.normalize(str(event.get("span_id")));
            if (explicitSpan != null) {
                record.put("spanId", explicitSpan);
            } else if (spanKind(event) != null) {
                record.put("spanId", spanIdHex(event));
            }
        }
        return record;
    }

    // --------------------------------------------------------------------- span

    /** Convert an operation event into an OTLP {@code Span}, or {@code null}. */
    public static Map<String, Object> eventToSpan(Map<String, Object> event) {
        Integer kind = spanKind(event);
        if (kind == null) {
            return null;
        }
        Map<String, Object> payload = asMap(event.get("payload"));
        String eventType = str(event.get("event_type"));
        String name;
        List<Map<String, Object>> attrs;
        boolean failed;
        if ("network_request".equals(eventType)) {
            name = httpSpanName(payload);
            attrs = httpSpanAttributes(payload);
            failed = httpSpanFailed(payload);
        } else {
            name = dbSpanName(payload);
            attrs = dbSpanAttributes(payload);
            failed = dbSpanFailed(payload);
        }

        long start = parseTimestampNanos(str(event.get("ts")));
        long end = start + (long) (durationMs(payload) * 1000000d);

        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", spanTraceId(event));
        span.put("spanId", spanIdHex(event));
        span.put("name", name);
        span.put("kind", kind);
        span.put("startTimeUnixNano", Long.toString(start));
        span.put("endTimeUnixNano", Long.toString(end));
        span.put("attributes", attrs);
        if (failed) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("code", STATUS_CODE_ERROR);
            status.put("message", name);
            span.put("status", status);
        }
        return span;
    }

    /** Only {@code network_request} / {@code db_query} are call spans. */
    private static Integer spanKind(Map<String, Object> event) {
        String type = str(event.get("event_type"));
        if ("network_request".equals(type) || "db_query".equals(type)) {
            return SPAN_KIND_CLIENT;
        }
        return null;
    }

    private static String httpSpanName(Map<String, Object> payload) {
        String method = firstOf(payload, HTTP_METHOD_KEYS);
        String url = firstOf(payload, URL_KEYS);
        return (method == null ? "HTTP" : method) + " " + (url == null ? "" : url);
    }

    private static List<Map<String, Object>> httpSpanAttributes(Map<String, Object> payload) {
        List<Map<String, Object>> attrs = new ArrayList<>();
        String method = firstOf(payload, HTTP_METHOD_KEYS);
        String url = firstOf(payload, URL_KEYS);
        Number status = toNumber(firstOf(payload, STATUS_KEYS));
        if (method != null) {
            attrs.add(toKeyValue(SEMCONV_HTTP_METHOD, method));
        }
        if (url != null) {
            attrs.add(toKeyValue(SEMCONV_URL_FULL, url));
            String host = hostOf(url);
            if (host != null) {
                attrs.add(toKeyValue(SEMCONV_SERVER_ADDRESS, host));
            }
        }
        if (status != null) {
            attrs.add(toKeyValue(SEMCONV_HTTP_STATUS, status.longValue()));
        }
        String error = httpError(payload, status);
        if (error != null) {
            attrs.add(toKeyValue(SEMCONV_ERROR_TYPE, error));
        }
        return attrs;
    }

    private static String httpError(Map<String, Object> payload, Number status) {
        if (status != null && status.doubleValue() >= 400) {
            return String.valueOf(status.longValue());
        }
        String error = str(payload.get("error"));
        return error.isEmpty() ? null : error;
    }

    private static boolean httpSpanFailed(Map<String, Object> payload) {
        Number status = toNumber(firstOf(payload, STATUS_KEYS));
        return httpError(payload, status) != null;
    }

    private static String dbSpanName(Map<String, Object> payload) {
        String operation = firstOf(payload, DB_OPERATION_KEYS);
        String statement = firstOf(payload, SQL_KEYS);
        return (operation == null ? "SQL" : operation) + " " + (statement == null ? "" : statement);
    }

    private static List<Map<String, Object>> dbSpanAttributes(Map<String, Object> payload) {
        List<Map<String, Object>> attrs = new ArrayList<>();
        String system = firstOf(payload, DB_SYSTEM_KEYS);
        String operation = firstOf(payload, DB_OPERATION_KEYS);
        String statement = firstOf(payload, SQL_KEYS);
        if (system != null) {
            attrs.add(toKeyValue(SEMCONV_DB_SYSTEM, system));
        }
        if (operation != null) {
            attrs.add(toKeyValue(SEMCONV_DB_OPERATION, operation));
        }
        if (statement != null) {
            attrs.add(toKeyValue(SEMCONV_DB_STATEMENT, statement));
        }
        String error = dbError(payload);
        if (error != null) {
            attrs.add(toKeyValue(SEMCONV_ERROR_TYPE, error));
        }
        return attrs;
    }

    private static String dbError(Map<String, Object> payload) {
        String error = str(payload.get("error"));
        if (!error.isEmpty()) {
            return error;
        }
        String errorType = str(payload.get("error_type"));
        return errorType.isEmpty() ? null : errorType;
    }

    private static boolean dbSpanFailed(Map<String, Object> payload) {
        return dbError(payload) != null;
    }

    /** Extract the host part of an absolute URL ({@code server.address}). */
    private static String hostOf(String url) {
        Matcher m = URL_HOST_RE.matcher(url);
        if (!m.find()) {
            return null;
        }
        String host = m.group(1);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close > 0 ? host.substring(1, close) : host;
        }
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    // -------------------------------------------------------------------- batch

    /** Build an OTLP/JSON {@code ExportLogsServiceRequest} body. */
    public static Map<String, Object> toOtlpLogs(List<?> events, Map<String, Object> extraResource) {
        List<Map<String, Object>> resourceLogs = new ArrayList<>();
        for (List<Map<String, Object>> group : groupByResource(events)) {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Map<String, Object> event : group) {
                records.add(eventToLogRecord(event));
            }
            Map<String, Object> scopeLogs = new LinkedHashMap<>();
            scopeLogs.put("scope", scope());
            scopeLogs.put("logRecords", records);

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("attributes", mergeExtraResource(resourceAttributes(group.get(0)), extraResource));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("resource", resource);
            entry.put("scopeLogs", List.of(scopeLogs));
            resourceLogs.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceLogs", resourceLogs);
        return body;
    }

    /** Build an OTLP/JSON {@code ExportTraceServiceRequest} body. */
    public static Map<String, Object> toOtlpSpans(List<?> events, Map<String, Object> extraResource) {
        List<Map<String, Object>> resourceSpans = new ArrayList<>();
        for (List<Map<String, Object>> group : groupByResource(events)) {
            List<Map<String, Object>> spans = new ArrayList<>();
            for (Map<String, Object> event : group) {
                Map<String, Object> span = eventToSpan(event);
                if (span != null) {
                    spans.add(span);
                }
            }
            if (spans.isEmpty()) {
                // No operation events: skip the group entirely instead of emitting
                // an empty resource block (and, at the transport layer, a request).
                continue;
            }
            Map<String, Object> scopeSpans = new LinkedHashMap<>();
            scopeSpans.put("scope", scope());
            scopeSpans.put("spans", spans);

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("attributes", mergeExtraResource(resourceAttributes(group.get(0)), extraResource));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("resource", resource);
            entry.put("scopeSpans", List.of(scopeSpans));
            resourceSpans.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceSpans", resourceSpans);
        return body;
    }

    /**
     * Build both signals for one batch of events.
     *
     * <p>Returns {@code {"logs": ..., "traces": ...}}. OTLP transports one signal
     * per request, so callers POST {@code logs} to {@code /v1/logs} and
     * {@code traces} to {@code /v1/traces}.
     */
    public static Map<String, Object> toOtlp(List<?> events, Map<String, Object> extraResource) {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("logs", toOtlpLogs(events, extraResource));
        both.put("traces", toOtlpSpans(events, extraResource));
        return both;
    }

    /** Serialize an OTLP payload to compact JSON. */
    public static String toOtlpJson(Object payload) {
        return Json.stringify(payload);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return map;
        }
        return java.util.Collections.emptyMap();
    }

    private static String firstOf(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private static Number toNumber(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException alsoIgnored) {
                return null;
            }
        }
    }

    private static double durationMs(Map<String, Object> payload) {
        Number value = toNumber(firstOf(payload, DURATION_KEYS));
        return value == null ? 0d : value.doubleValue();
    }

    /**
     * A sink that buffers events and renders them as OTLP batches.
     *
     * <p>Usage:
     * <pre>{@code
     * OtlpSink sink = new OtlpSink();
     * AgentLog.bootstrap(config, sink);
     * ...
     * Map<String, Object> batch = sink.drain();      // {logs: ..., traces: ...}
     * String body = OtlpMapper.toOtlpJson(batch.get("logs"));
     * // POST body to <collector>/v1/logs with Content-Type: application/json
     * }</pre>
     *
     * <p>The sink stores the <em>serialized</em> lines exactly as written, so
     * redaction and min-level filtering have already been applied by the logger.
     */
    public static final class OtlpSink implements LogSink {

        private final List<String> lines = new ArrayList<>();
        private final Map<String, Object> extraResource;

        public OtlpSink() {
            this(null);
        }

        public OtlpSink(Map<String, Object> extraResource) {
            this.extraResource = extraResource == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(extraResource);
        }

        @Override
        public synchronized void write(String jsonLine) {
            lines.add(jsonLine);
        }

        public synchronized List<String> lines() {
            return new ArrayList<>(lines);
        }

        public synchronized int size() {
            return lines.size();
        }

        /** Return the OTLP batch for buffered events and clear the buffer. */
        public synchronized Map<String, Object> drain() {
            List<Object> batch = new ArrayList<>(lines);
            lines.clear();
            return toOtlp(batch, extraResource);
        }
    }
}
