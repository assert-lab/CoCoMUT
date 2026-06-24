package org.assertlab.cocomut;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        assertEquals(ContextRequest.BuildPolicy.DENY_BUILD, request.buildPolicy());
    }

    @Test
    public void buildPolicyRequiresExplicitBuildPermission() {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(Path.of("."))
                .allowUnsandboxedBuild()
                .build();

        assertEquals(ContextRequest.BuildPolicy.ALLOW_UNSANDBOXED_BUILD, request.buildPolicy());
        assertTrue(!request.skipBuild());
    }

    @Test
    public void builderAcceptsChaBytecodeAnalysis() {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(Path.of("."))
                .callGraphAlgorithm(CallGraphGenerator.Algorithm.CHA)
                .build();

        assertEquals(CallGraphGenerator.Algorithm.CHA, request.callGraphAlgorithm());
    }

    @Test
    public void builderAcceptsExplicitArtifactInputs() {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(Path.of("."))
                .skipBuild(true)
                .classOutputDir(Path.of("target/classes"))
                .projectJar(Path.of("target/example.jar"))
                .dependencyJar(Path.of("lib/dependency.jar"))
                .classpathFile(Path.of("classpath.txt"))
                .build();

        assertTrue(request.skipBuild());
        assertTrue(request.classOutputDirs().contains(Path.of("target/classes").toAbsolutePath().normalize()));
        assertTrue(request.projectJars().contains(Path.of("target/example.jar").toAbsolutePath().normalize()));
        assertTrue(request.dependencyJars().contains(Path.of("lib/dependency.jar").toAbsolutePath().normalize()));
        assertTrue(request.classpathFiles().contains(Path.of("classpath.txt").toAbsolutePath().normalize()));
    }
}
