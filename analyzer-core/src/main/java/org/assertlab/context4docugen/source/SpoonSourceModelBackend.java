package org.assertlab.context4docugen.source;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoon-backed source model.
 *
 * <p>The backend uses no-classpath mode by default. That is a deliberate mining
 * choice: C4DG should extract source/Javadoc context from imperfect public
 * repositories and record unresolved type precision in provenance rather than
 * failing just because dependencies are unavailable.
 */
final class SpoonSourceModelBackend implements SourceModelBackend {
    private static final Pattern PARAM_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@param\\s+(\\S+)");
    private static final Pattern THROWS_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@(throws|exception)\\s+(\\S+)");
    private static final Pattern SINCE_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@since\\s+(.+)$");
    private static final Pattern SEE_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@see\\s+(.+)$");
    private static final Pattern INLINE_LINK = Pattern.compile("\\{@(?:link|linkplain)\\s+([^}\\s]+)");
    private static final String MAX_SOURCE_FILES_PROPERTY = "c4dg.maxSourceFiles";
    private static final String MAX_SOURCE_FILES_ENV = "C4DG_MAX_SOURCE_FILES";
    private final Map<String, ParsedProject> cache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "spoon";
    }

    @Override
    public String mode() {
        return "noclasspath";
    }

    @Override
    public List<SourceMethod> findMethods(ProjectModel project) throws IOException {
        return parsed(project).methods();
    }

    @Override
    public Optional<SourceMethod> findMethod(ProjectModel project, String methodUri) throws IOException {
        return Optional.ofNullable(parsed(project).methodsByUri().get(methodUri));
    }

    @Override
    public Optional<SourceContext> extractContext(ProjectModel project, String methodUri) throws IOException {
        ParsedProject parsed = parsed(project);
        CtExecutable<?> executable = parsed.executablesByUri().get(methodUri);
        SourceMethod method = parsed.methodsByUri().get(methodUri);
        if (executable == null || method == null) {
            return Optional.empty();
        }

        CtType<?> owner = executable.getParent(CtType.class);
        String methodBody = sourceSlice(executable);
        String javadoc = docComment(executable);
        String classJavadoc = owner != null ? docComment(owner) : "";
        String classHierarchy = owner != null ? classHierarchy(owner) : "";
        String hierarchyResolution = hierarchyResolution(owner);
        Map<String, String> classMethods = owner != null ? classMethods(owner) : Map.of();
        List<String> siblingMethods = classMethods.keySet().stream().sorted().toList();
        List<String> overloadGroup = siblingMethods.stream()
                .filter(sig -> sig.startsWith(method.methodName() + "("))
                .toList();

        return Optional.of(new SourceContext(
                method,
                methodBody,
                javadoc,
                classJavadoc,
                classHierarchy,
                hierarchyResolution,
                classMethods,
                fieldReads(executable),
                fieldWrites(executable),
                siblingMethods,
                overloadGroup,
                dynamicFeatures(executable),
                javadocMetadata(executable, javadoc),
                documentationMetrics(method, javadoc)));
    }

    private ParsedProject parsed(ProjectModel project) throws IOException {
        String key = cacheKey(project);
        ParsedProject cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        ParsedProject parsed = parse(project);
        cache.put(key, parsed);
        return parsed;
    }

    private static String cacheKey(ProjectModel project) {
        String limit = String.valueOf(maxSourceFiles());
        String roots = project.sourceRoots().stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .sorted()
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
        return project.projectPath().toAbsolutePath().normalize()
                + "::roots=" + roots
                + "::maxSourceFiles=" + limit;
    }

    private ParsedProject parse(ProjectModel project) throws IOException {
        List<SourceMethod> methods = new ArrayList<>();
        Map<String, SourceMethod> methodsByUri = new LinkedHashMap<>();
        Map<String, CtExecutable<?>> executablesByUri = new LinkedHashMap<>();

        List<CtExecutable<?>> executables = new ArrayList<>();
        for (CtModel model : parseModels(project)) {
            for (CtExecutable<?> executable : model.getElements(new TypeFilter<>(CtExecutable.class))) {
                if ((executable instanceof CtMethod<?> || executable instanceof CtConstructor<?>)
                        && executable.getPosition() != null
                        && executable.getPosition().isValidPosition()) {
                    executables.add(executable);
                }
            }
        }
        executables.sort(Comparator.comparingInt(e -> e.getPosition().getLine()));

        for (CtExecutable<?> executable : executables) {
            Optional<SourceMethod> method = toSourceMethod(project, executable);
            if (method.isPresent()) {
                SourceMethod sourceMethod = method.get();
                methods.add(sourceMethod);
                methodsByUri.put(sourceMethod.methodUri(), sourceMethod);
                executablesByUri.put(sourceMethod.methodUri(), executable);
            }
        }

        return new ParsedProject(methods, methodsByUri, executablesByUri);
    }

    private List<CtModel> parseModels(ProjectModel project) throws IOException {
        Integer maxSourceFiles = maxSourceFiles();
        if (maxSourceFiles != null) {
            return parseJavaFilesWithLimit(project.sourceRoots(), complianceLevel(project.javaVersion()), maxSourceFiles);
        }

        if (project.sourceRoots().isEmpty()) {
            return List.of(buildModel(List.of(), complianceLevel(project.javaVersion())));
        }

        try {
            return List.of(buildModel(project.sourceRoots(), complianceLevel(project.javaVersion())));
        } catch (RuntimeException combinedFailure) {
            List<CtModel> models = new ArrayList<>();
            for (Path root : project.sourceRoots()) {
                try {
                    models.add(buildModel(List.of(root), complianceLevel(project.javaVersion())));
                } catch (RuntimeException rootFailure) {
                    models.addAll(parseJavaFilesIndividually(root, complianceLevel(project.javaVersion())));
                }
            }
            if (models.isEmpty()) {
                throw combinedFailure;
            }
            return models;
        }
    }

    private List<CtModel> parseJavaFilesWithLimit(List<Path> roots, int complianceLevel, int maxSourceFiles)
            throws IOException {
        if (roots.isEmpty()) {
            return List.of(buildModel(List.of(), complianceLevel));
        }
        List<CtModel> models = new ArrayList<>();
        int parsedFiles = 0;
        for (Path root : roots) {
            if (parsedFiles >= maxSourceFiles) {
                break;
            }
            if (!Files.exists(root)) {
                continue;
            }
            try (var walk = Files.walk(root)) {
                for (Path file : walk
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    if (parsedFiles >= maxSourceFiles) {
                        break;
                    }
                    parsedFiles++;
                    try {
                        models.add(buildModel(List.of(file), complianceLevel));
                    } catch (RuntimeException ignored) {
                        // A few invalid/newer-syntax files should not drop a capped smoke run.
                    }
                }
            }
        }
        return models.isEmpty() ? List.of(buildModel(List.of(), complianceLevel)) : models;
    }

    private List<CtModel> parseJavaFilesIndividually(Path root, int complianceLevel) throws IOException {
        List<CtModel> models = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                try {
                    models.add(buildModel(List.of(file), complianceLevel));
                } catch (RuntimeException ignored) {
                    // A few invalid/newer-syntax files should not drop an entire repository.
                }
            }
        }
        return models;
    }

    private CtModel buildModel(List<Path> inputs, int complianceLevel) {
        try {
            return launcher(inputs, complianceLevel).buildModel();
        } catch (RuntimeException firstFailure) {
            if (complianceLevel == 17) {
                throw firstFailure;
            }
            return launcher(inputs, 17).buildModel();
        }
    }

    private Launcher launcher(List<Path> inputs, int complianceLevel) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(true);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setShouldCompile(false);
        launcher.getEnvironment().setComplianceLevel(complianceLevel);

        if (inputs.isEmpty()) {
            launcher.addInputResource(new VirtualFile("", "NoSource.java"));
        } else {
            for (Path input : inputs) {
                launcher.addInputResource(input.toString());
            }
        }
        return launcher;
    }

    private Optional<SourceMethod> toSourceMethod(ProjectModel project, CtExecutable<?> executable) {
        CtType<?> owner = executable.getParent(CtType.class);
        SourcePosition position = executable.getPosition();
        if (owner == null || position == null || !position.isValidPosition()) {
            return Optional.empty();
        }
        Path sourceFile = position.getFile().toPath().toAbsolutePath().normalize();
        String className = owner.getQualifiedName();
        String methodName = methodName(executable, owner);
        List<SourceParameter> parameters = parameters(executable);
        String signature = methodName + "(" + parameterSignature(parameters) + ")";
        String uri = methodUri(project.projectPath(), sourceFile, className, signature);

        return Optional.of(new SourceMethod(
                uri,
                className,
                methodName,
                signature,
                sourceFile,
                position.getLine(),
                Math.max(0, position.getColumn()),
                visibility(executable),
                isStatic(executable),
                returnType(executable),
                parameters,
                annotations(executable),
                thrownExceptions(executable),
                sourceSet(project.projectPath(), sourceFile),
                executable instanceof CtConstructor<?>));
    }

    private static String methodName(CtExecutable<?> executable, CtType<?> owner) {
        if (executable instanceof CtConstructor<?>) {
            return owner.getSimpleName();
        }
        return executable.getSimpleName();
    }

    private static List<SourceParameter> parameters(CtExecutable<?> executable) {
        List<SourceParameter> parameters = new ArrayList<>();
        for (CtParameter<?> parameter : executable.getParameters()) {
            parameters.add(new SourceParameter(
                    parameter.getSimpleName(),
                    typeName(parameter.getType()),
                    modifiers(parameter),
                    annotations(parameter)));
        }
        return parameters;
    }

    private static String parameterSignature(List<SourceParameter> parameters) {
        return parameters.stream()
                .map(p -> (p.type() + " " + p.name()).trim())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String sourceSlice(CtElement element) {
        SourcePosition position = element.getPosition();
        if (position == null || !position.isValidPosition()) {
            return "";
        }
        try {
            String source = Files.readString(position.getFile().toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, position.getSourceStart());
            int end = Math.min(source.length(), position.getSourceEnd() + 1);
            if (end > start) {
                return source.substring(start, end);
            }
        } catch (Exception ignored) {
            // Source slices are optional context; missing text should not drop the method.
        }
        return "";
    }

    private static String docComment(CtElement element) {
        try {
            String doc = element.getDocComment();
            return doc != null ? doc.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String classHierarchy(CtType<?> type) {
        StringBuilder hierarchy = new StringBuilder(type.getQualifiedName());
        CtTypeReference<?> superclass = type.getSuperclass();
        if (superclass != null) {
            hierarchy.append(" extends ").append(typeName(superclass));
        }
        if (!type.getSuperInterfaces().isEmpty()) {
            hierarchy.append(" implements ");
            hierarchy.append(type.getSuperInterfaces().stream()
                    .map(SpoonSourceModelBackend::typeName)
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
        }
        return hierarchy.toString();
    }

    private static String hierarchyResolution(CtType<?> type) {
        if (type == null) {
            return "missing";
        }
        try {
            boolean unresolvedSuperclass = type.getSuperclass() != null
                    && type.getSuperclass().getDeclaration() == null;
            boolean unresolvedInterface = type.getSuperInterfaces().stream()
                    .anyMatch(ref -> {
                        try {
                            return ref.getDeclaration() == null;
                        } catch (Exception e) {
                            return true;
                        }
                    });
            return unresolvedSuperclass || unresolvedInterface ? "partial" : "resolved";
        } catch (Exception e) {
            return "partial";
        }
    }

    private static Map<String, String> classMethods(CtType<?> type) {
        Map<String, String> methods = new LinkedHashMap<>();
        for (CtExecutable<?> executable : type.getTypeMembers().stream()
                .filter(CtExecutable.class::isInstance)
                .map(CtExecutable.class::cast)
                .toList()) {
            String name = executable instanceof CtConstructor<?> ? type.getSimpleName() : executable.getSimpleName();
            String signature = name + "(" + parameterSignature(parameters(executable)) + ")";
            methods.put(signature, signature);
        }
        return methods;
    }

    private static List<String> fieldReads(CtExecutable<?> executable) {
        return executable.getElements(new TypeFilter<>(CtFieldRead.class)).stream()
                .map(read -> read.getVariable() != null ? read.getVariable().getSimpleName() : "")
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> fieldWrites(CtExecutable<?> executable) {
        return executable.getElements(new TypeFilter<>(CtFieldWrite.class)).stream()
                .map(write -> write.getVariable() != null ? write.getVariable().getSimpleName() : "")
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> dynamicFeatures(CtExecutable<?> executable) {
        Set<String> features = new LinkedHashSet<>();
        String text = sourceSlice(executable);
        if (text.contains("Class.forName(") || text.contains(".getDeclaredMethod(")
                || text.contains(".getMethod(") || text.contains("Method.invoke(")
                || text.contains(".invoke(")) {
            features.add("reflection");
        }
        if (text.contains("Proxy.newProxyInstance(")) {
            features.add("proxy");
        }
        if (text.contains("ServiceLoader.load(")) {
            features.add("service_loader");
        }
        if (text.contains("native ")) {
            features.add("native_method");
        }
        for (String annotation : annotations(executable)) {
            String lower = annotation.toLowerCase(Locale.ROOT);
            if (lower.endsWith("autowired") || lower.endsWith("inject") || lower.endsWith("bean")
                    || lower.endsWith("component") || lower.endsWith("service")) {
                features.add("dependency_injection");
            }
        }
        for (CtInvocation<?> invocation : executable.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutableReference<?> ref = invocation.getExecutable();
            String qualified = ref != null ? ref.toString() : "";
            if (qualified.contains("Class.forName")) {
                features.add("reflection");
            }
            if (qualified.contains("ServiceLoader.load")) {
                features.add("service_loader");
            }
        }
        return List.copyOf(features);
    }

    private static Map<String, Object> documentationMetrics(SourceMethod method, String javadoc) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        List<String> paramTags = matches(PARAM_TAG, normalized, 1);
        List<String> throwsTags = matches(THROWS_TAG, normalized, 2);
        Set<String> parameterNames = new LinkedHashSet<>(method.parameters().stream()
                .map(SourceParameter::name)
                .toList());
        Set<String> documentedParams = new LinkedHashSet<>(paramTags);
        List<String> missingParams = parameterNames.stream()
                .filter(p -> !documentedParams.contains(p))
                .toList();

        metrics.put("has_summary", hasSummary(normalized));
        metrics.put("has_param_tags", !paramTags.isEmpty());
        metrics.put("missing_param_tags", missingParams);
        metrics.put("has_return_tag", normalized.contains("@return"));
        metrics.put("has_throws_tag", !throwsTags.isEmpty());
        metrics.put("mentions_null", normalized.toLowerCase(Locale.ROOT).contains("null"));
        metrics.put("mentions_examples", mentionsExample(normalized));
        metrics.put("uses_inheritdoc", normalized.contains("{@inheritDoc}") || normalized.contains("@inheritDoc"));
        metrics.put("has_since_tag", !matches(SINCE_TAG, normalized, 1).isEmpty());
        metrics.put("has_see_tag", !matches(SEE_TAG, normalized, 1).isEmpty());
        metrics.put("inline_link_count", matches(INLINE_LINK, normalized, 1).size());
        return metrics;
    }

    private static Map<String, Object> javadocMetadata(CtExecutable<?> executable, String javadoc) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        metadata.put("since", matches(SINCE_TAG, normalized, 1));
        metadata.put("see", matches(SEE_TAG, normalized, 1));
        metadata.put("inline_links", matches(INLINE_LINK, normalized, 1));
        metadata.put("uses_inheritdoc", normalized.contains("{@inheritDoc}") || normalized.contains("@inheritDoc"));
        metadata.put("deprecated", isDeprecated(executable, normalized));
        metadata.put("deprecation_text", deprecationText(normalized));
        metadata.put("inheritdoc_resolution", inheritdocResolution(executable, normalized));
        metadata.put("inherited_javadoc_candidates", inheritedJavadocCandidates(executable));
        return metadata;
    }

    private static boolean isDeprecated(CtExecutable<?> executable, String javadoc) {
        return annotations(executable).stream().anyMatch(a -> a.endsWith("Deprecated"))
                || javadoc.contains("@deprecated");
    }

    private static String deprecationText(String javadoc) {
        Matcher matcher = Pattern.compile("(?ms)^\\s*\\*?\\s*@deprecated\\s+(.+?)(?:\\R\\s*\\*?\\s*@|\\z)").matcher(javadoc);
        if (matcher.find()) {
            return matcher.group(1).replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    private static String inheritdocResolution(CtExecutable<?> executable, String javadoc) {
        if (!(javadoc.contains("{@inheritDoc}") || javadoc.contains("@inheritDoc"))) {
            return "not_used";
        }
        try {
            if (executable instanceof CtMethod<?> method && method.getTopDefinitions() != null
                    && !method.getTopDefinitions().isEmpty()) {
                return "resolved_candidate";
            }
        } catch (Exception ignored) {
            return "unresolved";
        }
        return "unresolved";
    }

    private static List<String> inheritedJavadocCandidates(CtExecutable<?> executable) {
        if (!(executable instanceof CtMethod<?> method)) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        try {
            for (CtMethod<?> definition : method.getTopDefinitions()) {
                String doc = docComment(definition);
                if (!doc.isBlank()) {
                    candidates.add(doc);
                }
                if (candidates.size() >= 3) {
                    break;
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return candidates;
    }

    private static Integer maxSourceFiles() {
        String configured = System.getProperty(MAX_SOURCE_FILES_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MAX_SOURCE_FILES_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> matches(Pattern pattern, String text, int group) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
        return values;
    }

    private static boolean hasSummary(String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return false;
        }
        String noTags = javadoc.replaceAll("(?m)^\\s*\\*?\\s*@.*$", "").strip();
        return !noTags.isBlank();
    }

    private static boolean mentionsExample(String javadoc) {
        String lower = javadoc.toLowerCase(Locale.ROOT);
        return lower.contains("<pre") || lower.contains("{@snippet") || lower.contains("example");
    }

    private static String visibility(CtExecutable<?> executable) {
        if (!(executable instanceof CtModifiable modifiable)) {
            return "package-private";
        }
        if (modifiable.isPublic()) {
            return "public";
        }
        if (modifiable.isProtected()) {
            return "protected";
        }
        if (modifiable.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private static boolean isStatic(CtExecutable<?> executable) {
        return executable instanceof CtModifiable modifiable && modifiable.isStatic();
    }

    private static String returnType(CtExecutable<?> executable) {
        if (executable instanceof CtMethod<?> method) {
            return typeName(method.getType());
        }
        return "";
    }

    private static List<String> annotations(CtElement element) {
        return element.getAnnotations().stream()
                .map(SpoonSourceModelBackend::annotationName)
                .filter(s -> !s.isBlank())
                .sorted()
                .toList();
    }

    private static String annotationName(CtAnnotation<?> annotation) {
        try {
            return typeName(annotation.getAnnotationType());
        } catch (Exception e) {
            return annotation.toString();
        }
    }

    private static List<String> modifiers(CtModifiable modifiable) {
        return modifiable.getModifiers().stream()
                .map(modifier -> modifier.toString().toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }

    private static List<String> thrownExceptions(CtExecutable<?> executable) {
        return executable.getThrownTypes().stream()
                .map(SpoonSourceModelBackend::typeName)
                .sorted()
                .toList();
    }

    private static String typeName(CtTypeReference<?> ref) {
        if (ref == null) {
            return "";
        }
        try {
            String qualified = ref.getQualifiedName();
            return qualified != null && !qualified.isBlank() ? qualified : ref.getSimpleName();
        } catch (Exception e) {
            return ref.toString();
        }
    }

    private static int complianceLevel(String javaVersion) {
        if (javaVersion == null || javaVersion.isBlank() || "unknown".equals(javaVersion)) {
            return 17;
        }
        String numeric = javaVersion.replace("1.", "").replaceAll("[^0-9].*$", "");
        try {
            int level = Integer.parseInt(numeric);
            return Math.max(8, Math.min(25, level));
        } catch (NumberFormatException e) {
            return 17;
        }
    }

    private static String methodUri(Path projectRoot, Path sourceFile, String className, String signature) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedFile = sourceFile.toAbsolutePath().normalize();
        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        return relative + "#" + className + "." + signature.replaceAll("\\s+", " ").trim();
    }

    private static String sourceSet(Path projectRoot, Path sourceFile) {
        String relative = projectRoot.toAbsolutePath().normalize()
                .relativize(sourceFile.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.contains("/src/test/") || lower.startsWith("src/test/")) {
            return "test";
        }
        if (lower.contains("/src/it/") || lower.contains("/src/itest/")) {
            return "integration_test";
        }
        if (lower.contains("generated") || lower.contains("target/generated-sources")
                || lower.contains("build/generated/")) {
            return "generated";
        }
        if (lower.contains("example") || lower.contains("sample") || lower.contains("demo")) {
            return "example";
        }
        if (lower.contains("/src/main/") || lower.startsWith("src/main/")) {
            return "main";
        }
        return "unknown";
    }

    private record ParsedProject(
            List<SourceMethod> methods,
            Map<String, SourceMethod> methodsByUri,
            Map<String, CtExecutable<?>> executablesByUri) {
        private ParsedProject {
            methods = List.copyOf(methods);
            methodsByUri = Map.copyOf(methodsByUri);
            executablesByUri = Map.copyOf(executablesByUri);
        }
    }
}
