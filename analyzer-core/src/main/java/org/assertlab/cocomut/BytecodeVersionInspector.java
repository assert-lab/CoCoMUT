package org.assertlab.cocomut;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads class-file headers before SootUp so unsupported bytecode is explicit. */
final class BytecodeVersionInspector {
    static final int MAX_SUPPORTED_MAJOR = 70; // Java 26, supported by ASM 9.9.1.

    private BytecodeVersionInspector() {}

    static int maximumMajor(Collection<Path> locations) {
        int max = -1;
        for (Path location : locations) {
            try {
                if (Files.isDirectory(location)) {
                    try (var files = Files.walk(location)) {
                        for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                            max = Math.max(max, readMajor(Files.newInputStream(file)));
                        }
                    }
                } else if (Files.isRegularFile(location) && location.toString().endsWith(".jar")) {
                    try (ZipFile zip = new ZipFile(location.toFile())) {
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                                max = Math.max(max, readMajor(zip.getInputStream(entry)));
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // Build diagnostics retain unreadable artifact details elsewhere.
            }
        }
        return max;
    }

    private static int readMajor(InputStream input) throws IOException {
        try (DataInputStream data = new DataInputStream(input)) {
            if (data.readInt() != 0xCAFEBABE) return -1;
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}
