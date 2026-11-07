package example;

import com.scaffold.agentlog.AgentLog;
import com.scaffold.agentlog.JdbcInstrumentation;
import com.scaffold.agentlog.Level;
import com.scaffold.agentlog.LoggingConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentLog Java 日志框架示例演示。
 *
 * <p>覆盖契约要求的全部能力：
 * <ul>
 *   <li>bootstrap：从 {@link LoggingConfig} 初始化（零运行时依赖，手写 JSON）；</li>
 *   <li>6 档级别：trace/debug/info/warn/error/critical；</li>
 *   <li>child：合并作用域上下文（userId / subsystem），返回新 logger，父级不变；</li>
 *   <li>redact：默认敏感键不可关闭，额外追加自定义键；</li>
 *   <li>setLevel：调高最低级别后，低级别事件被过滤；</li>
 *   <li>crash 自动埋点：安装后未捕获异常被记为 crash 事件（agent_auto=true）。</li>
 * </ul>
 *
 * <p>运行（仓库根目录）：
 * <pre>{@code
 *   mvn -q -B compile
 *   javac -cp target/classes -d target/example example/Example.java
 *   java  -cp target/classes:target/example example.Example
 * }</pre>
 * Windows 上 classpath 分隔符用 {@code ;} 而非 {@code :}。
 * 事件落盘于系统临时目录下的 {@code agent-log-demo/agent-log.jsonl}。
 */
public final class Example {

    private Example() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String dir = System.getProperty("java.io.tmpdir")
                + java.io.File.separator + "agent-log-demo";

        LoggingConfig cfg = new LoggingConfig()
                .setService("demo-app")
                .setEnv("dev")
                .setLevel(Level.DEBUG)
                .setExportDirectory(dir);
        AgentLog.bootstrap(cfg);

        // 1) 各档级别（category 用于归类，traceId 用于跨端串联）
        AgentLog.info("应用启动", "app", null, null);
        AgentLog.debug("调试细节", "app", "trace_abc", of("step", "init"));
        AgentLog.warn("磁盘空间偏低", "system", null, of("freeMb", 42));
        AgentLog.error("连接失败", "net", null, of("host", "db.example.com"));
        AgentLog.trace("细粒度追踪", "app", null, of("span", "boot"));
        AgentLog.critical("核心服务不可用", "core", null, of("region", "cn"));

        // 2) child：合并作用域上下文（userId / subsystem），不改父 logger
        AgentLog.child(scope("userId", "u-1234", "subsystem", "auth"))
                .info("用户完成登录", "auth", null, of("method", "oauth"));

        // 3) 脱敏：默认敏感键（password/token/secret…）不可关闭；此处追加 sessionToken
        AgentLog.redact(List.of("sessionToken"));
        AgentLog.info("登录载荷", "auth", null, of(
                "password", "hunter2",
                "sessionToken", "topsecret",
                "username", "alice"));

        // 4) setLevel：调高到 WARN，后续 info 被过滤
        AgentLog.setLevel(Level.WARN);
        AgentLog.info("这条不会写出（被 WARN 过滤）", "app", null, null);
        AgentLog.warn("这条会写出", "app", null, null);

        // 5) crash 自动埋点：安装后，未捕获异常被记为 crash 事件
        AgentLog.installCrashHandler();
        new Thread(() -> {
            throw new RuntimeException("演示崩溃：验证 crash 自动埋点");
        }, "demo-crash").start();
        Thread.sleep(200); // 等子线程把 crash 事件刷盘

        // 7) DB 自动埋点（event_type = db_query）：生产环境用 JdbcInstrumentation.wrap(dataSource)
        //    包裹数据源即可让每条 SQL 自动记录；此处直接演示 recordDbQuery 事件（无需真实数据库）。
        JdbcInstrumentation.recordDbQuery(
                "exec", "INSERT INTO orders(user_id) VALUES ('u-1234')", 3, true, 1, null);

        // 6) 展示落盘结果
        Path jsonl = Path.of(AgentLog.exportPath());
        System.out.println("导出路径: " + jsonl);
        System.out.println("--- agent-log.jsonl（前若干行）---");
        Files.lines(jsonl).limit(12).forEach(System.out::println);
    }

    /** 构造 payload：必须为 {@code Map<String,Object>}（契约方法签名要求）。 */
    @SafeVarargs
    private static Map<String, Object> of(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return m;
    }

    /** 构造 child 作用域上下文（仅保留键会被合并）。 */
    private static Map<String, Object> scope(Object... keysAndValues) {
        return of(keysAndValues);
    }
}
