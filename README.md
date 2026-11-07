# agent-log-java

Language-neutral **AgentLog** logging framework for Java — conforming to
[Schema v1](../docs/schema/log-event.schema.json) of the
[language-neutral logging contract](../docs/INTERFACE.md).

- **Zero third-party dependencies.** JSON serialization + parsing is hand-rolled
  (`cn.lovexiaov.agent.log.Json`). Only JUnit is used (test scope).
- **Wire format** = Schema v1 JSONL (RFC3339 `ts`, lowercase levels, snake_case).
- **Auto-instrumentation** path (`agent_auto=true`) always logged.
- **Redaction** at the logger boundary — default sensitive keys can never be removed.

## Quickstart

```java
import cn.lovexiaov.agent.log.*;

// 1. Bootstrap (FileSink → <tmp>/agent-log/agent-log.jsonl)
AgentLog.bootstrap(LoggingConfig.fromJson("{\"service\":\"my-app\",\"env\":\"dev\"}"));

// 2. Manual logs (respect minLevel)
AgentLog.info("app started");
AgentLog.warn("disk almost full", "app", null, Map.of("freeMb", 42));
AgentLog.error("boom");

// 3. Scoped child logger (merges context, parent unchanged)
Logger userLog = AgentLog.child(Map.of("userId", "u1"));
userLog.info("user signed in"); // carries user_id="u1"

// 4. Append-only redaction
AgentLog.redact(List.of("customSecret"));

// 5. Auto-instrumented events (always logged regardless of minLevel)
AgentLog.recordAuto("network_request", "GET /api -> 200",
        "auto", "trace_abc", Map.of("host", "api.example.com", "status", 200));

// 6. Crash handler (records a crash auto-event on uncaught exceptions)
AgentLog.installCrashHandler();

// 7. Where the JSONL lives
String path = AgentLog.exportPath();
```

## API

### Facade `AgentLog` (singleton)
| Method | Description |
| --- | --- |
| `bootstrap(LoggingConfig)` / `bootstrap(LoggingConfig, LogSink)` | Initialize. |
| `trace/debug/info/warn/error/critical(message)` | Manual log, default category `app`. |
| `info(message, category, traceId, payload)` (and siblings) | Manual log with extras. |
| `child(context)` | New scoped logger (does not mutate parent). |
| `setLevel(Level)` | Set minLevel. |
| `redact(List<String>)` | Append sensitive keys/paths. |
| `recordAuto(eventType, message, category, traceId, payload)` | `agent_auto=true` event. |
| `exportPath()` | Path of the JSONL file. |
| `installCrashHandler()` | JVM-wide uncaught-exception → crash event. |

### Levels
`trace | debug | info | warn | error | critical` (6, lowercase).
`"warning"` is a read-only alias normalized to `"warn"`; it is never written.

### Config (`LoggingConfig.fromJson`)
| Key | Default |
| --- | --- |
| `service` | `"app"` |
| `env` | none |
| `level` | `"info"` |
| `redactEnabled` | `true` |
| `redactPaths` | `[]` (append only) |
| `exportDirectory` | `<java.io.tmpdir>/agent-log` |

## Build & Test

```bash
mvn -q test          # run the test suite (SchemaTests, Logger, Redactor, Config)
mvn -q checkstyle:check   # lint
```

## Repository layout
```
src/main/java/com/scaffold/agentlog/   core (Logger, Json, Level, CoreTypes, LogEvent,
                                       Redactor, AgentLog, LoggingConfig, LogSink,
                                       FileSink, MemorySink)
src/test/java/com/scaffold/agentlog/   SchemaTests, LoggerTests, RedactorTests, ConfigTests
docs/                                  INTERFACE.md + schema (verbatim contract)
config/                                checkstyle.xml, logging.config.json
```

## License
[MIT](LICENSE)
