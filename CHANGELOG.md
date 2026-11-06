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
- C7 `SchemaTests` conformance suite, plus `LoggerTests`, `RedactorTests`, `ConfigTests`.
