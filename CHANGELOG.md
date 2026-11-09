# Changelog

## [Unreleased]

Schema v1.3（向后兼容增量）。

### Added

- 顶层可选 `release`：`new LoggingConfig().setRelease("1.4.2")` 或配置文件
  `{"release": "1.4.2"}` 配置后由 `Logger` 统一盖章到**每条**事件，不校验格式、
  仅限非空且 ≤128 字符；未配置则不写该键。
- OTLP：`release` 映射为 resource 的 `service.version`。

### Changed（异常 payload 标准词表）

- 未捕获异常 / 崩溃事件统一按标准词表写 `payload`：`exception`（类型名）、
  `exception_message`（原始消息）、`stack`（单字符串）、`mechanism`
  （`uncaught` / `handled` / `reported`）。`installCrashHandler()` 的 payload 由标准键组成。
- OTLP 适配器读取侧继续兼容历史键（`message` / `stacktrace` / `trace`），老日志
  映射行为不变。

### Docs

- 与 `ai-dev-spec` 的 Schema v1.3 / `INTERFACE.md` §4「异常与版本」对齐；
  说明框架只产出数据，不做上报 / APM 集成 / 崩溃落盘。

## [1.2.0] - 2026-11-07

### Changed（Schema v1.2，破坏性）

- `trace_id` 收紧为 **32 位小写 hex**（8 字节，与 OTLP `traceId` / W3C traceparent
  trace-id 同构）。大写自动归一；非法值在序列化边界**丢弃该字段、事件照写**，与
  `span_id` 策略一致。适配器不再对非 hex 值做 `sha256("agentlog:trace:"+raw)` 派生。
  发布时尚无生产使用方（Schema v1.2 描述内已注明）。
- session id 默认生成值改为 32 位小写 hex：`trace_id = session_id` 这个最自然的
  绑定永远合法。

### Added

- `newTraceId()`：生成 Schema 合法的 trace id（uuid4 去横线）。

### Docs

- README 新增 `JdbcInstrumentation`（包装 `DataSource`/`Connection` → `db_query`）与
  `installCrashHandler()` 自动埋点说明。

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
