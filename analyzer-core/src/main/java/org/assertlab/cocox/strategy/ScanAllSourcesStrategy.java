package org.assertlab.cocox.strategy;

import org.assertlab.cocox.MethodIdentifier;
import org.assertlab.cocox.MethodInfo;
import org.assertlab.cocox.ProjectMetadata;

import java.io.IOException;
import java.util.List;

/**
 * Method source strategy for full-project scans.
 *
 * Walks every {@code .java} file under the project source root and extracts
 * all methods via {@link MethodIdentifier}. This is the default strategy for
 * Java projects.
 *
 * <p>For large projects (commons-lang3: 8 000+ methods), this produces a
 * comprehensive but unfiltered method list. Downstream phases handle all of
 * them unless a sampling limit is configured on {@link MethodIdentifier}.
 *
 * <p>Selected automatically by {@link MethodSourceStrategy#detect(java.nio.file.Path)}.
 */
public class ScanAllSourcesStrategy implements MethodSourceStrategy {

    @Override
    public String name() {
        return "SCAN_ALL";
    }

    @Override
    public List<MethodInfo> loadMethods(ProjectMetadata meta) throws IOException {
        MethodIdentifier identifier = new MethodIdentifier(meta);
        return identifier.identify();
    }
}
