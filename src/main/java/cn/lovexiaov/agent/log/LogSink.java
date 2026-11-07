package cn.lovexiaov.agent.log;

/**
 * Output destination for serialized JSONL lines.
 */
public interface LogSink {
    /** Write one JSONL line (without a trailing newline added by caller). */
    void write(String jsonLine);
}
