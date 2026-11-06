# Contributing

Thanks for contributing to `agent-log-java`!

## Prerequisites
- Java 17 (build uses `maven-compiler-plugin` `<release>17</release>`).
- Maven 3.8+.

## Build & Test
```bash
mvn -q test                 # run all tests (SchemaTests, Logger, Redactor, Config)
mvn -q checkstyle:check     # lint
mvn -q clean package        # build the jar
```

## Coding standards
- **No third-party runtime/compile dependencies.** JSON is hand-rolled in
  `Json.java`. Do not add libraries for JSON, UUID, time, etc.
- Keep the wire format conformant to `docs/schema/log-event.schema.json`
  (`additionalProperties:false`, snake_case, 6 lowercase levels).
- Run `mvn checkstyle:check` — no tabs, ordered imports, braces on all blocks.
- Match the language-neutral contract in `docs/INTERFACE.md`.

## Tests
- Every change that alters output must keep `SchemaTests` green.
- Add/extend tests under `src/test/java/com/scaffold/agentlog/`.

## Commit / PR
- Keep commits focused and descriptive.
- CI runs `mvn test` (hard gate) and `checkstyle:check` (reported).
