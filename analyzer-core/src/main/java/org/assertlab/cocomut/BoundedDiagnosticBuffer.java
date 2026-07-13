package org.assertlab.cocomut;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Keeps a bounded diagnostic prefix and rolling tail without retaining the full stream. */
public final class BoundedDiagnosticBuffer {
    private final int prefixLimit;
    private final byte[] tail;
    private final ByteArrayOutputStream prefix;
    private int tailStart;
    private int tailSize;
    private long totalBytes;

    public BoundedDiagnosticBuffer(int prefixLimit, int tailLimit) {
        if (prefixLimit < 0 || tailLimit <= 0) {
            throw new IllegalArgumentException("Diagnostic limits must be non-negative with a positive tail");
        }
        this.prefixLimit = prefixLimit;
        this.tail = new byte[tailLimit];
        this.prefix = new ByteArrayOutputStream(Math.min(prefixLimit, 8192));
    }

    public synchronized void append(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0) return;
        int prefixRemaining = prefixLimit - prefix.size();
        if (prefixRemaining > 0) {
            prefix.write(bytes, offset, Math.min(prefixRemaining, length));
        }
        for (int i = 0; i < length; i++) {
            if (tailSize < tail.length) {
                tail[(tailStart + tailSize) % tail.length] = bytes[offset + i];
                tailSize++;
            } else {
                tail[tailStart] = bytes[offset + i];
                tailStart = (tailStart + 1) % tail.length;
            }
        }
        totalBytes += length;
    }

    public void appendLine(String line) {
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        append(bytes, 0, bytes.length);
    }

    public synchronized String transcript() {
        String head = prefix.toString(StandardCharsets.UTF_8);
        if (totalBytes <= prefixLimit) return head;
        return head + "\n[CoCoMUT diagnostic truncated; final bytes follow]\n" + tailText();
    }

    public synchronized String tailText() {
        byte[] ordered = new byte[tailSize];
        for (int i = 0; i < tailSize; i++) {
            ordered[i] = tail[(tailStart + i) % tail.length];
        }
        return new String(ordered, StandardCharsets.UTF_8);
    }
}
