package org.assertlab.cocomut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Shared test resources: small Maven projects compiled on demand so
 * {@link Orchestrator} integration tests do not scan the whole workspace.
 */
public final class TestFixtures {

    private static final Object LOCK = new Object();
    private static volatile Path cachedRoot;
    private static volatile boolean compiled;

    private TestFixtures() {
    }

    /**
     * Absolute path to {@code src/test/resources/fixtures/minimal-maven-project}
     * (resolved from the JVM working directory, expected to be the module root).
     */
    public static Path minimalMavenProjectRoot() {
        if (cachedRoot != null) {
            return cachedRoot;
        }
        synchronized (LOCK) {
            if (cachedRoot != null) {
                return cachedRoot;
            }
            Path root = Paths.get("src/test/resources/fixtures/minimal-maven-project")
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("Missing fixture directory: " + root);
            }
            cachedRoot = root;
            return root;
        }
    }

    /**
     * Ensures the minimal fixture has {@code target/classes} with at least one
     * {@code .class} file (runs {@code mvn clean compile -q} once per JVM if needed).
     */
    public static void ensureMinimalMavenProjectCompiled() throws Exception {
        synchronized (LOCK) {
            if (compiled) {
                return;
            }
            Path root = minimalMavenProjectRoot();
            Path classes = root.resolve("target/classes");
            if (Files.isDirectory(classes)) {
                try (var stream = Files.walk(classes)) {
                    if (stream.anyMatch(p -> p.toString().endsWith(".class"))) {
                        compiled = true;
                        return;
                    }
                }
            }
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            String mvn = win ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(mvn, "clean", "compile", "-q");
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalStateException("Maven compile timed out for minimal fixture");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException(
                        "Maven compile failed for minimal fixture (exit=" + p.exitValue() + ")");
            }
            compiled = true;
        }
    }
}
