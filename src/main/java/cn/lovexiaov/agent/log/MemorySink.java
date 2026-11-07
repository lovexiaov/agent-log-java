package cn.lovexiaov.agent.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory sink — convenient for tests and inspections.
 */
public final class MemorySink implements LogSink {

    private final List<String> lines = new ArrayList<>();

    @Override
    public synchronized void write(String jsonLine) {
        lines.add(jsonLine);
    }

    /** All lines written so far, in order. */
    public synchronized List<String> lines() {
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    /** Number of lines written so far. */
    public synchronized int size() {
        return lines.size();
    }

    public synchronized void clear() {
        lines.clear();
    }
}
