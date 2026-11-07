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
- C7 `SchemaTests` conformance suite, plus `LoggerTests`, `RedactorTests`, `ConfigTests`,
  `SpanIdTests`, `JdbcInstrumentationTests`.
