package org.assertlab.cocomut;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Category(FastTests.class)
public class ContextRequestTest {

    @Test
    public void builderConvenienceMethodsMirrorCliSelectionOptions() {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(Path.of("."))
                .entryPoints()
                .sourceSet("main")
                .packageName("demo.api")
                .typeName("PublicApi")
                .className("demo.api.PublicApi")
                .methodName("parse")
                .visibility("public")
                .includePathGlob("src/main/java/**/*.java")
                .excludePathGlob("**/generated/**")
                .methodUri("src/main/java/demo/api/PublicApi.java#demo.api.PublicApi.parse():void")
                .classUri("src/main/java/demo/api/PublicApi.java#demo.api.PublicApi")
                .packageUri("src/main/java/demo/api/package-info.java#demo.api")
                .build();

        assertEquals(ContextRequest.Scope.ENTRY_POINTS, request.scope());
        assertEquals(Set.of("main"), request.sourceSets());
        assertEquals(Set.of("demo.api"), request.packages());
        assertEquals(Set.of("PublicApi", "demo.api.PublicApi"), request.classes());
        assertEquals(Set.of("parse"), request.methods());
        assertEquals(Set.of("public"), request.visibilities());
        assertEquals(Set.of("src/main/java/**/*.java"), request.includePathGlobs());
        assertEquals(Set.of("**/generated/**"), request.excludePathGlobs());
        assertTrue(request.targets().contains(SymbolTarget.method(
                "src/main/java/demo/api/PublicApi.java#demo.api.PublicApi.parse():void")));
        assertTrue(request.targets().contains(SymbolTarget.type(
                "src/main/java/demo/api/PublicApi.java#demo.api.PublicApi")));
        assertTrue(request.targets().contains(SymbolTarget.packageTarget(
                "src/main/java/demo/api/package-info.java#demo.api")));
    }

    @Test
    public void defaultRequestRequiresBytecodeAnalysis() {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(Path.of("."))
                .build();

        assertEquals(CallGraphGenerator.Algorithm.RTA, request.callGraphAlgorithm());
        assertEquals(ContextRequest.SourceResolution.CLASSPATH, request.sourceResolution());
        assertTrue(request.attemptCompile());
    }

    @Test
    public void builderRejectsDisabledCallGraph() {
        try {
            ContextRequest.builder()
                    .projectRoot(Path.of("."))
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .build();
            fail("Expected disabled call graph to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bytecode analysis"));
        }
    }

    @Test
    public void builderRejectsNoClasspathExtraction() {
        try {
            ContextRequest.builder()
                    .projectRoot(Path.of("."))
                    .sourceResolution(ContextRequest.SourceResolution.NOCLASSPATH)
                    .build();
            fail("Expected no-classpath extraction to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("classpath-aware"));
        }
    }

    @Test
    public void builderRejectsDisabledCompilation() {
        try {
            ContextRequest.builder()
                    .projectRoot(Path.of("."))
                    .attemptCompile(false)
                    .build();
            fail("Expected disabled compilation to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bytecode"));
        }
    }
}
