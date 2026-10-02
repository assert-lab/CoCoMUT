package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual Gradle projects and compilation tasks, shared by build and source discovery. */
public record GradleBuildPlan(Map<String, String> projects,
                              Map<String, String> skippedProjects,
                              List<String> compilationTasks,
                              List<String> unavailableSourceSets) {
    public GradleBuildPlan {
        projects = projects == null ? Map.of() : Map.copyOf(projects);
        skippedProjects = skippedProjects == null ? Map.of() : Map.copyOf(skippedProjects);
        compilationTasks = compilationTasks == null ? List.of() : List.copyOf(compilationTasks);
        unavailableSourceSets = unavailableSourceSets == null ? List.of() : List.copyOf(unavailableSourceSets);
    }

    public static GradleBuildPlan empty() {
        return new GradleBuildPlan(Map.of(), Map.of(), List.of(), List.of());
    }

    public boolean partial() {
        return !skippedProjects.isEmpty() || !unavailableSourceSets.isEmpty();
    }

    public boolean includes(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try { normalized = normalized.toRealPath(); } catch (IOException ignored) { }
        Path absolute = normalized;
        // The nearest actual project owns the path, including JVM children of a skipped parent.
        String owner = projects.entrySet().stream()
                .filter(entry -> absolute.startsWith(Path.of(entry.getValue())))
                .max(java.util.Comparator.comparingInt(entry -> Path.of(entry.getValue()).getNameCount()))
                .map(Map.Entry::getKey).orElse(null);
        return owner == null || !skippedProjects.containsKey(owner);
    }

    /** Missing SDKs only exclude actual child project descriptors; root preflight stays explicit. */
    public static String selectionScript(Path root) throws IOException {
        Path canonicalRoot = root.toRealPath();
        Map<String, String> unavailable = new LinkedHashMap<>();
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put(AndroidSdkSupport.ALLOW_PROVISIONING_ENV, "false");
        List<Path> buildFiles = new java.util.ArrayList<>();
        Files.walkFileTree(canonicalRoot, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                return isBuildOutput(canonicalRoot.relativize(directory))
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.getFileName().toString().matches("build\\.gradle(?:\\.kts)?")) {
                    buildFiles.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        for (Path file : buildFiles.stream().sorted().toList()) {
            Path module = file.getParent().toAbsolutePath().normalize();
            if (module.equals(canonicalRoot) || !AndroidSdkSupport.isAndroidProject(module)) continue;
            AndroidSdkSupport.Preparation preparation = AndroidSdkSupport.prepare(module, env);
            boolean sdkUnset = env.getOrDefault("ANDROID_SDK_ROOT", "").isBlank()
                    && env.getOrDefault("ANDROID_HOME", "").isBlank();
            if (!preparation.succeeded() || sdkUnset) {
                unavailable.put(module.toString(), sdkUnset ? "Android SDK root is unset" : preparation.diagnostic());
            }
        }
        String json = new ObjectMapper().writeValueAsString(unavailable);
        return """
                def cocomutUnavailable = new groovy.json.JsonSlurper().parseText('%s')
                gradle.ext.cocomutSkippedProjects = [:]
                gradle.settingsEvaluated { settings ->
                    def visit
                    visit = { descriptor ->
                        def reason = cocomutUnavailable[descriptor.projectDir.canonicalPath]
                        if (descriptor.path != ':' && reason != null) {
                            // Use an absent build file for this descriptor only. Never edit project files.
                            descriptor.buildFileName = '.cocomut-skipped-android-%s.gradle'
                            gradle.ext.cocomutSkippedProjects[descriptor.path] = reason
                        }
                        descriptor.children.each { visit(it) }
                    }
                    visit(settings.rootProject)
                }
                """.formatted(groovy(json), java.util.UUID.randomUUID());
    }

    private static boolean isBuildOutput(Path relative) {
        for (Path segment : relative) {
            if (List.of(".git", ".gradle", "build", "target").contains(segment.toString())) return true;
        }
        return false;
    }

    private static String groovy(String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    public static Invocation compileInvocation(Path root, boolean tests) throws IOException {
        Path result = Files.createTempFile("cocomut-gradle-plan-", ".json");
        Path script = Files.createTempFile("cocomut-gradle-compile-", ".gradle");
        String body = """
                gradle.taskGraph.whenReady { graph ->
                    graph.allTasks.findAll { it instanceof org.gradle.api.tasks.testing.Test }.each { it.enabled = false }
                }
                gradle.projectsEvaluated {
                    def selectedTasks = []
                    def projects = [:]
                    def unavailable = []
                    rootProject.allprojects.each { p ->
                        projects[p.path] = p.projectDir.canonicalPath
                        if (!gradle.ext.cocomutSkippedProjects.containsKey(p.path)) {
                            def mainTask = p.tasks.findByName('classes') ?: p.tasks.findByName('compileJava') ?: p.tasks.findByName('assemble')
                            def testTask = p.tasks.findByName('testClasses') ?: p.tasks.findByName('compileTestJava')
                            if (mainTask != null) selectedTasks.add(mainTask)
                            if (%s && testTask != null) selectedTasks.add(testTask)
                            if (%s && mainTask != null && testTask == null) {
                                def javaSourceSets = p.extensions.findByName('sourceSets') ?: (p.hasProperty('sourceSets') ? p.sourceSets : null)
                                // Exempt only an actionless lifecycle task with no local compilation or Java inputs.
                                def lifecycleOnly = (mainTask.name == 'assemble' && mainTask.actions.isEmpty()
                                        && p.tasks.withType(org.gradle.api.tasks.compile.AbstractCompile).isEmpty()
                                        && javaSourceSets == null)
                                if (lifecycleOnly) {
                                    def ownJava = p.fileTree(p.projectDir) {
                                        include '**/*.java'
                                        exclude '**/.git/**', '**/.gradle/**', '**/build/**', '**/target/**'
                                    }
                                    def projectDir = p.projectDir.canonicalFile.toPath()
                                    rootProject.allprojects.each { child ->
                                        def childDir = child.projectDir.canonicalFile.toPath()
                                        if (child != p && childDir != projectDir && childDir.startsWith(projectDir)) {
                                            ownJava.exclude(projectDir.relativize(childDir).toString().replace(File.separatorChar, '/' as char) + '/**')
                                        }
                                    }
                                    lifecycleOnly = ownJava.isEmpty()
                                }
                                if (!lifecycleOnly) unavailable.add(p.path + ':test')
                            }
                        }
                    }
                    def plan = [projects: projects, skippedProjects: gradle.ext.cocomutSkippedProjects,
                                compilationTasks: selectedTasks.collect { it.path }.unique(),
                                unavailableSourceSets: unavailable]
                    new File('%s').text = groovy.json.JsonOutput.toJson(plan)
                    rootProject.tasks.create('cocomutCompileSelected') {
                        dependsOn selectedTasks
                        doLast {
                            if (selectedTasks.isEmpty()) {
                                if (!gradle.ext.cocomutSkippedProjects.isEmpty()) throw new GradleException('CoCoMUT: Android SDK unavailable for all compilable projects')
                                throw new GradleException('CoCoMUT: no compatible compilation task is available')
                            }
                        }
                    }
                }
                """.formatted(tests, tests, groovy(result.toString()));
        Files.writeString(script, selectionScript(root) + body);
        return new Invocation(script, result);
    }

    public record Invocation(Path script, Path result) implements AutoCloseable {
        public GradleBuildPlan read() throws IOException {
            if (Files.size(result) == 0) return empty();
            return new ObjectMapper().readValue(result.toFile(), GradleBuildPlan.class);
        }
        @Override public void close() throws IOException {
            Files.deleteIfExists(script);
            Files.deleteIfExists(result);
        }
    }
}
