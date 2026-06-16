package org.assertlab.context4docugen.strategy;

import org.assertlab.context4docugen.MethodIdentifier;
import org.assertlab.context4docugen.MethodInfo;
import org.assertlab.context4docugen.ProjectMetadata;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Method source strategy that processes only <em>public entry-point</em> methods.
 *
 * <p>Scans all sources via {@link MethodIdentifier}, then keeps only methods with
 * {@code public} visibility. This is the right default for documenting a library's
 * public API surface without the noise of private helpers — far smaller output than
 * {@link ScanAllSourcesStrategy} while still covering everything an external caller
 * can reach.
 *
 * <p>Not auto-selected by {@link MethodSourceStrategy#detect}; choose it explicitly:
 * <pre>{@code
 * AnalyzerFacade.analyze(projectPath, new EntryPointScanStrategy());
 * }</pre>
 *
 * <p>Optionally excludes {@code main(String[])} bootstrap methods, which are public
 * but rarely the documentation target of interest.
 */
public class EntryPointScanStrategy implements MethodSourceStrategy {

    private final boolean excludeMain;

    /** Default: include public methods, exclude {@code main}. */
    public EntryPointScanStrategy() {
        this(true);
    }

    /**
     * @param excludeMain when {@code true}, skips {@code public static void main(String[])}
     */
    public EntryPointScanStrategy(boolean excludeMain) {
        this.excludeMain = excludeMain;
    }

    @Override
    public String name() {
        return "ENTRY_POINTS";
    }

    @Override
    public List<MethodInfo> loadMethods(ProjectMetadata meta) throws IOException {
        MethodIdentifier identifier = new MethodIdentifier(meta);

        List<MethodInfo> all = identifier.identify();
        List<MethodInfo> entryPoints = all.stream()
                .filter(m -> "public".equals(m.getVisibility()))
                .filter(m -> !excludeMain || !isMain(m))
                .collect(Collectors.toList());

        System.out.printf("[EntryPointScanStrategy] %d public entry points (of %d total methods)%n",
                entryPoints.size(), all.size());
        return entryPoints;
    }

    /** {@code public static void main(String[] args)} detection. */
    private static boolean isMain(MethodInfo m) {
        return "main".equals(m.getMethodName()) && m.isStatic();
    }
}
