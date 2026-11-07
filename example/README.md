# Java 示例演示

演示 [AgentLog Java](../README.md) 的端到端用法，覆盖契约要求的全部能力：

- `bootstrap` + `LoggingConfig`（零运行时依赖，手写 JSON，Schema v1 线格式）
- 6 档级别：`trace / debug / info / warn / error / critical`
- `child`：合并作用域上下文（`userId` / `subsystem`），返回新 logger，父级不变
- `redact`：默认敏感键不可关闭，可追加自定义键
- `setLevel`：调高最低级别后低级别事件被过滤
- crash 自动埋点：安装后未捕获异常被记为 `crash` 事件（`agent_auto=true`）

## 运行

在仓库根目录执行：

```bash
mvn -q -B compile
javac -cp target/classes -d target/example example/Example.java
java  -cp target/classes:target/example example.Example
```

> Windows 上 classpath 分隔符用 `;` 而非 `:`。

事件落盘于系统临时目录下的 `agent-log-demo/agent-log.jsonl`，程序结束前会打印该路径与部分内容。
每条记录均为 Schema v1 合规的 JSONL（顶层封闭、snake_case），可直接被 Agent 按行消费。
