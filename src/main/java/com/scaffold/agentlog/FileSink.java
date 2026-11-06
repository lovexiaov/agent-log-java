package com.scaffold.agentlog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends JSONL to {@code <exportDirectory>/agent-log.jsonl}.
 */
public final class FileSink implements LogSink {

    static final String FILE_NAME = "agent-log.jsonl";

    private final Path filePath;

    public FileSink(String exportDirectory) {
        this(Path.of(exportDirectory));
    }

    public FileSink(Path exportDirectory) {
        try {
            Files.createDirectories(exportDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create export directory: " + exportDirectory, e);
        }
        this.filePath = exportDirectory.resolve(FILE_NAME);
    }

    @Override
    public synchronized void write(String jsonLine) {
        try (BufferedWriter w = Files.newBufferedWriter(filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            w.write(jsonLine);
            w.write('\n');
        } catch (IOException e) {
            throw new RuntimeException("Failed to write log line to " + filePath, e);
        }
    }

    /** Absolute path of the JSONL file. */
    public String exportPath() {
        return filePath.toAbsolutePath().toString();
    }
}
