# Changelog

## [1.0.0] - 2026-11-07

### Added
- Initial release of the AgentLog Java logging framework.
- Schema v1 JSONL wire format (zero third-party JSON deps — hand-rolled parser/serializer).
- Core `Logger` with `emit`, `child`, `setLevel`, `redact`.
- Six levels (`trace|debug|info|warn|error|critical`); `warning` normalized to `warn`.
- Field mapping camelCase → snake_case at the serialization boundary; non-reserved keys
  routed to `payload`.
- `minLevel` filtering for manual (`agent_auto=false`) events; auto events always logged.
- `Redactor` with append-only default sensitive keys, key-mask, free-text regex, and
  recursive object/array redaction.
- Sinks: `FileSink` (append JSONL) and `MemorySink` (tests).
- `LoggingConfig.fromJson` (hand-rolled) with defaults.
- `AgentLog` facade singleton + `installCrashHandler` auto-instrumentation demo.
- JDBC auto-instrumentation (`db_query`) via `JdbcInstrumentation`.
- Schema v1.1: optional top-level `span_id` (16 lowercase hex chars, same shape as the
  W3C `traceparent` span-id). Bind it via `Logger.withSpan(traceId, spanId)` or
  `child(Map.of("span_id", …))` — `child` accepts both camelCase and snake_case for the
  reserved scope keys, while the wire format stays snake_case. Malformed values are
  dropped instead of written, so a bad value can never produce a schema-violating line.
- OTLP/JSON adapter (`OtlpMapper`), zero third-party deps (`java.base` only): one
  `LogRecord` per event, plus a `SPAN_KIND_CLIENT` span for `network_request` / `db_query`
  (HTTP and database semantic conventions). Severity 6→6 mapping (`critical` → `FATAL`=21),
  `payload.*` flattened one level, int64 (nanos / counts) emitted as strings.
  Entry points: `toOtlp`, `toOtlpLogs`, `toOtlpSpans`, `eventToLogRecord`, `eventToSpan`,
  `toOtlpJson`, and the `OtlpSink` `LogSink`.
- Deterministic OTLP ids (retry-safe, no randomness): 32-hex `trace_id` passes through
  lowercased, anything else is `sha256("agentlog:trace:"+raw)[:32]`; a span without a trace
  falls back to `sha256("agentlog:session:"+session_id)[:32]`; a missing `span_id` is derived
  from `session_id|trace_id|event_type|ts|message`, while a schema-valid event `span_id` is
  passed through as-is. `LogRecord` only carries `spanId` when a trace id exists, so no
  orphan spans are produced.
- OTLP batch layout grouped by `(service, env, session_id)` preserving insertion order, with
  scope `{name:"agentlog.otlp", version:"1.0.0"}`; conflicting `extraResource` keys are
  dropped so agent-log's own resource attributes always win.
- `OtlpMapperTests` (27 cases): severity table, `warning` alias, AnyValue typing, timestamp
  parsing (`Z` / `+08:00` / `+0800` / space separator / sub-nanosecond truncation), id
  derivation, LogRecord shape, HTTP + DB spans, resource grouping, `extraResource` conflicts,
  and compact (no-whitespace) JSON serialization.
- C7 `SchemaTests` conformance suite, plus `LoggerTests`, `RedactorTests`, `ConfigTests`,
  `SpanIdTests`, `JdbcInstrumentationTests`.
