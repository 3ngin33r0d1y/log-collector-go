package log.dashboard.service;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a log file entry retrieved from S3.
 */
public class LogFileEntry {
    private final String key;
    private final String fileName;
    private final Instant lastModified;
    private final long size;
    private final int sequence;

    public LogFileEntry(String key, String fileName, Instant lastModified, long size, int sequence) {
        this.key = key;
        this.fileName = fileName;
        this.lastModified = lastModified;
        this.size = size;
        this.sequence = sequence;
    }

    // Getters
    public String getKey() {
        return key;
    }

    public String getFileName() {
        return fileName;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public long getSize() {
        return size;
    }

    public int getSequence() {
        return sequence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogFileEntry that = (LogFileEntry) o;
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        // Rewritten toString using String.format for robustness
        return String.format("LogFileEntry{key=	%s	, fileName=	%s	, lastModified=%s, size=%d, sequence=%d}",
                key, fileName, lastModified, size, sequence);
    }
}

