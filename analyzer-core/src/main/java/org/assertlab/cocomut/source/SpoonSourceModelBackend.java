package org.assertlab.cocomut.source;

import org.assertlab.cocomut.ResourceFailures;

import spoon.Launcher;
import spoon.javadoc.api.StandardJavadocTagType;
import spoon.javadoc.api.elements.JavadocBlockTag;
import spoon.javadoc.api.elements.JavadocElement;
import spoon.javadoc.api.elements.JavadocInlineTag;
import spoon.javadoc.api.elements.JavadocReference;
import spoon.javadoc.api.elements.JavadocText;
import spoon.javadoc.api.parsing.JavadocParser;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtFormalTypeDeclarer;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoon-backed source model.
 *
 * <p>The product pipeline uses classpath-aware extraction against compiled
 * projects. It may recover individual source files after whole-model parse
 * failures, but it does not turn source-only extraction into a successful
 * product mode.
 */
final class SpoonSourceModelBackend implements SourceModelBackend {
    private static final Pattern ANCHOR_HREF = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JAVADOC_FILE_REFERENCE = Pattern.compile(
            "(?:\\{@docRoot\\}/)?(?:[\\w.$-]+/)*(?:doc-files/)?[\\w.$-]+\\.(?:png|svg|gif|jpg|jpeg|html|htm|txt|java)",
            Pattern.CASE_INSENSITIVE);
    private static final String MAX_SOURCE_FILES_PROPERTY = "cocomut.maxSourceFiles";
    private static final String MAX_SOURCE_FILES_ENV = "COCOMUT_MAX_SOURCE_FILES";
    private static final int MAX_SAME_TYPE_METHOD_CONTEXT = 500;
    private static final int MAX_OVERLOAD_CONTEXT = 200;
    private static final List<String> COMMON_JDK_PACKAGES = List.of(
            "java.util",
            "java.util.regex",
            "java.util.stream",
            "java.time",
            "java.text",
            "java.io");

    @Override
    public String name() {
        return "spoon";
    }

    @Override
    public String mode() {
        return "classpath";
    }

    @Override
    public SourceAnalysisSession open(ProjectModel project) throws IOException {
        return new SpoonSession(parse(project));
    }

    private Optional<SourceContext> extractContext(ParsedProject parsed, String methodUri) {
        CtExecutable<?> executable = parsed.executablesByUri().get(methodUri);
        SourceMethod method = parsed.methodsByUri().get(methodUri);
        if (executable == null || method == null) {
            return Optional.empty();
        }

        // The declaration and source text are mandatory. Resolve optional evidence
        // independently so a missing related type cannot discard a selected row.
        CtType<?> owner = executable.getParent(CtType.class);
        String methodBody = sourceSlice(executable);
        List<EnrichmentDiagnostic> diagnostics = new ArrayList<>();
        CommentAttempt parsedComment = enrich("parsed_javadoc", diagnostics, () -> {
            String text = executable.getDocComment();
            return new CommentAttempt(text != null ? text.trim() : "", false);
        }, new CommentAttempt("", true));
        RawCommentAttempt rawComment = rawDocCommentAttempt(parsed, executable);
        String parsedJavadoc = parsedComment.text();
        String rawJavadoc = rawComment.text().orElse(parsedJavadoc);
        // Raw source preserves comments when Spoon cannot expose them, including
        // the explicit supertype target that Spoon drops from inheritDoc tags.
        String javadoc = parsedJavadoc.isBlank() || hasExplicitInheritDocTarget(rawJavadoc)
                ? rawJavadoc : parsedJavadoc;
        List<JavadocElement> elements = parseJavadocElements(executable, diagnostics);
        String typeJavadoc = enrich("type_javadoc", diagnostics,
                () -> {
                    String text = owner != null ? owner.getDocComment() : "";
                    return text != null ? text.trim() : "";
                }, "");
        String typeHierarchy = enrich("type_hierarchy", diagnostics,
                () -> owner != null ? typeHierarchy(owner) : "", "");
        String hierarchyResolution = diagnostics.stream().anyMatch(d -> d.component().equals("type_hierarchy"))
                ? "unavailable" : enrich("hierarchy_resolution", diagnostics, () -> {
                    if (owner == null) return "missing";
                    boolean unresolvedSuperclass = owner.getSuperclass() != null
                            && owner.getSuperclass().getDeclaration() == null;
                    boolean unresolvedInterface = owner.getSuperInterfaces().stream()
                            .anyMatch(ref -> ref.getDeclaration() == null);
                    return unresolvedSuperclass || unresolvedInterface ? "partial" : "resolved";
                }, "unavailable");
        TypeContext typeContext = enrich("type_context", diagnostics,
                () -> owner != null
                        ? typeContext(parsed, owner, method.methodName(), method.signature(), method.constructor())
                        : TypeContext.empty(), TypeContext.empty());
        List<String> reads = enrich("field_reads", diagnostics, () -> fieldReads(executable), List.of());
        List<String> writes = enrich("field_writes", diagnostics, () -> fieldWrites(executable), List.of());
        List<String> dynamic = enrich("dynamic_features", diagnostics, () -> dynamicFeatures(executable), List.of());
        boolean elementsUnavailable = diagnostics.stream().anyMatch(d -> d.component().equals("javadoc_elements"));
        if (elementsUnavailable) {
            EnrichmentDiagnostic cause = diagnostics.stream().filter(d -> d.component().equals("javadoc_elements"))
                    .findFirst().orElseThrow();
            diagnostics.add(new EnrichmentDiagnostic("javadoc_metadata", cause.exceptionClass(), cause.message()));
            diagnostics.add(new EnrichmentDiagnostic("documentation_metrics", cause.exceptionClass(), cause.message()));
        }
        Map<String, Object> metadata = elementsUnavailable ? unavailableEvidence() : enrich("javadoc_metadata", diagnostics,
                () -> javadocMetadata(parsed, owner, executable, method, elements, javadoc, rawJavadoc,
                        parsedComment, rawComment, diagnostics), unavailableEvidence());
        Map<String, Object> metrics = elementsUnavailable ? unavailableEvidence() : enrich("documentation_metrics", diagnostics,
                () -> documentationMetrics(method, elements, javadoc), unavailableEvidence());
        return Optional.of(new SourceContext(method, methodBody, javadoc, typeJavadoc,
                typeHierarchy, hierarchyResolution, typeContext.typeMethods(), reads, writes,
                typeContext.sameTypeMethods(), typeContext.overloadGroup(), dynamic,
                metadata, metrics, parsed.mode(), diagnostics,
                enrich("source_callees", diagnostics, () -> sourceCallees(parsed, executable), List.of())));
    }

    private static List<SourceCallee> sourceCallees(ParsedProject parsed, CtExecutable<?> focal) {
        Map<String, SourceCallee> declarations = new LinkedHashMap<>();
        for (spoon.reflect.code.CtAbstractInvocation<?> invocation : focal.getElements(
                new TypeFilter<>(spoon.reflect.code.CtAbstractInvocation.class))) {
            if (invocation.isImplicit() || !belongsToExecutable(invocation, focal)) continue;
            CtExecutableReference<?> reference = invocation.getExecutable();
            SourceCallee callee;
            try {
                callee = sourceCallee(parsed, reference);
            } catch (RuntimeException failure) {
                ResourceFailures.rethrowIfPresent(failure);
                callee = new SourceCallee("unresolved", "", "", "", "", invocation.toString(),
                        "unresolved", "source_resolution_failed:" + failure.getClass().getName());
            }
            // Deduplicate proven declarations only. Unresolved expressions do not
            // establish that two references denote the same declaration.
            String key = callee.targetUri().isBlank() ? "unresolved:" + declarations.size() : callee.targetUri();
            declarations.putIfAbsent(key, callee);
        }
        return List.copyOf(declarations.values());
    }

    private static boolean belongsToExecutable(CtElement element, CtExecutable<?> focal) {
        for (CtElement parent = element.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == focal) return true;
            // Include expressions in lambdas, but exclude nested class/method bodies.
            if (parent instanceof CtType<?> || parent instanceof CtMethod<?> || parent instanceof CtConstructor<?>) return false;
            if (!parent.isParentInitialized()) break;
        }
        return false;
    }

    private static SourceCallee sourceCallee(ParsedProject parsed, CtExecutableReference<?> reference) {
        String owner = reference.getDeclaringType() != null ? reference.getDeclaringType().getQualifiedName() : "";
        String name = reference.getSimpleName();
        String signature = reference.getSignature();
        CtExecutable<?> declaration = reference.getExecutableDeclaration();
        if (declaration != null) {
            CtType<?> type = declaration.getParent(CtType.class);
            if (type != null) {
                owner = type.getQualifiedName();
                name = methodName(declaration, type);
                signature = identitySignature(name, parameters(declaration),
                        erasedReturnType(declaration, returnType(declaration)));
                Optional<Path> file = sourceFile(declaration);
                if (file.isPresent()) {
                    String uri = methodUri(parsed.projectRoot(), file.get(), owner, signature);
                    if (parsed.methodsByUri().containsKey(uri)) {
                        return new SourceCallee("project_method", uri, uri, owner, name, signature, "resolved", "");
                    }
                } else if (declaration instanceof spoon.reflect.declaration.CtShadowable shadow && shadow.isShadow()) {
                    String kind = owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("jdk.")
                            ? "jdk_method" : "external_method";
                    return new SourceCallee(kind, "", "java:" + owner + "#" + signature,
                            owner, name, signature, "resolved_external", "");
                }
            }
        }
        return new SourceCallee("unresolved", "", "", owner, name, signature, "unresolved",
                declaration != null && declaration.isImplicit() ? "implicit_declaration" : "source_declaration_unavailable");
    }

    private static Map<String, Object> unavailableEvidence() {
        return Map.of("availability", "unavailable");
    }

    private static List<JavadocElement> parseJavadocElements(
            CtExecutable<?> executable, List<EnrichmentDiagnostic> diagnostics) {
        try {
            return enrich("javadoc_elements", diagnostics,
                    () -> JavadocParser.forElement(executable), List.of());
        } catch (AssertionError failure) {
            // Spoon uses explicit AssertionError for malformed tags even without -ea.
            // This is a parser failure; VM resource errors remain terminal.
            ResourceFailures.rethrowIfPresent(failure);
            diagnostics.add(EnrichmentDiagnostic.from("javadoc_elements", failure));
            return List.of();
        }
    }

    static <T> T enrich(String component, List<EnrichmentDiagnostic> diagnostics,
                        java.util.function.Supplier<T> operation, T unavailable) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            ResourceFailures.rethrowIfPresent(failure);
            diagnostics.add(EnrichmentDiagnostic.from(component, failure));
            return unavailable;
        }
    }

    private Optional<SourceContext> extractDeclarationContext(ParsedProject parsed, String methodUri) {
        CtExecutable<?> executable = parsed.executablesByUri().get(methodUri);
        SourceMethod method = parsed.methodsByUri().get(methodUri);
        if (executable == null || method == null) {
            return Optional.empty();
        }
        return Optional.of(new SourceContext(method, sourceSlice(executable),
                rawDocComment(parsed, executable).orElseGet(() -> docComment(executable)), "", "", "unavailable", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), unavailableEvidence(), unavailableEvidence(),
                parsed.mode(), List.of(), List.of()));
    }

    private ParsedProject parse(ProjectModel project) throws IOException {
        SourceBackends.recordParse();
        return buildParsedProject(project, parseModels(project));
    }

    private final class SpoonSession implements SourceAnalysisSession {
        private final ParsedProject parsed;

        private SpoonSession(ParsedProject parsed) {
            this.parsed = parsed;
        }

        @Override
        public List<SourceMethod> methods() {
            return parsed.methods();
        }

        @Override
        public Optional<SourceMethod> findMethod(String methodUri) {
            return Optional.ofNullable(parsed.methodsByUri().get(methodUri));
        }

        @Override
        public Optional<SourceContext> extractContext(String methodUri) {
            return SpoonSourceModelBackend.this.extractContext(parsed, methodUri);
        }

        @Override
        public Optional<SourceContext> extractDeclarationContext(String methodUri) {
            return SpoonSourceModelBackend.this.extractDeclarationContext(parsed, methodUri);
        }

        @Override
        public SourceParseStats parseStats() {
            return parsed.parseStats();
        }

        @Override
        public void close() throws IOException {
            parsed.close();
        }
    }

    private ParsedProject buildParsedProject(ProjectModel project, ParsedModels parsedModels) {
        List<SourceMethod> methods = new ArrayList<>();
        Map<String, SourceMethod> methodsByUri = new LinkedHashMap<>();
        Map<String, CtExecutable<?>> executablesByUri = new LinkedHashMap<>();
        Map<String, CtType<?>> typesByQualifiedName = new LinkedHashMap<>();
        Map<String, List<SourceMethod>> methodsByTypeName = new LinkedHashMap<>();
        Map<String, List<SourceField>> fieldsByTypeName = new LinkedHashMap<>();
        Map<Path, ImportContext> importsByFile = new LinkedHashMap<>();

        List<CtExecutable<?>> executables = new ArrayList<>();
        for (CtModel model : parsedModels.models()) {
            for (CtType<?> type : model.getElements(new TypeFilter<>(CtType.class))) {
                if (type instanceof CtTypeParameter) {
                    continue;
                }
                String qualifiedName = type.getQualifiedName();
                if (qualifiedName != null && !qualifiedName.isBlank()) {
                    sourceFile(type).ifPresent(path ->
                            importsByFile.computeIfAbsent(path, SpoonSourceModelBackend::parseImportContext));
                    typesByQualifiedName.putIfAbsent(qualifiedName, type);
                    for (CtField<?> field : type.getFields()) {
                        toSourceField(project, type, field)
                                .ifPresent(sourceField -> fieldsByTypeName
                                        .computeIfAbsent(sourceField.typeName(), ignored -> new ArrayList<>())
                                        .add(sourceField));
                    }
                }
            }
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
                if (methodsByUri.containsKey(sourceMethod.methodUri())) {
                    continue;
                }
                methods.add(sourceMethod);
                methodsByUri.put(sourceMethod.methodUri(), sourceMethod);
                executablesByUri.put(sourceMethod.methodUri(), executable);
                methodsByTypeName.computeIfAbsent(sourceMethod.typeName(), ignored -> new ArrayList<>())
                        .add(sourceMethod);
            }
        }

        return new ParsedProject(project.projectPath(), methods, methodsByUri, executablesByUri,
                typesByQualifiedName, methodsByTypeName, fieldsByTypeName,
                importsByFile, new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                projectClassLoader(project), parsedModels.mode(), parsedModels.stats());
    }

    private ParsedModels parseModels(ProjectModel project) throws IOException {
        List<SourceModelAttempt> attempts = new ArrayList<>();
        Integer maxSourceFiles = maxSourceFiles();
        ParsedModels parsed;
        try {
            parsed = maxSourceFiles != null
                    ? parseJavaFilesWithLimit(allSourceRoots(project),
                            complianceLevel(project.javaVersion()), maxSourceFiles, project, attempts)
                    : parseCtModels(project, attempts);
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            throw new SourceModelBuildException(failure, attempts);
        }
        parsed = reconcileSourceDeclarations(project, parsed, attempts, maxSourceFiles);
        SourceParseStats stats = parsed.stats();
        return new ParsedModels(parsed.models(), parsed.mode(),
                new SourceParseStats(stats.discovered(), stats.parsed(), stats.failedFiles(),
                        parsed.mode(), attempts, stats.recoveredFiles()));
    }

    private ParsedModels reconcileSourceDeclarations(ProjectModel project, ParsedModels parsed,
            List<SourceModelAttempt> attempts, Integer maxSourceFiles) throws IOException {
        int compliance = complianceLevel(project.javaVersion());
        List<Path> files = javaFiles(allSourceRoots(project), maxSourceFiles == null ? 0 : maxSourceFiles);
        List<CtModel> models = new ArrayList<>(parsed.models());
        List<String> modes = new ArrayList<>(List.of(parsed.mode()));
        Set<Path> failedFiles = new LinkedHashSet<>();
        List<Path> recoveredFiles = new ArrayList<>();
        var represented = SourceDeclarationAudit.executables(models);
        for (Path file : files) {
            Path normalized = file.toAbsolutePath().normalize();
            SourceDeclarationAudit.SourceFile source;
            try {
                source = SourceDeclarationAudit.read(file, compliance, Charset.defaultCharset());
            } catch (IOException failure) {
                // An unreadable compilation unit must not discard other models
                // or the attempt diagnostics already collected for this session.
                failedFiles.add(file);
                attempts.add(new SourceModelAttempt(List.of(diagnosticPath(project, file)), parsed.mode(),
                        compliance, compliance, 0, "failed", failure.getClass().getName(),
                        diagnosticMessage(project, failure), "declaration_audit", "source_read_failed"));
                continue;
            }
            int missing = SourceDeclarationAudit.missing(source, represented.getOrDefault(normalized, List.of()));
            if (!source.syntaxFailure().isEmpty()) {
                failedFiles.add(file);
                attempts.add(new SourceModelAttempt(List.of(diagnosticPath(project, file)), parsed.mode(),
                        compliance, compliance, 0, "failed", "", source.syntaxFailure(),
                        "declaration_audit", "source_syntax_error"));
            } else if (missing > 0) {
                attempts.add(new SourceModelAttempt(List.of(diagnosticPath(project, file)), parsed.mode(),
                        compliance, compliance, 0, "incomplete", "",
                        "Missing " + missing + " of " + source.declarations().size() + " source declarations",
                        "declaration_audit", "source_declarations_missing"));
                try {
                    ModelBuild recovered = buildModel(List.of(file), compliance, project, attempts);
                    var recoveredMethods = SourceDeclarationAudit.executables(List.of(recovered.model()));
                    int stillMissing = SourceDeclarationAudit.missing(source,
                            recoveredMethods.getOrDefault(normalized, List.of()));
                    if (stillMissing == 0) {
                        // Prefer the complete isolated model's declarations when
                        // the combined model contains only part of this file.
                        models.add(0, recovered.model());
                        modes.add(recovered.mode());
                        recoveredFiles.add(file);
                    } else {
                        failedFiles.add(file);
                        attempts.add(new SourceModelAttempt(List.of(diagnosticPath(project, file)), recovered.mode(),
                                compliance, compliance, 0, "failed", "",
                                "Isolated recovery still lacks " + stillMissing + " source declarations",
                                "declaration_audit", "source_declarations_missing"));
                    }
                } catch (RuntimeException | LinkageError | AssertionError failure) {
                    failedFiles.add(file);
                }
            } else if (parsed.stats().failedFiles().contains(file)) {
                failedFiles.add(file);
            }
        }
        String mode = mergedMode(modes) + (maxSourceFiles == null ? "" : "_limited");
        return new ParsedModels(models, mode,
                new SourceParseStats(files.size(), files.size() - failedFiles.size(), new ArrayList<>(failedFiles),
                        mode, List.of(), recoveredFiles));
    }

    private ParsedModels parseCtModels(ProjectModel project, List<SourceModelAttempt> attempts) throws IOException {
        List<Path> roots = allSourceRoots(project);
        List<Path> javaFiles = javaFiles(roots, 0);
        if (roots.isEmpty()) {
            ModelBuild built = buildModel(List.of(), complianceLevel(project.javaVersion()), project, attempts);
            return new ParsedModels(List.of(built.model()), built.mode(), SourceParseStats.empty());
        }

        try {
            ModelBuild built = buildModel(roots, complianceLevel(project.javaVersion()), project, attempts);
            return new ParsedModels(List.of(built.model()), built.mode(),
                    new SourceParseStats(javaFiles.size(), javaFiles.size(), List.of()));
        } catch (RuntimeException | LinkageError | AssertionError combinedFailure) {
            List<CtModel> models = new ArrayList<>();
            List<String> modes = new ArrayList<>();
            List<Path> failedFiles = new ArrayList<>();
            for (Path root : roots) {
                try {
                    ModelBuild built = buildModel(List.of(root), complianceLevel(project.javaVersion()), project, attempts);
                    models.add(built.model());
                    modes.add(built.mode());
                } catch (RuntimeException | LinkageError | AssertionError rootFailure) {
                    ParsedModels parsedRoot = parseJavaFilesIndividually(root, complianceLevel(project.javaVersion()), project, attempts);
                    models.addAll(parsedRoot.models());
                    modes.add(parsedRoot.mode());
                    failedFiles.addAll(parsedRoot.stats().failedFiles());
                }
            }
            if (models.isEmpty()) {
                throw combinedFailure;
            }
            return new ParsedModels(models, mergedMode(modes),
                    new SourceParseStats(javaFiles.size(), javaFiles.size() - failedFiles.size(), failedFiles));
        }
    }

    private ParsedModels parseJavaFilesWithLimit(List<Path> roots, int complianceLevel, int maxSourceFiles,
                                                  ProjectModel project, List<SourceModelAttempt> attempts)
            throws IOException {
        if (roots.isEmpty()) {
            ModelBuild built = buildModel(List.of(), complianceLevel, project, attempts);
            return new ParsedModels(List.of(built.model()), built.mode() + "_limited", SourceParseStats.empty());
        }
        List<CtModel> models = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        List<Path> files = javaFiles(roots, maxSourceFiles);
        List<Path> failedFiles = new ArrayList<>();
        for (Path file : files) {
            try {
                ModelBuild built = buildModel(List.of(file), complianceLevel, project, attempts);
                models.add(built.model());
                modes.add(built.mode());
            } catch (RuntimeException | LinkageError | AssertionError ignored) {
                failedFiles.add(file);
            }
        }
        if (models.isEmpty()) {
            ModelBuild built = buildModel(List.of(), complianceLevel, project, attempts);
            models = List.of(built.model());
            modes.add(built.mode());
        }
        return new ParsedModels(models, mergedMode(modes) + "_limited",
                new SourceParseStats(files.size(), files.size() - failedFiles.size(), failedFiles));
    }

    private static List<Path> allSourceRoots(ProjectModel project) {
        List<Path> roots = new ArrayList<>();
        roots.addAll(project.sourceRoots());
        roots.addAll(project.testSourceRoots());
        return roots.stream().distinct().toList();
    }

    private ParsedModels parseJavaFilesIndividually(Path root, int complianceLevel, ProjectModel project, List<SourceModelAttempt> attempts) throws IOException {
        List<CtModel> models = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        List<Path> files = javaFiles(List.of(root), 0);
        List<Path> failedFiles = new ArrayList<>();
        for (Path file : files) {
            try {
                ModelBuild built = buildModel(List.of(file), complianceLevel, project, attempts);
                models.add(built.model());
                modes.add(built.mode());
            } catch (RuntimeException | LinkageError | AssertionError ignored) {
                failedFiles.add(file);
            }
        }
        return new ParsedModels(models, mergedMode(modes), new SourceParseStats(files.size(),
                files.size() - failedFiles.size(), failedFiles));
    }

    private static List<Path> javaFiles(List<Path> roots, int limit) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    if (limit > 0 && files.size() >= limit) {
                        return List.copyOf(files);
                    }
                    files.add(file.toAbsolutePath().normalize());
                }
            }
        }
        return List.copyOf(files);
    }

    private ModelBuild buildModel(List<Path> inputs, int complianceLevel, ProjectModel project,
                                  List<SourceModelAttempt> attempts) {
        Throwable initialFailure = null;
        int[] levels = complianceLevel == 17 ? new int[] {17} : new int[] {complianceLevel, 17};
        for (boolean useClasspath : new boolean[] {true, false}) {
            for (int level : levels) {
                String mode = useClasspath ? "classpath" : "no_classpath";
                List<String> inputNames = inputs.stream().map(path -> diagnosticPath(project, path)).toList();
                int entries = useClasspath ? classpathEntries(project).size() : 0;
                try {
                    CtModel model = launcher(inputs, level, project, useClasspath).buildModel();
                    attempts.add(new SourceModelAttempt(inputNames, mode, complianceLevel, level,
                            entries, "success", "", ""));
                    return new ModelBuild(model, mode);
                } catch (RuntimeException | LinkageError | AssertionError failure) {
                    if (initialFailure == null) initialFailure = failure;
                    attempts.add(new SourceModelAttempt(inputNames, mode, complianceLevel, level,
                            entries, "failed", failure.getClass().getName(), diagnosticMessage(project, failure)));
                }
            }
        }
        if (initialFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        throw (Error) initialFailure;
    }

    private static String diagnosticPath(ProjectModel project, Path path) {
        Path root = project.projectPath().toAbsolutePath().normalize();
        Path input = path.toAbsolutePath().normalize();
        return (input.startsWith(root) ? root.relativize(input) : input).toString().replace('\\', '/');
    }

    private static String diagnosticMessage(ProjectModel project, Throwable failure) {
        String message = Objects.toString(failure.getMessage(), "");
        message = message.replace(project.projectPath().toAbsolutePath().toString(), "<project>")
                .replace(System.getProperty("user.home", "\u0000"), "<home>")
                .replaceAll("[\\p{Cntrl}]", " ");
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    static String mergedMode(List<String> modes) {
        boolean classpath = modes.stream().anyMatch(mode -> mode.startsWith("classpath") || mode.startsWith("mixed"));
        boolean noClasspath = modes.stream().anyMatch(mode -> mode.startsWith("no_classpath") || mode.startsWith("mixed"));
        if (classpath && noClasspath) {
            return "mixed";
        }
        return noClasspath ? "no_classpath" : "classpath";
    }

    private Launcher launcher(List<Path> inputs, int complianceLevel, ProjectModel project, boolean useClasspath) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(!useClasspath);
        launcher.getEnvironment().setCommentEnabled(true);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setShouldCompile(false);
        launcher.getEnvironment().setComplianceLevel(complianceLevel);
        launcher.getEnvironment().setEncoding(Charset.defaultCharset());
        List<String> classpath = useClasspath ? classpathEntries(project) : List.of();
        if (!classpath.isEmpty()) {
            launcher.getEnvironment().setSourceClasspath(classpath.toArray(String[]::new));
        }

        if (inputs.isEmpty()) {
            launcher.addInputResource(new VirtualFile("", "NoSource.java"));
        } else {
            for (Path input : inputs) {
                launcher.addInputResource(input.toString());
            }
        }
        return launcher;
    }

    private static List<String> classpathEntries(ProjectModel project) {
        if (project == null) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        project.classOutputDirs().forEach(path -> entries.add(path.toString()));
        project.projectArtifactJars().forEach(path -> entries.add(path.toString()));
        project.dependencyClasspath().forEach(path -> entries.add(path.toString()));
        return entries;
    }

    private static Optional<Path> sourceFile(CtElement element) {
        if (element == null || element.getPosition() == null || !element.getPosition().isValidPosition()) {
            return Optional.empty();
        }
        try {
            return Optional.of(element.getPosition().getFile().toPath().toAbsolutePath().normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static ImportContext parseImportContext(Path sourceFile) {
        Map<String, String> explicit = new LinkedHashMap<>();
        Set<String> wildcard = new LinkedHashSet<>();
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            return new ImportContext(explicit, wildcard);
        }
        try {
            Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;")
                    .matcher(Files.readString(sourceFile, Charset.defaultCharset()));
            while (matcher.find()) {
                String imported = matcher.group(2);
                if (imported.endsWith(".*")) {
                    wildcard.add(imported.substring(0, imported.length() - 2));
                } else {
                    String simple = imported.substring(imported.lastIndexOf('.') + 1);
                    explicit.putIfAbsent(simple, imported);
                }
            }
        } catch (Exception ignored) {
            // Import-aware Javadoc resolution is enrichment. Missing imports should
            // not prevent source extraction.
        }
        return new ImportContext(explicit, wildcard);
    }

    private Optional<SourceMethod> toSourceMethod(ProjectModel project, CtExecutable<?> executable) {
        CtType<?> owner = executable.getParent(CtType.class);
        SourcePosition position = executable.getPosition();
        if (owner == null || position == null || !position.isValidPosition()) {
            return Optional.empty();
        }
        Path sourceFile = position.getFile().toPath().toAbsolutePath().normalize();
        String typeName = owner.getQualifiedName();
        String methodName = methodName(executable, owner);
        List<SourceParameter> parameters = parameters(executable);
        String displaySignature = methodName + "(" + parameterSignature(parameters, false) + ")";
        String returnType = returnType(executable);
        String erasedReturnType = erasedReturnType(executable, returnType);
        String identitySignature = identitySignature(methodName, parameters, erasedReturnType);
        String uri = methodUri(project.projectPath(), sourceFile, typeName, identitySignature);

        return Optional.of(new SourceMethod(
                uri,
                typeName,
                methodName,
                displaySignature,
                sourceFile,
                position.getLine(),
                Math.max(0, position.getColumn()),
                visibility(executable),
                isStatic(executable),
                returnType,
                erasedReturnType,
                parameters,
                annotations(executable),
                methodModifiers(executable),
                thrownExceptions(executable),
                sourceSet(project, sourceFile),
                executable instanceof CtConstructor<?>));
    }

    private Optional<SourceField> toSourceField(ProjectModel project, CtType<?> owner, CtField<?> field) {
        SourcePosition position = field.getPosition();
        if (owner == null || position == null || !position.isValidPosition()) {
            return Optional.empty();
        }
        Path sourceFile = position.getFile().toPath().toAbsolutePath().normalize();
        String sourceType = typeName(field.getType());
        String erasedType = erasedType(field.getType(), sourceType);
        return Optional.of(new SourceField(
                fieldUri(project.projectPath(), sourceFile, owner.getQualifiedName(), field.getSimpleName(), erasedType),
                owner.getQualifiedName(),
                field.getSimpleName(),
                sourceType,
                erasedType,
                sourceFile,
                position.getLine(),
                modifiers(field),
                annotations(field),
                docComment(field),
                sourceSet(project, sourceFile)));
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
            String sourceType = typeName(parameter.getType());
            parameters.add(new SourceParameter(
                    parameter.getSimpleName(),
                    sourceType,
                    erasedType(parameter.getType(), sourceType),
                    modifiers(parameter),
                    annotations(parameter)));
        }
        return parameters;
    }

    private static String parameterSignature(List<SourceParameter> parameters, boolean erased) {
        return parameters.stream()
                .map(p -> ((erased ? p.erasedType() : p.type()) + " " + p.name()).trim())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String erasedParameterSignature(List<SourceParameter> parameters) {
        return parameters.stream()
                .map(SourceParameter::erasedType)
                .map(String::trim)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static String identitySignature(String methodName, List<SourceParameter> parameters,
                                            String erasedReturnType) {
        String returnType = erasedReturnType == null || erasedReturnType.isBlank() ? "void" : erasedReturnType;
        return methodName + "(" + erasedParameterSignature(parameters) + "):" + returnType;
    }

    private static String sourceSlice(CtElement element) {
        SourcePosition position = element.getPosition();
        if (position == null || !position.isValidPosition()) {
            return "";
        }
        try {
            String source = Files.readString(position.getFile().toPath(), Charset.defaultCharset());
            int start = Math.max(0, position.getSourceStart());
            int end = Math.min(source.length(), position.getSourceEnd() + 1);
            if (end > start) {
                return stripLeadingJavadoc(source.substring(start, end), element);
            }
        } catch (Exception ignored) {
            // Source slices are optional context; missing text should not drop the method.
        }
        return "";
    }

    private static String stripLeadingJavadoc(String source, CtElement element) {
        int first = 0;
        while (first < source.length() && Character.isWhitespace(source.charAt(first))) {
            first++;
        }
        if (!source.startsWith("/**", first)) {
            int declaration = declarationNameIndex(source, element);
            if (declaration < 0) {
                return source;
            }
            int javadocStart = source.lastIndexOf("/**", declaration);
            if (javadocStart < 0) {
                return source;
            }
            int javadocEnd = source.indexOf("*/", javadocStart + 3);
            if (javadocEnd < 0 || javadocEnd > declaration) {
                return source;
            }
            return source.substring(javadocEnd + 2).stripLeading();
        }
        int end = source.indexOf("*/", first + 3);
        if (end < 0) {
            return source;
        }
        return source.substring(end + 2).stripLeading();
    }

    private static int declarationNameIndex(String source, CtElement element) {
        if (!(element instanceof CtExecutable<?> executable)) {
            return -1;
        }
        CtType<?> owner = executable.getParent(CtType.class);
        if (owner == null) {
            return -1;
        }
        String name = methodName(executable, owner);
        Pattern declarationName = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(");
        Matcher matcher = declarationName.matcher(source);
        int result = -1;
        while (matcher.find()) {
            result = matcher.start();
        }
        if (result < 0) {
            return -1;
        }
        return result;
    }

    private static String docComment(CtElement element) {
        return docCommentAttempt(element).text();
    }

    private static CommentAttempt docCommentAttempt(CtElement element) {
        try {
            String doc = element.getDocComment();
            return new CommentAttempt(doc != null ? doc.trim() : "", false);
        } catch (Exception e) {
            ResourceFailures.rethrowIfPresent(e);
            return new CommentAttempt("", true);
        }
    }

    private static Optional<String> rawDocComment(ParsedProject parsed, CtElement element) {
        return rawDocCommentAttempt(parsed, element).text();
    }

    private static RawCommentAttempt rawDocCommentAttempt(ParsedProject parsed, CtElement element) {
        try {
            SourcePosition position = element.getPosition();
            if (position == null || !position.isValidPosition() || position.getFile() == null) {
                return new RawCommentAttempt(Optional.empty(), false);
            }
            Path sourceFile = position.getFile().toPath().toAbsolutePath().normalize();
            String source = parsed.sourceTextByFile().get(sourceFile);
            if (source == null) {
                source = Files.readString(sourceFile, Charset.defaultCharset());
                parsed.sourceTextByFile().put(sourceFile, source);
            }
            int start = Math.max(0, Math.min(position.getSourceStart(), source.length()));
            int open = source.lastIndexOf("/**", start);
            if (open < 0) {
                return new RawCommentAttempt(Optional.empty(), false);
            }
            int close = source.indexOf("*/", open);
            if (close < 0 || close > start) {
                return new RawCommentAttempt(Optional.empty(), false);
            }
            String between = source.substring(close + 2, start);
            if (!between.replaceAll("@\\w+(?:\\([^)]*\\))?", "").trim().isBlank()) {
                return new RawCommentAttempt(Optional.empty(), false);
            }
            return new RawCommentAttempt(
                    Optional.of(cleanRawJavadoc(source.substring(open, close + 2))), false);
        } catch (Exception ignored) {
            ResourceFailures.rethrowIfPresent(ignored);
            return new RawCommentAttempt(Optional.empty(), true);
        }
    }

    private static String cleanRawJavadoc(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String body = raw.replaceFirst("^\\s*/\\*\\*", "")
                .replaceFirst("\\*/\\s*$", "");
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\\R", -1)) {
            lines.add(line.replaceFirst("^\\s*\\* ?", ""));
        }
        return String.join("\n", lines).strip();
    }

    private static String typeHierarchy(CtType<?> type) {
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
                        } catch (Exception | StackOverflowError e) {
                            return true;
                        }
                    });
            return unresolvedSuperclass || unresolvedInterface ? "partial" : "resolved";
        } catch (Exception | StackOverflowError e) {
            return "partial";
        }
    }

    private static TypeContext typeContext(ParsedProject parsed, CtType<?> type, String methodName,
                                             String focalSignature, boolean constructor) {
        String key = type.getQualifiedName() + "#" + (constructor ? "constructor:" : "method:") + focalSignature;
        return parsed.typeContextsByTypeAndMethod().computeIfAbsent(key, ignored -> {
            Map<String, String> typeMethods = parsed.typeMethodsByType().computeIfAbsent(
                    type.getQualifiedName(), ignoredType -> typeMethods(type));
            List<String> allMethods = typeMethods.keySet().stream().sorted().toList();
            LinkedHashSet<String> overloads = new LinkedHashSet<>();
            if (!constructor) {
                if (typeMethods.containsKey(focalSignature)) {
                    overloads.add(focalSignature);
                }
                allMethods.stream()
                        .filter(signature -> signature.startsWith(methodName + "("))
                        .limit(MAX_OVERLOAD_CONTEXT)
                        .forEach(overloads::add);
            }
            List<String> overloadGroup = overloads.stream()
                    .limit(MAX_OVERLOAD_CONTEXT)
                    .sorted()
                    .toList();

            LinkedHashSet<String> selected = new LinkedHashSet<>(overloadGroup);
            for (String signature : allMethods) {
                if (selected.size() >= MAX_SAME_TYPE_METHOD_CONTEXT) {
                    break;
                }
                selected.add(signature);
            }
            List<String> sameTypeMethods = selected.stream().sorted().toList();
            return new TypeContext(typeMethods, sameTypeMethods, overloadGroup);
        });
    }

    private static Map<String, String> typeMethods(CtType<?> type) {
        Map<String, String> methods = new LinkedHashMap<>();
        for (CtMethod<?> executable : type.getTypeMembers().stream()
                .filter(CtMethod.class::isInstance)
                .map(CtMethod.class::cast)
                .toList()) {
            try {
                String name = executable.getSimpleName();
                String signature = name + "(" + parameterSignature(parameters(executable), false) + ")";
                methods.put(signature, signature);
            } catch (Exception | StackOverflowError ignored) {
                // Some generic declarations can recurse inside Spoon resolution.
                // Same-type method context is optional, so keep the focal method.
            }
        }
        // Freeze once per declaring type; Map.copyOf in downstream source
        // records reuses this immutable snapshot rather than copying entries.
        return Map.copyOf(methods);
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
            String qualified = "";
            try {
                qualified = ref != null ? ref.toString() : "";
            } catch (Exception | StackOverflowError ignored) {
                // Optional dynamic hint extraction should not fail the method.
            }
            if (qualified.contains("Class.forName")) {
                features.add("reflection");
            }
            if (qualified.contains("ServiceLoader.load")) {
                features.add("service_loader");
            }
        }
        return List.copyOf(features);
    }

    private static Map<String, Object> documentationMetrics(SourceMethod method, List<JavadocElement> elements,
                                                            String javadoc) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        Map<String, Object> structuredTags = structuredTags(elements, normalized);
        List<String> paramTags = structuredTagNames(structuredTags, "params");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> throwsTags = (List<Map<String, String>>) structuredTags.getOrDefault("throws", List.of());
        Set<String> parameterNames = new LinkedHashSet<>(method.parameters().stream()
                .map(SourceParameter::name)
                .toList());
        Set<String> documentedParams = new LinkedHashSet<>(paramTags);
        List<String> missingParams = parameterNames.stream()
                .filter(p -> !documentedParams.contains(p))
                .toList();

        metrics.put("parser", structuredTags.getOrDefault("parser", "cocomut-fallback"));
        metrics.put("parse_confidence", structuredTags.getOrDefault("parse_confidence", "low"));
        metrics.put("has_summary", hasSummary(normalized));
        metrics.put("has_param_tags", !paramTags.isEmpty());
        metrics.put("missing_param_tags", missingParams);
        metrics.put("has_return_tag", !structuredList(structuredTags, "return").isEmpty());
        metrics.put("has_throws_tag", !throwsTags.isEmpty());
        metrics.put("mentions_null", normalized.toLowerCase(Locale.ROOT).contains("null"));
        metrics.put("mentions_examples", mentionsExample(normalized));
        metrics.put("uses_inheritdoc", containsInheritDoc(normalized));
        metrics.put("has_since_tag", !structuredList(structuredTags, "since").isEmpty());
        metrics.put("has_see_tag", !rawBlockTagBodies(normalized, "see").isEmpty());
        metrics.put("inline_link_count", inlineLinkTargets(elements, normalized).size());
        return metrics;
    }

    private static Map<String, Object> javadocMetadata(ParsedProject parsed, CtType<?> owner,
                                                       CtExecutable<?> executable, SourceMethod method,
                                                       List<JavadocElement> elements,
                                                       String javadoc, String rawJavadoc,
                                                       CommentAttempt parsedComment,
                                                       RawCommentAttempt rawComment,
                                                       List<EnrichmentDiagnostic> diagnostics) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        List<Map<String, Object>> references = enrich("javadoc_references", diagnostics,
                () -> javadocReferences(parsed, owner, executable, elements, normalized), List.of());
        boolean usesInheritDoc = containsInheritDoc(
                rawJavadoc == null || rawJavadoc.isBlank() ? normalized : rawJavadoc);
        boolean preserveExplicitTarget = hasExplicitInheritDocTarget(rawJavadoc);
        List<JavadocElement> inheritanceElements = preserveExplicitTarget ? List.of() : elements;
        String inheritanceText = preserveExplicitTarget ? rawJavadoc.strip() : normalized;
        Map<String, Object> declaredStructuredTags = structuredTags(inheritanceElements, inheritanceText);
        Map<String, Object> resolutionStructuredTags = structuredTagsForInheritance(
                inheritanceElements, inheritanceText);
        InheritedJavadocResolver.Availability focalAvailability = focalJavadocAvailability(
                normalized, rawJavadoc, parsedComment.failed(), rawComment.failed());
        boolean extractionFailed = focalAvailability == InheritedJavadocResolver.Availability.PARSE_FAILED;
        InheritedJavadocResolver.Documentation declaredDocumentation = new InheritedJavadocResolver.Documentation(
                focalAvailability,
                method.methodUri(),
                rawJavadoc,
                mainDescription(inheritanceElements, inheritanceText),
                declaredStructuredTags,
                mainDescriptionForInheritance(inheritanceText),
                resolutionStructuredTags,
                stringValue(declaredStructuredTags.get("parser")),
                stringValue(declaredStructuredTags.get("parse_confidence")),
                extractionFailed ? "source_javadoc_extraction_failed" : "");
        InheritedJavadocResolver.Resolution inherited = enrich("inherited_javadoc", diagnostics,
                () -> executable instanceof CtMethod<?> ctMethod
                ? InheritedJavadocResolver.resolve(ctMethod, usesInheritDoc, declaredDocumentation,
                candidate -> inheritedDocumentation(parsed, candidate),
                SpoonSourceModelBackend::resolveJavadocTypeName,
                SourceBackends.javadocInheritancePolicy())
                : InheritedJavadocResolver.Resolution.notApplicable(executable, declaredDocumentation), null);
        if (inherited == null) {
            Map<String, Object> effective = Map.of(
                    "description", Map.of("text", "", "source", "indeterminate",
                            "inheritance_mode", "unknown", "resolution", "indeterminate"),
                    "type_params", List.of(), "params", List.of(), "return", List.of(),
                    "throws", List.of(), "resolution", "partial");
            inherited = new InheritedJavadocResolver.Resolution("indeterminate", false, List.of(),
                    effective, 0, 0, false);
        }
        metadata.put("since", structuredList(declaredStructuredTags, "since"));
        metadata.put("see", referenceTargetsByTag(references, "see"));
        metadata.put("inline_links", inlineReferenceTargets(references));
        metadata.put("javadoc_references", references);
        metadata.put("file_references", fileReferences(parsed.projectRoot(), method, rawJavadoc));
        // structured_tags remains the declared-only compatibility view. Effective
        // Javadoc inheritance is exposed separately and never overwrites source text.
        metadata.put("structured_tags", declaredStructuredTags);
        metadata.put("declared_structured_tags", declaredStructuredTags);
        metadata.put("effective_structured_tags", inherited.effectiveStructuredTags());
        metadata.put("uses_inheritdoc", usesInheritDoc);
        var policy = SourceBackends.javadocInheritancePolicy();
        metadata.put("inheritdoc_policy", executable instanceof CtMethod<?> ? policy.id() : "not_applicable");
        metadata.put("javadoc_inheritance",
                policy.metadata(SourceBackends.javadocInheritancePolicyDefaulted()));
        metadata.put("deprecated", isDeprecated(executable, normalized));
        metadata.put("deprecation_text", deprecationText(normalized));
        metadata.put("inheritdoc_resolution", inherited.resolution());
        metadata.put("inherited_javadoc_candidates", inherited.candidates());
        metadata.put("inheritdoc_candidate_count", inherited.candidateCount());
        metadata.put("inheritdoc_documented_candidate_count", inherited.documentedCandidateCount());
        metadata.put("inheritdoc_candidates_truncated", inherited.truncated());
        return metadata;
    }

    static InheritedJavadocResolver.Availability focalJavadocAvailability(
            String parsedJavadoc,
            String rawJavadoc,
            boolean parsedFailed,
            boolean rawFailed) {
        if (!(parsedJavadoc == null || parsedJavadoc.isBlank())
                || !(rawJavadoc == null || rawJavadoc.isBlank())) {
            return InheritedJavadocResolver.Availability.PRESENT;
        }
        return parsedFailed || rawFailed
                ? InheritedJavadocResolver.Availability.PARSE_FAILED
                : InheritedJavadocResolver.Availability.ABSENT;
    }

    static InheritedJavadocResolver.TypeNameResolution resolveJavadocTypeName(
            CtMethod<?> focal, String sourceSpelling) {
        if (focal == null || focal.getDeclaringType() == null) {
            return InheritedJavadocResolver.TypeNameResolution.unresolved();
        }
        String spelling = normalizeJavadocTypeSpelling(sourceSpelling);
        if (spelling.isBlank()) {
            return InheritedJavadocResolver.TypeNameResolution.unresolved();
        }
        CtType<?> owner = focal.getDeclaringType();

        if (!spelling.contains(".")) {
            boolean methodTypeParameter = focal.getFormalCtTypeParameters().stream()
                    .anyMatch(parameter -> spelling.equals(parameter.getSimpleName()));
            boolean ownerTypeParameter = enclosingTypes(owner).stream()
                    .flatMap(type -> type.getFormalCtTypeParameters().stream())
                    .anyMatch(parameter -> spelling.equals(parameter.getSimpleName()));
            if (methodTypeParameter || ownerTypeParameter) {
                return InheritedJavadocResolver.TypeNameResolution.resolved(
                        "type-parameter:" + spelling);
            }
        }

        if (spelling.contains(".")) {
            Optional<String> exact = knownTypeName(focal, spelling);
            if (exact.isPresent()) {
                return InheritedJavadocResolver.TypeNameResolution.resolved(exact.get());
            }
        }

        InheritedJavadocResolver.TypeNameResolution lexical = lexicalTypeName(owner, spelling);
        if (lexical.status() != InheritedJavadocResolver.TypeNameResolutionStatus.UNRESOLVED) {
            return lexical;
        }

        if (!spelling.contains(".")) {
            Set<String> inheritedMembers = inheritedMemberTypeNames(owner, spelling);
            if (inheritedMembers.size() == 1) {
                return InheritedJavadocResolver.TypeNameResolution.resolved(
                        inheritedMembers.iterator().next());
            }
            if (inheritedMembers.size() > 1) {
                return InheritedJavadocResolver.TypeNameResolution.ambiguous();
            }
        }

        ImportNameResolution imports = importedTypeNames(focal, spelling);
        if (imports.explicit().size() == 1) {
            return InheritedJavadocResolver.TypeNameResolution.resolved(
                    imports.explicit().iterator().next());
        }
        if (imports.explicit().size() > 1) {
            return InheritedJavadocResolver.TypeNameResolution.ambiguous();
        }

        String ownerPackage = owner.getPackage() == null ? "" : owner.getPackage().getQualifiedName();
        String samePackage = ownerPackage.isBlank() ? spelling : ownerPackage + "." + spelling;
        Optional<String> samePackageType = knownTypeName(focal, samePackage);
        if (samePackageType.isPresent()) {
            return InheritedJavadocResolver.TypeNameResolution.resolved(samePackageType.get());
        }

        Optional<String> javaLang = knownTypeName(focal, "java.lang." + spelling);
        if (javaLang.isPresent()) {
            return InheritedJavadocResolver.TypeNameResolution.resolved(javaLang.get());
        }

        if (imports.onDemand().size() == 1) {
            return InheritedJavadocResolver.TypeNameResolution.resolved(
                    imports.onDemand().iterator().next());
        }
        if (imports.onDemand().size() > 1) {
            return InheritedJavadocResolver.TypeNameResolution.ambiguous();
        }

        Optional<String> canonical = knownTypeName(focal, spelling);
        return canonical.map(InheritedJavadocResolver.TypeNameResolution::resolved)
                .orElseGet(InheritedJavadocResolver.TypeNameResolution::unresolved);
    }

    private static String normalizeJavadocTypeSpelling(String sourceSpelling) {
        return stripModulePrefix(sourceSpelling == null ? "" : sourceSpelling)
                .replaceAll("<.*>", "")
                .trim();
    }

    private static List<CtType<?>> enclosingTypes(CtType<?> owner) {
        List<CtType<?>> types = new ArrayList<>();
        for (CtType<?> current = owner; current != null; current = current.getDeclaringType()) {
            types.add(current);
        }
        return types;
    }

    private static InheritedJavadocResolver.TypeNameResolution lexicalTypeName(
            CtType<?> owner, String spelling) {
        for (CtType<?> lexical : enclosingTypes(owner)) {
            String[] parts = spelling.split("\\.");
            int nextPart = 1;
            CtType<?> resolved = parts[0].equals(lexical.getSimpleName())
                    ? lexical : lexical.getNestedType(parts[0]);
            if (resolved == null) {
                continue;
            }
            while (nextPart < parts.length && resolved != null) {
                resolved = resolved.getNestedType(parts[nextPart++]);
            }
            if (resolved != null) {
                return InheritedJavadocResolver.TypeNameResolution.resolved(
                        resolved.getQualifiedName());
            }
        }
        return InheritedJavadocResolver.TypeNameResolution.unresolved();
    }

    private static Set<String> inheritedMemberTypeNames(CtType<?> owner, String simpleName) {
        Set<String> matches = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        if (owner.getSuperclass() != null) {
            matches.addAll(nearestMemberTypeNames(
                    owner.getSuperclass(), owner, simpleName, visited));
        }
        for (CtTypeReference<?> superInterface : owner.getSuperInterfaces()) {
            matches.addAll(nearestMemberTypeNames(
                    superInterface, owner, simpleName, visited));
        }
        return matches;
    }

    private static Set<String> nearestMemberTypeNames(CtTypeReference<?> reference,
                                                       CtType<?> owner,
                                                       String simpleName,
                                                       Set<String> visited) {
        if (reference == null || !visited.add(reference.getQualifiedName())) {
            return Set.of();
        }
        CtType<?> declaration;
        try {
            declaration = reference.getTypeDeclaration();
        } catch (RuntimeException | LinkageError | AssertionError ignored) {
            declaration = null;
        }
        if (declaration == null) {
            return Set.of();
        }
        Set<String> declaredHere = new LinkedHashSet<>();
        for (CtType<?> nested : declaration.getNestedTypes()) {
            if (simpleName.equals(nested.getSimpleName())
                    && inheritedMemberTypeAccessible(nested, owner)) {
                declaredHere.add(nested.getQualifiedName());
            }
        }
        if (!declaredHere.isEmpty()) {
            return declaredHere;
        }

        Set<String> inherited = new LinkedHashSet<>();
        if (declaration.getSuperclass() != null) {
            inherited.addAll(nearestMemberTypeNames(
                    declaration.getSuperclass(), owner, simpleName, visited));
        }
        for (CtTypeReference<?> superInterface : declaration.getSuperInterfaces()) {
            inherited.addAll(nearestMemberTypeNames(
                    superInterface, owner, simpleName, visited));
        }
        return inherited;
    }

    private static boolean inheritedMemberTypeAccessible(CtType<?> member, CtType<?> owner) {
        if (member.isPrivate()) {
            return false;
        }
        if (member.isPublic() || member.isProtected()) {
            return true;
        }
        String memberPackage = member.getPackage() == null ? "" : member.getPackage().getQualifiedName();
        String ownerPackage = owner.getPackage() == null ? "" : owner.getPackage().getQualifiedName();
        return memberPackage.equals(ownerPackage);
    }

    private static ImportNameResolution importedTypeNames(CtMethod<?> focal, String spelling) {
        Set<String> explicit = new LinkedHashSet<>();
        Set<String> onDemand = new LinkedHashSet<>();
        int separator = spelling.indexOf('.');
        String leading = separator < 0 ? spelling : spelling.substring(0, separator);
        String suffix = separator < 0 ? "" : spelling.substring(separator);
        try {
            if (focal.getPosition().isValidPosition()) {
                focal.getPosition().getCompilationUnit().getImports().forEach(importValue -> {
                    String imported = importValue.toString()
                            .replaceFirst("^import\\s+(?:static\\s+)?", "")
                            .replace(";", "").trim();
                    if (imported.endsWith(".*")) {
                        String candidate = imported.substring(0, imported.length() - 1) + spelling;
                        knownTypeName(focal, candidate).ifPresent(onDemand::add);
                    } else if (simpleTypeName(imported).equals(leading)) {
                        explicit.add(imported + suffix);
                    }
                });
            }
        } catch (RuntimeException | LinkageError | AssertionError ignored) {
            // Incomplete import metadata leaves the source spelling unresolved.
        }
        return new ImportNameResolution(explicit, onDemand);
    }

    private static Optional<String> knownTypeName(CtMethod<?> focal, String candidate) {
        return knownTypeName(focal.getFactory().Type().getAll(), candidate)
                .or(() -> hierarchyTypeName(focal.getDeclaringType(), candidate));
    }

    private static Optional<String> knownTypeName(List<CtType<?>> types, String candidate) {
        String normalized = candidate.replace('$', '.');
        return types.stream()
                .map(CtType::getQualifiedName)
                .filter(name -> name.replace('$', '.').equals(normalized))
                .findFirst();
    }

    private static Optional<String> hierarchyTypeName(CtType<?> owner, String candidate) {
        String normalized = candidate.replace('$', '.');
        List<CtTypeReference<?>> pending = new ArrayList<>();
        if (owner.getSuperclass() != null) {
            pending.add(owner.getSuperclass());
        }
        pending.addAll(owner.getSuperInterfaces());
        Set<String> visited = new LinkedHashSet<>();
        for (int index = 0; index < pending.size(); index++) {
            CtTypeReference<?> reference = pending.get(index);
            if (reference == null || !visited.add(reference.getQualifiedName())) {
                continue;
            }
            if (reference.getQualifiedName().replace('$', '.').equals(normalized)) {
                return Optional.of(reference.getQualifiedName());
            }
            try {
                CtType<?> declaration = reference.getTypeDeclaration();
                if (declaration != null) {
                    if (declaration.getSuperclass() != null) {
                        pending.add(declaration.getSuperclass());
                    }
                    pending.addAll(declaration.getSuperInterfaces());
                }
            } catch (RuntimeException | LinkageError | AssertionError ignored) {
                // A shadow type can still be matched by its existing reference.
            }
        }
        return Optional.empty();
    }

    private record ImportNameResolution(Set<String> explicit, Set<String> onDemand) {
    }

    private static InheritedJavadocResolver.Documentation inheritedDocumentation(
            ParsedProject parsed, CtMethod<?> method) {
        CommentAttempt parsedComment = docCommentAttempt(method);
        RawCommentAttempt rawComment = rawDocCommentAttempt(parsed, method);
        String normalized = parsedComment.text().strip();
        Optional<String> raw = rawComment.text();
        String rawJavadoc = raw.orElse(normalized);
        boolean preserveExplicitTarget = hasExplicitInheritDocTarget(rawJavadoc);
        String parseText = normalized.isBlank() || preserveExplicitTarget ? rawJavadoc : normalized;
        boolean sourceAvailable = sourceFile(method).isPresent() && !method.isShadow();
        if (normalized.isBlank() && rawJavadoc.isBlank()) {
            boolean extractionFailed = parsedComment.failed() || rawComment.failed();
            return new InheritedJavadocResolver.Documentation(
                    sourceAvailable ? extractionFailed
                            ? InheritedJavadocResolver.Availability.PARSE_FAILED
                            : InheritedJavadocResolver.Availability.ABSENT
                            : InheritedJavadocResolver.Availability.SOURCE_UNAVAILABLE,
                    sourceMethodUri(parsed, method), "", "", emptyStructuredTags(),
                    "", emptyStructuredTags(), "", "none",
                    sourceAvailable ? extractionFailed
                            ? "source_javadoc_extraction_failed"
                            : "source_declaration_has_no_javadoc"
                            : "source_declaration_unavailable");
        }

        List<JavadocElement> elements = preserveExplicitTarget ? List.of() : spoonJavadocElements(method);
        Map<String, Object> tags = structuredTags(elements, parseText);
        Map<String, Object> resolutionTags = structuredTagsForInheritance(elements, parseText);
        String diagnostic = elements.isEmpty() && !parseText.isBlank()
                ? "spoon_javadoc_parse_unavailable_fallback_used"
                : "";
        return new InheritedJavadocResolver.Documentation(
                InheritedJavadocResolver.Availability.PRESENT,
                sourceMethodUri(parsed, method),
                rawJavadoc,
                mainDescription(elements, parseText),
                tags,
                mainDescriptionForInheritance(parseText),
                resolutionTags,
                stringValue(tags.get("parser")),
                stringValue(tags.get("parse_confidence")),
                diagnostic);
    }

    private record CommentAttempt(String text, boolean failed) {
    }

    private record RawCommentAttempt(Optional<String> text, boolean failed) {
    }

    private static String sourceMethodUri(ParsedProject parsed, CtMethod<?> method) {
        CtType<?> owner = method != null ? method.getDeclaringType() : null;
        Optional<Path> source = sourceFile(method);
        if (owner == null || source.isEmpty()) {
            return "";
        }
        List<SourceParameter> parameters = parameters(method);
        String sourceReturnType = returnType(method);
        String signature = identitySignature(method.getSimpleName(), parameters,
                erasedReturnType(method, sourceReturnType));
        return methodUri(parsed.projectRoot(), source.get(), owner.getQualifiedName(), signature);
    }

    private static Map<String, Object> emptyStructuredTags() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("parser", "");
        tags.put("parse_confidence", "none");
        tags.put("params", List.of());
        tags.put("return", List.of());
        tags.put("throws", List.of());
        tags.put("since", List.of());
        tags.put("api_notes", List.of());
        tags.put("impl_specs", List.of());
        tags.put("impl_notes", List.of());
        tags.put("deprecated", List.of());
        return tags;
    }

    private static String mainDescription(List<JavadocElement> elements, String javadoc) {
        if (elements != null && !elements.isEmpty()) {
            List<JavadocElement> description = new ArrayList<>();
            for (JavadocElement element : elements) {
                if (element instanceof JavadocBlockTag) {
                    break;
                }
                description.add(element);
            }
            return elementsText(description);
        }
        String value = javadoc == null ? "" : javadoc;
        List<RawBlockTag> blockTags = rawBlockTags(value);
        return (blockTags.isEmpty() ? value : value.substring(0, blockTags.get(0).start()))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String mainDescriptionForInheritance(String javadoc) {
        // Spoon 11.2 normalizes standard tag names case-insensitively. The raw
        // source spelling must therefore remain authoritative for inheritance.
        String value = protectLiteralContent(javadoc == null ? "" : javadoc);
        List<RawBlockTag> blockTags = rawBlockTags(value);
        String description = blockTags.isEmpty() ? value : value.substring(0, blockTags.get(0).start());
        return markInlineReturns(description).replaceAll("\\s+", " ").trim();
    }

    private static String protectLiteralContent(String text) {
        String value = text == null ? "" : text;
        StringBuilder protectedText = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            InlineTag tag = nextInlineTag(value, cursor);
            if (tag == null) {
                protectedText.append(value, cursor, value.length());
                break;
            }
            protectedText.append(value, cursor, tag.nameEnd());
            String body = value.substring(tag.nameEnd(), tag.end());
            protectedText.append(containsLiteralContent(tag.name())
                    ? protectJavadocTagMarkers(body)
                    : protectLiteralContent(body));
            protectedText.append('}');
            cursor = tag.end() + 1;
        }
        return protectedText.toString();
    }

    private static String protectJavadocTagMarkers(String text) {
        return (text == null ? "" : text).replace('@', InheritedJavadocResolver.PROTECTED_AT_SIGN);
    }

    private static boolean containsLiteralContent(String tagName) {
        return "code".equals(tagName) || "literal".equals(tagName) || "snippet".equals(tagName);
    }

    private static String markInlineReturns(String text) {
        String value = text == null ? "" : text;
        int firstSignificant = 0;
        while (firstSignificant < value.length()
                && Character.isWhitespace(value.charAt(firstSignificant))) {
            firstSignificant++;
        }
        InlineTag tag = nextInlineTag(value, firstSignificant);
        if (tag == null || tag.start() != firstSignificant || !"return".equals(tag.name())) {
            return value;
        }
        return value.substring(0, tag.start())
                + InheritedJavadocResolver.INLINE_RETURN_START
                + tag.body()
                + InheritedJavadocResolver.INLINE_RETURN_END
                + value.substring(tag.end() + 1);
    }

    private static int inlineTagEnd(String text, int start) {
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static InlineTag nextInlineTag(String text, int fromIndex) {
        String value = text == null ? "" : text;
        int cursor = Math.max(0, fromIndex);
        while (cursor < value.length()) {
            int start = value.indexOf("{@", cursor);
            if (start < 0 || start + 2 >= value.length()) {
                return null;
            }
            int nameStart = start + 2;
            if (!Character.isJavaIdentifierStart(value.charAt(nameStart))) {
                cursor = nameStart + 1;
                continue;
            }
            int nameEnd = nameStart + 1;
            while (nameEnd < value.length()
                    && Character.isJavaIdentifierPart(value.charAt(nameEnd))) {
                nameEnd++;
            }
            int end = inlineTagEnd(value, start);
            if (end < 0) {
                return null;
            }
            return new InlineTag(start, end, nameEnd,
                    value.substring(nameStart, nameEnd),
                    value.substring(nameEnd, end).trim());
        }
        return null;
    }

    private static boolean containsInheritDoc(String javadoc) {
        return containsInlineTag(protectLiteralContent(javadoc), "inheritDoc", false);
    }

    private static boolean hasExplicitInheritDocTarget(String javadoc) {
        return containsInlineTag(protectLiteralContent(javadoc), "inheritDoc", true);
    }

    private static boolean containsInlineTag(String text, String name, boolean requireBody) {
        String value = text == null ? "" : text;
        int cursor = 0;
        while (cursor < value.length()) {
            InlineTag tag = nextInlineTag(value, cursor);
            if (tag == null) {
                return false;
            }
            if (name.equals(tag.name()) && (!requireBody || !tag.body().isBlank())) {
                return true;
            }
            if (!containsLiteralContent(tag.name())
                    && containsInlineTag(tag.body(), name, requireBody)) {
                return true;
            }
            cursor = tag.end() + 1;
        }
        return false;
    }

    private record InlineTag(int start, int end, int nameEnd, String name, String body) {
    }

    private record RawBlockTag(int start, String name, String body) {
    }

    private record RawBlockTagStart(int start, int nameEnd, String name) {
    }

    private static List<RawBlockTag> rawBlockTags(String text) {
        String value = text == null ? "" : text;
        List<InlineTag> inlineTags = new ArrayList<>();
        int inlineCursor = 0;
        while (inlineCursor < value.length()) {
            InlineTag tag = nextInlineTag(value, inlineCursor);
            if (tag == null) {
                break;
            }
            inlineTags.add(tag);
            inlineCursor = tag.end() + 1;
        }

        List<RawBlockTagStart> starts = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= value.length()) {
            int lineEnd = value.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = value.length();
            }
            int cursor = lineStart;
            while (cursor < lineEnd && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            if (cursor < lineEnd && value.charAt(cursor) == '*') {
                cursor++;
                while (cursor < lineEnd && Character.isWhitespace(value.charAt(cursor))) {
                    cursor++;
                }
            }
            if (cursor < lineEnd && value.charAt(cursor) == '@'
                    && !insideInlineTag(inlineTags, cursor)) {
                int nameStart = cursor + 1;
                if (nameStart < lineEnd && Character.isJavaIdentifierStart(value.charAt(nameStart))) {
                    int nameEnd = nameStart + 1;
                    while (nameEnd < lineEnd && Character.isJavaIdentifierPart(value.charAt(nameEnd))) {
                        nameEnd++;
                    }
                    starts.add(new RawBlockTagStart(lineStart, nameEnd,
                            value.substring(nameStart, nameEnd)));
                }
            }
            if (lineEnd == value.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }

        List<RawBlockTag> tags = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            RawBlockTagStart start = starts.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1).start() : value.length();
            tags.add(new RawBlockTag(start.start(), start.name(),
                    normalizeRawBlockBody(value.substring(start.nameEnd(), end))));
        }
        return tags;
    }

    private static boolean insideInlineTag(List<InlineTag> tags, int offset) {
        for (InlineTag tag : tags) {
            if (offset < tag.start()) {
                return false;
            }
            if (offset <= tag.end()) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeRawBlockBody(String body) {
        List<String> lines = new ArrayList<>();
        for (String line : (body == null ? "" : body).split("\\R", -1)) {
            lines.add(line.replaceFirst("^\\s*\\*?\\s?", ""));
        }
        return String.join(" ", lines).replaceAll("\\s+", " ").trim();
    }

    private static List<String> rawBlockTagBodies(String text, String name) {
        return rawBlockTags(text).stream()
                .filter(tag -> name.equals(tag.name()))
                .map(RawBlockTag::body)
                .toList();
    }

    private static List<Map<String, Object>> fileReferences(Path projectRoot, SourceMethod method, String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> references = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = JAVADOC_FILE_REFERENCE.matcher(javadoc);
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.startsWith("http://") || raw.startsWith("https://")
                    || precededByUrlScheme(javadoc, matcher.start())) {
                continue;
            }
            String normalized = raw.replace("{@docRoot}/", "");
            if (!seen.add(normalized)) {
                continue;
            }
            Path resolved = resolveJavadocFile(projectRoot, method.sourceFile(), normalized);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("raw", raw);
            ref.put("path", normalized);
            ref.put("kind", fileReferenceKind(normalized));
            ref.put("parser", "cocomut-file-regex");
            ref.put("parse_confidence", "low");
            ref.put("source_form", fileReferenceSourceForm(javadoc, matcher.start(), raw, method.sourceFile(), normalized));
            ref.put("resolved_path", resolved != null ? resolved.toString() : "");
            ref.put("exists", resolved != null && Files.exists(resolved));
            if (resolved != null && Files.exists(resolved) && isTextLike(normalized)) {
                ref.put("excerpt", excerpt(readSmallFile(resolved)));
            }
            references.add(ref);
            if (references.size() >= 20) {
                break;
            }
        }
        return references;
    }

    private static String fileReferenceSourceForm(String javadoc, int start, String raw,
                                                  Path sourceFile, String normalizedPath) {
        String prefix = javadoc.substring(Math.max(0, start - 64), start).toLowerCase(Locale.ROOT);
        String lowerRaw = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (lowerRaw.startsWith("{@docroot}/") || prefix.endsWith("{@docroot}/")
                || sourceContains(sourceFile, "{@docRoot}/" + normalizedPath)) {
            return "doc_root";
        }
        if (prefix.matches("(?s).*@filename\\s+$")) {
            return "filename_tag";
        }
        if (prefix.matches("(?s).*\\{@snippet\\s+[^}]*file\\s*=\\s*[\"']$")) {
            return "snippet_file_attribute";
        }
        if (lowerRaw.contains("doc-files/")) {
            return "doc_files";
        }
        return "regex_text";
    }

    private static boolean sourceContains(Path sourceFile, String text) {
        if (sourceFile == null || text == null || text.isBlank() || !Files.isRegularFile(sourceFile)) {
            return false;
        }
        try {
            return Files.readString(sourceFile, Charset.defaultCharset()).contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean precededByUrlScheme(String text, int start) {
        int from = Math.max(0, start - 16);
        String prefix = text.substring(from, start).toLowerCase(Locale.ROOT);
        return prefix.contains("http://") || prefix.contains("https://");
    }

    private static Path resolveJavadocFile(Path projectRoot, Path sourceFile, String rawPath) {
        if (sourceFile == null || rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path sourceDir = sourceFile.toAbsolutePath().normalize().getParent();
        if (sourceDir == null) {
            return null;
        }
        Path direct = sourceDir.resolve(rawPath).normalize();
        if (isWithinProject(projectRoot, direct) && Files.exists(direct)) {
            return direct;
        }
        int docFiles = rawPath.indexOf("doc-files/");
        if (docFiles >= 0) {
            Path docFile = sourceDir.resolve(rawPath.substring(docFiles)).normalize();
            if (isWithinProject(projectRoot, docFile) && Files.exists(docFile)) {
                return docFile;
            }
        }
        return isWithinProject(projectRoot, direct) ? direct : null;
    }

    private static boolean isWithinProject(Path projectRoot, Path candidate) {
        if (projectRoot == null || candidate == null) {
            return false;
        }
        try {
            Path root = projectRoot.toRealPath();
            Path path = Files.exists(candidate) ? candidate.toRealPath() : candidate.toAbsolutePath().normalize();
            return path.startsWith(root);
        } catch (Exception e) {
            Path root = projectRoot.toAbsolutePath().normalize();
            Path path = candidate.toAbsolutePath().normalize();
            return path.startsWith(root);
        }
    }

    private static String fileReferenceKind(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".svg") || lower.endsWith(".gif")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        }
        if (lower.endsWith(".java")) {
            return "sample_source";
        }
        return "text";
    }

    private static boolean isTextLike(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".txt") || lower.endsWith(".java");
    }

    private static String readSmallFile(Path path) {
        try {
            if (Files.size(path) > 32_768) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Map<String, Object> structuredTags(List<JavadocElement> elements, String javadoc) {
        Map<String, Object> tags = !elements.isEmpty() && typedSyntaxMatches(elements, javadoc)
                ? structuredTagsFromElements(elements, false)
                : fallbackStructuredTags(javadoc, false);
        addLeadingInlineReturn(tags, javadoc, false);
        return tags;
    }

    private static Map<String, Object> structuredTagsForInheritance(
            List<JavadocElement> elements, String javadoc) {
        String protectedJavadoc = protectLiteralContent(javadoc);
        Map<String, Object> tags = literalContentNeedsProtection(javadoc)
                || elements.isEmpty()
                || !typedSyntaxMatches(elements, javadoc)
                ? fallbackStructuredTags(protectedJavadoc, true)
                : structuredTagsFromElements(elements, true);
        addLeadingInlineReturn(tags, protectedJavadoc, true);
        return tags;
    }

    private static boolean literalContentNeedsProtection(String javadoc) {
        String value = javadoc == null ? "" : javadoc;
        return !value.equals(protectLiteralContent(value));
    }

    private static boolean typedSyntaxMatches(List<JavadocElement> elements, String javadoc) {
        List<JavadocBlockTag> typed = blockTags(elements);
        List<RawBlockTag> raw = rawBlockTags(javadoc);
        if (typed.size() != raw.size()) {
            return false;
        }
        for (int index = 0; index < typed.size(); index++) {
            if (!raw.get(index).name().equals(typed.get(index).getTagType().getName())) {
                return false;
            }
        }
        return !containsCaseVariantOfStandardInlineTag(javadoc);
    }

    private static boolean containsCaseVariantOfStandardInlineTag(String text) {
        String value = text == null ? "" : text;
        int cursor = 0;
        while (cursor < value.length()) {
            InlineTag tag = nextInlineTag(value, cursor);
            if (tag == null) {
                return false;
            }
            boolean standardIgnoringCase = java.util.Arrays.stream(StandardJavadocTagType.values())
                    .anyMatch(type -> type.getName().equalsIgnoreCase(tag.name()));
            boolean standardExact = java.util.Arrays.stream(StandardJavadocTagType.values())
                    .anyMatch(type -> type.getName().equals(tag.name()));
            if (standardIgnoringCase && !standardExact) {
                return true;
            }
            if (!containsLiteralContent(tag.name())
                    && containsCaseVariantOfStandardInlineTag(tag.body())) {
                return true;
            }
            cursor = tag.end() + 1;
        }
        return false;
    }

    private static Map<String, Object> structuredTagsFromElements(
            List<JavadocElement> elements, boolean inheritanceSafe) {
        Map<String, Object> tags = new LinkedHashMap<>();
        List<Map<String, String>> params = new ArrayList<>();
        List<Map<String, String>> throwsTags = new ArrayList<>();
        List<String> returns = new ArrayList<>();
        List<String> since = new ArrayList<>();
        List<String> apiNotes = new ArrayList<>();
        List<String> implSpecs = new ArrayList<>();
        List<String> implNotes = new ArrayList<>();
        List<String> deprecated = new ArrayList<>();

        for (JavadocBlockTag block : blockTags(elements)) {
            String tag = block.getTagType().getName();
            List<JavadocElement> blockElements = block.getElements();
            switch (tag) {
                case "param" -> params.add(namedBlockTag(blockElements, "name", inheritanceSafe));
                case "return" -> returns.add(elementsText(blockElements, inheritanceSafe));
                case "throws", "exception" -> throwsTags.add(
                        namedBlockTag(blockElements, "type", inheritanceSafe));
                case "since" -> since.add(elementsText(blockElements, inheritanceSafe));
                case "apiNote" -> apiNotes.add(elementsText(blockElements, inheritanceSafe));
                case "implSpec" -> implSpecs.add(elementsText(blockElements, inheritanceSafe));
                case "implNote" -> implNotes.add(elementsText(blockElements, inheritanceSafe));
                case "deprecated" -> deprecated.add(elementsText(blockElements, inheritanceSafe));
                default -> {
                    // Only expose the structured tags CoCoMUT's schema names.
                }
            }
        }
        tags.put("parser", "spoon-javadoc");
        tags.put("parse_confidence", "high");
        tags.put("params", params);
        tags.put("return", returns);
        tags.put("throws", throwsTags);
        tags.put("since", since);
        tags.put("api_notes", apiNotes);
        tags.put("impl_specs", implSpecs);
        tags.put("impl_notes", implNotes);
        tags.put("deprecated", deprecated);
        return tags;
    }

    private static Map<String, Object> fallbackStructuredTags(String javadoc) {
        return fallbackStructuredTags(javadoc, false);
    }

    private static Map<String, Object> fallbackStructuredTags(
            String javadoc, boolean inheritanceSafe) {
        Map<String, Object> tags = new LinkedHashMap<>();
        List<Map<String, String>> params = new ArrayList<>();
        List<Map<String, String>> throwsTags = new ArrayList<>();
        List<String> returns = new ArrayList<>();
        List<String> since = new ArrayList<>();
        List<String> apiNotes = new ArrayList<>();
        List<String> implSpecs = new ArrayList<>();
        List<String> implNotes = new ArrayList<>();
        List<String> deprecated = new ArrayList<>();

        String protectedJavadoc = protectLiteralContent(javadoc);
        for (RawBlockTag block : rawBlockTags(protectedJavadoc)) {
            String tag = block.name();
            String body = block.body();
            if (!inheritanceSafe) {
                body = InheritedJavadocResolver.decodeProtectedText(body);
            }
            switch (tag) {
                case "param" -> params.add(splitNamedText(body, "name"));
                case "return" -> returns.add(body);
                case "throws", "exception" -> throwsTags.add(splitNamedText(body, "type"));
                case "since" -> since.add(body);
                case "apiNote" -> apiNotes.add(body);
                case "implSpec" -> implSpecs.add(body);
                case "implNote" -> implNotes.add(body);
                case "deprecated" -> deprecated.add(body);
                default -> {
                    // Keep the switch exhaustive for known fallback tags above.
                }
            }
        }
        tags.put("parser", "cocomut-fallback");
        tags.put("parse_confidence", "low");
        tags.put("params", params);
        tags.put("return", returns);
        tags.put("throws", throwsTags);
        tags.put("since", since);
        tags.put("api_notes", apiNotes);
        tags.put("impl_specs", implSpecs);
        tags.put("impl_notes", implNotes);
        tags.put("deprecated", deprecated);
        return tags;
    }

    @SuppressWarnings("unchecked")
    private static void addLeadingInlineReturn(Map<String, Object> tags,
                                               String javadoc,
                                               boolean inheritanceSafe) {
        String protectedJavadoc = protectLiteralContent(javadoc);
        String main = mainDescription(List.of(), protectedJavadoc);
        leadingInlineTagBody(main, "return").ifPresent(body -> {
            List<String> returns = (List<String>) tags.get("return");
            String value = inheritanceSafe
                    ? body : InheritedJavadocResolver.decodeProtectedText(body);
            if (!returns.contains(value)) {
                returns.add(value);
            }
        });
    }

    private static Optional<String> leadingInlineTagBody(String text, String name) {
        String value = text == null ? "" : text;
        int firstSignificant = 0;
        while (firstSignificant < value.length()
                && Character.isWhitespace(value.charAt(firstSignificant))) {
            firstSignificant++;
        }
        InlineTag tag = nextInlineTag(value, firstSignificant);
        return tag != null && tag.start() == firstSignificant && name.equals(tag.name())
                ? Optional.of(tag.body()) : Optional.empty();
    }

    private static List<JavadocBlockTag> blockTags(List<JavadocElement> elements) {
        List<JavadocBlockTag> blocks = new ArrayList<>();
        for (JavadocElement element : elements == null ? List.<JavadocElement>of() : elements) {
            if (element instanceof JavadocBlockTag block) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private static Map<String, String> namedBlockTag(
            List<JavadocElement> elements, String key, boolean inheritanceSafe) {
        if (elements == null || elements.isEmpty()) {
            return Map.of(key, "", "text", "");
        }
        String name = elementText(elements.get(0), inheritanceSafe);
        String text = elementsText(elements.subList(1, elements.size()), inheritanceSafe);
        Map<String, String> value = new LinkedHashMap<>();
        value.put(key, name);
        value.put("text", text);
        return value;
    }

    private static Map<String, String> splitNamedText(String body, String key) {
        String[] parts = (body == null ? "" : body).split("\\s+", 2);
        Map<String, String> value = new LinkedHashMap<>();
        value.put(key, parts.length > 0 ? parts[0] : "");
        value.put("text", parts.length > 1 ? parts[1] : "");
        return value;
    }

    private static List<String> structuredTagNames(Map<String, Object> structuredTags, String field) {
        Object value = structuredTags.get(field);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object name = map.get("name");
                if (name != null && !name.toString().isBlank()) {
                    names.add(name.toString());
                }
            }
        }
        return names;
    }

    private static List<?> structuredList(Map<String, Object> structuredTags, String field) {
        Object value = structuredTags.get(field);
        return value instanceof List<?> list ? list : List.of();
    }

    private static String elementsText(List<JavadocElement> elements) {
        return elementsText(elements, false);
    }

    private static String elementsText(List<JavadocElement> elements, boolean inheritanceSafe) {
        if (elements == null || elements.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JavadocElement element : elements) {
            String text = elementText(element, inheritanceSafe);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join(" ", parts).replaceAll("\\s+", " ").trim();
    }

    private static String elementText(JavadocElement element) {
        return elementText(element, false);
    }

    private static String elementText(JavadocElement element, boolean inheritanceSafe) {
        if (element instanceof JavadocText text) {
            return text.getText();
        }
        if (element instanceof JavadocReference reference) {
            return referenceTarget(reference.getReference());
        }
        if (element instanceof JavadocInlineTag inline) {
            if (StandardJavadocTagType.INHERIT_DOC.equals(inline.getTagType())) {
                return "{@inheritDoc}";
            }
            String content = elementsText(inline.getElements(), inheritanceSafe);
            if (inheritanceSafe && (StandardJavadocTagType.CODE.equals(inline.getTagType())
                    || StandardJavadocTagType.LITERAL.equals(inline.getTagType())
                    || StandardJavadocTagType.SNIPPET.equals(inline.getTagType()))) {
                return protectJavadocTagMarkers(content);
            }
            if (inheritanceSafe && StandardJavadocTagType.RETURN.equals(inline.getTagType())) {
                return InheritedJavadocResolver.INLINE_RETURN_START + content
                        + InheritedJavadocResolver.INLINE_RETURN_END;
            }
            return content;
        }
        if (element instanceof JavadocBlockTag block) {
            return elementsText(block.getElements(), inheritanceSafe);
        }
        return "";
    }

    private static List<Map<String, Object>> javadocReferences(ParsedProject parsed, CtType<?> owner, CtExecutable<?> focal,
                                                               List<JavadocElement> elements, String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> references = new ArrayList<>();
        List<RawJavadocReference> rawReferences = new ArrayList<>(rawJavadocReferences(javadoc));

        if (typedSyntaxMatches(elements, javadoc)) {
            for (JavadocElement element : elements) {
                addSpoonJavadocReference(parsed, owner, focal, element, rawReferences, references);
            }
        }

        Set<String> represented = new LinkedHashSet<>();
        for (Map<String, Object> reference : references) {
            represented.add(javadocReferenceKey(reference));
        }
        for (Map<String, Object> fallback : fallbackJavadocReferences(parsed, owner, focal, javadoc)) {
            if (represented.add(javadocReferenceKey(fallback))) {
                fallback.put("fallback_reason", references.isEmpty()
                        ? "spoon_no_references"
                        : "not_represented_by_spoon");
                references.add(fallback);
            }
        }

        return references;
    }

    private static String javadocReferenceKey(Map<String, Object> reference) {
        return stringValue(reference.get("tag")) + "\u0000" + stringValue(reference.get("target"));
    }

    private static List<String> referenceTargetsByTag(List<Map<String, Object>> references, String tag) {
        List<Map<String, Object>> primary = references.stream()
                .filter(reference -> tag.equals(reference.get("tag")))
                .filter(reference -> !"cocomut-fallback".equals(reference.get("parser")))
                .toList();
        List<Map<String, Object>> source = primary.isEmpty()
                ? references.stream().filter(reference -> tag.equals(reference.get("tag"))).toList()
                : primary;
        return source.stream()
                .map(reference -> stringValue(reference.get("target")))
                .filter(target -> !target.isBlank())
                .filter(SpoonSourceModelBackend::completeReferenceTarget)
                .distinct()
                .toList();
    }

    private static List<String> inlineReferenceTargets(List<Map<String, Object>> references) {
        List<Map<String, Object>> primary = references.stream()
                .filter(reference -> "link".equals(reference.get("tag"))
                        || "linkplain".equals(reference.get("tag")))
                .filter(reference -> !"cocomut-fallback".equals(reference.get("parser")))
                .toList();
        List<Map<String, Object>> source = primary.isEmpty()
                ? references.stream()
                .filter(reference -> "link".equals(reference.get("tag"))
                        || "linkplain".equals(reference.get("tag")))
                .toList()
                : primary;
        return source.stream()
                .map(reference -> stringValue(reference.get("target")))
                .filter(target -> !target.isBlank())
                .filter(SpoonSourceModelBackend::completeReferenceTarget)
                .distinct()
                .toList();
    }

    private static boolean completeReferenceTarget(String target) {
        int parenDepth = 0;
        int angleDepth = 0;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth--;
            } else if (c == '<') {
                angleDepth++;
            } else if (c == '>') {
                angleDepth--;
            }
            if (parenDepth < 0 || angleDepth < 0) {
                return false;
            }
        }
        return parenDepth == 0 && angleDepth == 0;
    }

    private static List<String> inlineLinkTargets(List<JavadocElement> elements, String javadoc) {
        if (!typedSyntaxMatches(elements, javadoc)) {
            return inlineLinkReferences(javadoc).stream()
                    .map(InlineJavadocReference::target)
                    .toList();
        }
        List<String> spoonTargets = new ArrayList<>();
        collectInlineLinkTargets(elements, spoonTargets);
        if (!spoonTargets.isEmpty()) {
            List<String> rawTargets = inlineLinkReferences(javadoc).stream()
                    .map(InlineJavadocReference::target)
                    .toList();
            return rawTargets.size() == spoonTargets.size() ? rawTargets : spoonTargets;
        }
        return inlineLinkReferences(javadoc).stream()
                .map(InlineJavadocReference::target)
                .toList();
    }

    private static void collectInlineLinkTargets(List<JavadocElement> elements, List<String> targets) {
        for (JavadocElement element : elements == null ? List.<JavadocElement>of() : elements) {
            if (element instanceof JavadocInlineTag inline) {
                if (isInlineReferenceTag(inline)) {
                    firstReferenceTarget(inline).ifPresent(targets::add);
                }
                collectInlineLinkTargets(inline.getElements(), targets);
            } else if (element instanceof JavadocBlockTag block) {
                collectInlineLinkTargets(block.getElements(), targets);
            }
        }
    }

    private static List<JavadocElement> spoonJavadocElements(CtElement element) {
        if (element == null) {
            return List.of();
        }
        try {
            return JavadocParser.forElement(element);
        } catch (RuntimeException | AssertionError | StackOverflowError ignored) {
            ResourceFailures.rethrowIfPresent(ignored);
            return List.of();
        }
    }

    private static void addSpoonJavadocReference(ParsedProject parsed, CtType<?> owner, CtExecutable<?> focal,
                                                 JavadocElement element,
                                                 List<RawJavadocReference> rawReferences,
                                                 List<Map<String, Object>> references) {
        if (element instanceof JavadocInlineTag inline) {
            if (isInlineReferenceTag(inline)) {
                String canonicalTarget = firstReferenceTarget(inline).orElse("");
                references.add(spoonJavadocReference(parsed, owner, focal, inline.getTagType().getName(), inline.getElements(),
                        nextRaw(rawReferences, inline.getTagType().getName(), canonicalTarget)));
            }
            for (JavadocElement nested : inline.getElements()) {
                addSpoonJavadocReference(parsed, owner, focal, nested, rawReferences, references);
            }
        } else if (element instanceof JavadocBlockTag block) {
            if (StandardJavadocTagType.SEE.equals(block.getTagType())) {
                String canonicalTarget = firstReferenceTarget(block.getElements()).orElse("");
                references.add(spoonJavadocReference(parsed, owner, focal, block.getTagType().getName(), block.getElements(),
                        nextRaw(rawReferences, block.getTagType().getName(), canonicalTarget)));
            }
            for (JavadocElement nested : block.getElements()) {
                addSpoonJavadocReference(parsed, owner, focal, nested, rawReferences, references);
            }
        }
    }

    private static boolean isInlineReferenceTag(JavadocInlineTag tag) {
        return StandardJavadocTagType.LINK.equals(tag.getTagType())
                || StandardJavadocTagType.LINKPLAIN.equals(tag.getTagType())
                || StandardJavadocTagType.VALUE.equals(tag.getTagType());
    }

    private static Map<String, Object> spoonJavadocReference(ParsedProject parsed, CtType<?> owner, CtExecutable<?> focal,
                                                             String tag, List<JavadocElement> elements,
                                                             MatchedRawJavadocReference rawReference) {
        Optional<JavadocReference> reference = elements.stream()
                .filter(JavadocReference.class::isInstance)
                .map(JavadocReference.class::cast)
                .findFirst();
        String label = labelText(elements);
        if (reference.isPresent()) {
            CtReference spoonReference = reference.get().getReference();
            String canonicalTarget = referenceTarget(spoonReference);
            Optional<RawJavadocReference> reliableRaw = rawReference.reliableReference();
            String raw = reliableRaw.map(RawJavadocReference::raw).orElse(canonicalTarget);
            String target = reliableRaw.map(RawJavadocReference::target).orElse(canonicalTarget);
            String resolvedLabel = reliableRaw.map(RawJavadocReference::label)
                    .filter(rawLabel -> !rawLabel.isBlank())
                    .orElse(label);
            Map<String, Object> ref = resolveTypedJavadocReference(parsed, owner, focal, tag, raw, target,
                    resolvedLabel, spoonReference);
            ref.put("parser", "spoon-javadoc");
            ref.put("parse_confidence", "high");
            ref.put("spoon_reference", spoonReference.toString());
            if (!canonicalTarget.equals(target)) {
                ref.put("canonical_target", canonicalTarget);
            }
            if (!rawReference.confidence().equals("high")) {
                ref.put("raw_pairing_confidence", rawReference.confidence());
            }
            return ref;
        }
        String text = elements.stream()
                .filter(JavadocText.class::isInstance)
                .map(JavadocText.class::cast)
                .map(JavadocText::getText)
                .reduce((left, right) -> left + " " + right)
                .orElse("")
                .trim();
        Optional<RawJavadocReference> reliableRaw = rawReference.reliableReference();
        String raw = reliableRaw.map(RawJavadocReference::raw).orElse(text);
        String target = reliableRaw.map(RawJavadocReference::target)
                .orElseGet(() -> splitReferenceTargetAndLabel(text)[0]);
        String resolvedLabel = reliableRaw.map(RawJavadocReference::label)
                .orElseGet(() -> splitReferenceTargetAndLabel(text)[1]);
        Map<String, Object> ref = resolveJavadocReference(parsed, owner, focal, tag, raw, target, resolvedLabel);
        ref.put("parser", "spoon-javadoc-text-fallback");
        ref.put("parse_confidence", "medium");
        if (!rawReference.confidence().equals("high")) {
            ref.put("raw_pairing_confidence", rawReference.confidence());
        }
        return ref;
    }

    private static MatchedRawJavadocReference nextRaw(List<RawJavadocReference> rawReferences, String tag,
                                                      String canonicalTarget) {
        if (rawReferences == null || rawReferences.isEmpty()) {
            return MatchedRawJavadocReference.none();
        }
        int fallbackIndex = -1;
        for (int i = 0; i < rawReferences.size(); i++) {
            RawJavadocReference raw = rawReferences.get(i);
            if (!raw.tag().equals(tag)) {
                continue;
            }
            if (rawTargetMatchesCanonical(raw.target(), canonicalTarget)) {
                rawReferences.remove(i);
                return new MatchedRawJavadocReference(Optional.of(raw), "high");
            }
            if (fallbackIndex < 0) {
                fallbackIndex = i;
            }
        }
        if (fallbackIndex >= 0) {
            RawJavadocReference raw = rawReferences.remove(fallbackIndex);
            return new MatchedRawJavadocReference(Optional.of(raw), "low");
        }
        return MatchedRawJavadocReference.none();
    }

    private static boolean rawTargetMatchesCanonical(String rawTarget, String canonicalTarget) {
        String raw = stripModulePrefix(rawTarget == null ? "" : rawTarget.trim());
        String canonical = stripModulePrefix(canonicalTarget == null ? "" : canonicalTarget.trim());
        if (raw.isBlank() || canonical.isBlank()) {
            return false;
        }
        if (raw.equals(canonical)) {
            return true;
        }
        if (raw.startsWith("#") && canonical.contains("#")) {
            return memberReferenceCompatible(raw.substring(1), canonical.substring(canonical.indexOf('#') + 1));
        }
        if (raw.contains("#") && canonical.contains("#")) {
            String rawOwner = raw.substring(0, raw.indexOf('#'));
            String canonicalOwner = canonical.substring(0, canonical.indexOf('#'));
            return simpleTypeName(rawOwner).equals(simpleTypeName(canonicalOwner))
                    && memberReferenceCompatible(raw.substring(raw.indexOf('#') + 1),
                    canonical.substring(canonical.indexOf('#') + 1));
        }
        if (!raw.contains("#") && !canonical.contains("#")) {
            return raw.equals(canonical) || simpleTypeName(raw).equals(simpleTypeName(canonical));
        }
        return false;
    }

    private static boolean memberReferenceCompatible(String rawMember, String canonicalMember) {
        MemberReference raw = parseMemberReference(rawMember);
        MemberReference canonical = parseMemberReference(canonicalMember);
        if (!raw.name().equals(canonical.name())) {
            return false;
        }
        if (!raw.hasParameters()) {
            return true;
        }
        if (!canonical.hasParameters() || raw.parameterTypes().size() != canonical.parameterTypes().size()) {
            return false;
        }
        for (int i = 0; i < raw.parameterTypes().size(); i++) {
            if (!simpleTypeName(raw.parameterTypes().get(i))
                    .equals(simpleTypeName(canonical.parameterTypes().get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> firstReferenceTarget(List<JavadocElement> elements) {
        return elements.stream()
                .filter(JavadocReference.class::isInstance)
                .map(JavadocReference.class::cast)
                .map(reference -> referenceTarget(reference.getReference()))
                .findFirst();
    }

    private static Optional<String> firstReferenceTarget(JavadocInlineTag tag) {
        return firstReferenceTarget(tag.getElements());
    }

    private static String labelText(List<JavadocElement> elements) {
        return elements.stream()
                .dropWhile(element -> element instanceof JavadocReference)
                .filter(JavadocText.class::isInstance)
                .map(JavadocText.class::cast)
                .map(JavadocText::getText)
                .reduce((left, right) -> left + " " + right)
                .orElse("")
                .trim();
    }

    private static String referenceTarget(CtReference reference) {
        if (reference instanceof CtExecutableReference<?> executable) {
            String owner = executable.getDeclaringType() != null
                    ? executable.getDeclaringType().getQualifiedName()
                    : "";
            String params = executable.getParameters().stream()
                    .map(SpoonSourceModelBackend::typeName)
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            return (owner.isBlank() ? "" : owner) + "#" + executable.getSimpleName() + "(" + params + ")";
        }
        if (reference instanceof CtFieldReference<?> field) {
            String owner = field.getDeclaringType() != null
                    ? field.getDeclaringType().getQualifiedName()
                    : "";
            return (owner.isBlank() ? "" : owner) + "#" + field.getSimpleName();
        }
        if (reference instanceof CtTypeReference<?> type) {
            return type.getQualifiedName();
        }
        if (reference instanceof CtPackageReference packageReference) {
            return packageReference.getQualifiedName();
        }
        return reference == null ? "" : reference.toString();
    }

    private static List<Map<String, Object>> fallbackJavadocReferences(ParsedProject parsed, CtType<?> owner, CtExecutable<?> focal, String javadoc) {
        List<Map<String, Object>> references = new ArrayList<>();

        for (RawJavadocReference rawReference : rawJavadocReferences(javadoc)) {
            Map<String, Object> ref = resolveJavadocReference(parsed, owner, focal, rawReference.tag(), rawReference.raw(),
                    rawReference.target(), rawReference.label());
            ref.put("parser", "cocomut-fallback");
            ref.put("parse_confidence", "low");
            references.add(ref);
        }

        return references;
    }

    private static List<RawJavadocReference> rawJavadocReferences(String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<RawJavadocReference> references = new ArrayList<>();

        for (RawBlockTag block : rawBlockTags(javadoc)) {
            if (!"see".equals(block.name())) {
                continue;
            }
            String raw = block.body();
            String[] targetAndLabel = splitReferenceTargetAndLabel(raw);
            references.add(new RawJavadocReference("see", raw, targetAndLabel[0], targetAndLabel[1]));
        }

        for (InlineJavadocReference inline : inlineLinkReferences(javadoc)) {
            references.add(new RawJavadocReference(inline.tag(), inline.raw(), inline.target(), inline.label()));
        }

        return references;
    }

    private static List<InlineJavadocReference> inlineLinkReferences(String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<InlineJavadocReference> references = new ArrayList<>();
        int index = 0;
        while (index < javadoc.length()) {
            int start = javadoc.indexOf("{@", index);
            if (start < 0 || start + 3 >= javadoc.length()) {
                break;
            }
            int tagStart = start + 2;
            int tagEnd = tagStart;
            while (tagEnd < javadoc.length() && Character.isJavaIdentifierPart(javadoc.charAt(tagEnd))) {
                tagEnd++;
            }
            String tag = javadoc.substring(tagStart, tagEnd);
            if (!"link".equals(tag) && !"linkplain".equals(tag)) {
                index = tagEnd;
                continue;
            }
            if (tagEnd >= javadoc.length() || !Character.isWhitespace(javadoc.charAt(tagEnd))) {
                index = tagEnd;
                continue;
            }
            int end = javadoc.indexOf('}', tagEnd);
            if (end < 0) {
                break;
            }
            String raw = javadoc.substring(start, end + 1);
            String body = javadoc.substring(tagEnd, end).trim();
            String[] targetAndLabel = splitReferenceTargetAndLabel(body);
            references.add(new InlineJavadocReference(tag, raw, targetAndLabel[0], targetAndLabel[1]));
            index = end + 1;
        }
        return references;
    }

    private static String[] splitReferenceTargetAndLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[]{"", ""};
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"")) {
            int endQuote = trimmed.indexOf('"', 1);
            if (endQuote > 0) {
                return new String[]{trimmed.substring(0, endQuote + 1),
                        trimmed.substring(endQuote + 1).trim()};
            }
            return new String[]{trimmed, ""};
        }
        if (trimmed.startsWith("<a ") || trimmed.startsWith("<A ")
                || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return new String[]{trimmed, ""};
        }
        int firstWhitespace = -1;
        int parenDepth = 0;
        int angleDepth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (c == '<') {
                angleDepth++;
            } else if (c == '>') {
                angleDepth = Math.max(0, angleDepth - 1);
            } else if (Character.isWhitespace(c) && parenDepth == 0 && angleDepth == 0) {
                firstWhitespace = i;
                break;
            }
        }
        if (firstWhitespace < 0) {
            return new String[]{trimmed, ""};
        }
        return new String[]{
                trimmed.substring(0, firstWhitespace),
                trimmed.substring(firstWhitespace + 1).trim()
        };
    }

    private static Map<String, Object> resolveJavadocReference(ParsedProject parsed, CtType<?> owner, CtExecutable<?> focal,
                                                               String tag, String raw, String target,
                                                               String label) {
        Map<String, Object> ref = baseJavadocReference(tag, raw, target, label);
        if (!resolveTypeParameterReference(owner, focal, target, ref)) {
            resolveStringJavadocReference(parsed, owner, target, ref);
        }
        enrichReferenceTaxonomy(parsed, owner, ref);
        return ref;
    }

    private static Map<String, Object> resolveTypedJavadocReference(ParsedProject parsed, CtType<?> owner, CtExecutable<?> focal,
                                                                    String tag, String raw, String target,
                                                                    String label, CtReference spoonReference) {
        Map<String, Object> ref = baseJavadocReference(tag, raw, target, label);
        if (resolveTypeParameterReference(owner, focal, target, ref)) {
            enrichReferenceTaxonomy(parsed, owner, ref);
            return ref;
        }
        if (spoonReference instanceof CtExecutableReference<?> executable) {
            resolveExecutableJavadocReference(parsed, owner, executable, ref);
        } else if (spoonReference instanceof CtFieldReference<?> field) {
            resolveFieldJavadocReference(parsed, owner, field, ref);
        } else if (spoonReference instanceof CtTypeReference<?>) {
            // Typed guesses must not bypass source-level shadowing or ambiguity.
            resolveTypeReference(parsed, owner, target, ref);
        } else if (spoonReference instanceof CtPackageReference packageReference) {
            ref.put("kind", "type_reference");
            ref.put("resolution", "external_symbol");
            ref.put("external_type", packageReference.getQualifiedName());
            ref.put("external_resolution", "package_reference");
        } else {
            resolveStringJavadocReference(parsed, owner, target, ref);
        }
        enrichReferenceTaxonomy(parsed, owner, ref);
        return ref;
    }

    private static boolean resolveTypeParameterReference(CtType<?> owner, CtExecutable<?> focal,
                                                          String target, Map<String, Object> ref) {
        String spelling = normalizeJavadocTypeSpelling(target);
        String leading = spelling.split("[.#]", 2)[0];
        boolean methodParameter = focal instanceof CtFormalTypeDeclarer declarer
                && declarer.getFormalCtTypeParameters().stream()
                .anyMatch(parameter -> leading.equals(parameter.getSimpleName()));
        var lexical = scopedReferenceTypeName(owner, leading);
        boolean typeParameter = lexical.status() == InheritedJavadocResolver.TypeNameResolutionStatus.RESOLVED
                && lexical.canonicalName().startsWith("type-parameter:");
        if (!methodParameter && !typeParameter) {
            return false;
        }
        ref.put("kind", spelling.contains("#") ? "member_reference" : "type_reference");
        ref.put("resolution", "unresolved");
        ref.put("unresolved_reason", "lexical_type_parameter");
        return true;
    }

    private static Map<String, Object> baseJavadocReference(String tag, String raw, String target, String label) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("tag", tag);
        ref.put("raw", raw);
        ref.put("target", target);
        ref.put("label", label == null ? "" : label);
        return ref;
    }

    private static void resolveStringJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                      String target, Map<String, Object> ref) {
        if (target.startsWith("\"")) {
            ref.put("kind", "text_reference");
            ref.put("text", target.replaceAll("^\"|\"$", ""));
            ref.put("resolution", "text");
            return;
        }

        Matcher anchor = ANCHOR_HREF.matcher(target);
        if (anchor.find()) {
            ref.put("kind", "external_url");
            ref.put("url", anchor.group(1).trim());
            ref.put("label", anchor.group(2).replaceAll("\\s+", " ").trim());
            ref.put("resolution", "external");
            return;
        }

        if (target.startsWith("http://") || target.startsWith("https://")) {
            ref.put("kind", "external_url");
            ref.put("url", target);
            ref.put("resolution", "external");
            return;
        }

        String cleaned = stripModulePrefix(target.replaceAll("#$", "").trim());
        if (cleaned.isBlank()) {
            ref.put("kind", "unknown");
            ref.put("resolution", "empty_target");
            return;
        }

        if (cleaned.contains("#")) {
            resolveMemberReference(parsed, owner, cleaned, ref);
        } else {
            resolveTypeReference(parsed, owner, cleaned, ref);
        }
    }

    private static void resolveExecutableJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                          CtExecutableReference<?> executable,
                                                          Map<String, Object> ref) {
        String typeName = projectTypeName(parsed, owner, executable.getDeclaringType());
        String memberName = executable.getSimpleName();
        if ("<init>".equals(memberName) && !typeName.isBlank()) {
            memberName = simpleTypeName(typeName);
        }
        List<String> parameterTypes = executable.getParameters().stream()
                .map(SpoonSourceModelBackend::typeName)
                .toList();
        MemberReference memberReference = typedMemberReference(memberName, parameterTypes, stringValue(ref.get("target")));
        ref.put("kind", "member_reference");
        ref.put("resolved_type", typeName);
        ref.put("referenced_member", memberReference.hasParameters()
                ? memberName + "(" + String.join(",", parameterTypes) + ")"
                : memberName);
        boolean inheritedRelativeReference = inheritedRelativeReference(owner, typeName, stringValue(ref.get("target")));

        if (!typeName.isBlank()) {
            if (resolveMethodCandidate(parsed, typeName, memberReference, inheritedRelativeReference, ref)) {
                return;
            }
            if (!inheritedRelativeReference && resolveInheritedMemberCandidate(parsed, typeName, memberReference, ref)) {
                return;
            }
            ref.put("resolution", "type_resolved_member_unresolved");
            return;
        }

        String externalType = externalTypeName(executable.getDeclaringType());
        if (!externalType.isBlank()) {
            resolveExternalMemberReference(parsed, owner, externalType, memberReference,
                    stringValue(ref.get("referenced_member")), ref);
            return;
        }
        resolveStringJavadocReference(parsed, owner, stringValue(ref.get("target")), ref);
    }

    private static MemberReference typedMemberReference(String memberName, List<String> spoonParameterTypes,
                                                        String sourceTarget) {
        String target = sourceTarget == null ? "" : sourceTarget.trim();
        int hash = target.indexOf('#');
        if (hash >= 0 && hash + 1 < target.length()) {
            MemberReference sourceMember = parseMemberReference(target.substring(hash + 1));
            if (!sourceMember.name().isBlank() && !sourceMember.hasParameters()) {
                return sourceMember;
            }
        }
        return new MemberReference(memberName, true, spoonParameterTypes);
    }

    private static void resolveFieldJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                     CtFieldReference<?> field,
                                                     Map<String, Object> ref) {
        String typeName = projectTypeName(parsed, owner, field.getDeclaringType());
        MemberReference memberReference = new MemberReference(field.getSimpleName(), false, List.of());
        ref.put("kind", "field_reference");
        ref.put("resolved_type", typeName);
        ref.put("referenced_member", field.getSimpleName());
        boolean inheritedRelativeReference = inheritedRelativeReference(owner, typeName, stringValue(ref.get("target")));

        if (!typeName.isBlank()) {
            if (resolveFieldCandidate(parsed, typeName, memberReference, inheritedRelativeReference, ref)) {
                return;
            }
            if (!inheritedRelativeReference && resolveInheritedMemberCandidate(parsed, typeName, memberReference, ref)) {
                return;
            }
            ref.put("resolution", "type_resolved_member_unresolved");
            return;
        }

        String externalType = externalTypeName(field.getDeclaringType());
        if (!externalType.isBlank()) {
            resolveExternalMemberReference(parsed, owner, externalType, memberReference,
                    field.getSimpleName(), ref);
            return;
        }
        resolveStringJavadocReference(parsed, owner, stringValue(ref.get("target")), ref);
    }

    private static boolean inheritedRelativeReference(CtType<?> owner, String typeName, String sourceTarget) {
        if (owner == null || typeName == null || typeName.isBlank() || sourceTarget == null) {
            return false;
        }
        return sourceTarget.trim().startsWith("#") && !owner.getQualifiedName().equals(typeName);
    }

    private static void enrichReferenceTaxonomy(ParsedProject parsed, CtType<?> owner, Map<String, Object> ref) {
        ref.put("reference_target_kind", referenceTargetKind(ref));
        ref.put("reference_domain", referenceDomain(ref));
        ref.put("reference_scope", referenceScope(parsed, owner, ref));
    }

    private static String referenceTargetKind(Map<String, Object> ref) {
        String kind = stringValue(ref.get("kind"));
        String resolution = stringValue(ref.get("resolution"));
        if ("resolved_method".equals(resolution) || "resolved_inherited_method".equals(resolution)
                || "overload_ambiguous".equals(resolution)) {
            return "method";
        }
        if ("resolved_field".equals(resolution) || "resolved_inherited_field".equals(resolution)
                || "ambiguous_field".equals(resolution)) {
            return "field";
        }
        if ("resolved_type".equals(resolution)) {
            return "type";
        }
        if ("member_reference".equals(kind)) {
            String memberKind = stringValue(ref.get("external_member_kind"));
            return memberKind.isBlank() || "unknown".equals(memberKind) ? "method_or_field" : memberKind;
        }
        if ("field_reference".equals(kind)) {
            return "field";
        }
        if ("type_reference".equals(kind)) {
            return "type";
        }
        if ("external_url".equals(kind)) {
            return "url";
        }
        if ("text_reference".equals(kind)) {
            return "text";
        }
        return "unknown";
    }

    private static String referenceDomain(Map<String, Object> ref) {
        String kind = stringValue(ref.get("kind"));
        String resolution = stringValue(ref.get("resolution"));
        if ("external_url".equals(kind)) {
            return "external_web";
        }
        if ("text_reference".equals(kind)) {
            return "text";
        }
        if ("external_symbol".equals(resolution)) {
            String externalType = stringValue(ref.get("external_type"));
            if (externalType.startsWith("java.") || externalType.startsWith("javax.")) {
                return "external_jdk";
            }
            return "external_library";
        }
        if (stringValue(ref.get("method_uri")).isBlank()
                && stringValue(ref.get("field_uri")).isBlank()
                && stringValue(ref.get("type_uri")).isBlank()) {
            return "unresolved";
        }
        return "project";
    }

    private static String referenceScope(ParsedProject parsed, CtType<?> owner, Map<String, Object> ref) {
        String domain = referenceDomain(ref);
        if ("external_web".equals(domain) || domain.startsWith("external_")) {
            return "external";
        }
        if ("text".equals(domain)) {
            return "text";
        }
        if (!"project".equals(domain)) {
            return "unknown";
        }

        String ownerType = owner != null ? owner.getQualifiedName() : "";
        String targetType = referencedProjectType(parsed, ref);
        if (ownerType.isBlank() || targetType.isBlank()) {
            return "unknown";
        }
        if (ownerType.equals(targetType)) {
            return "same_type";
        }

        String ownerPackage = packageName(ownerType);
        String targetPackage = packageName(targetType);
        if (!ownerPackage.isBlank() && ownerPackage.equals(targetPackage)) {
            return "same_package";
        }
        return "same_module";
    }

    private static String referencedProjectType(ParsedProject parsed, Map<String, Object> ref) {
        String inheritedFrom = stringValue(ref.get("inherited_from"));
        if (!inheritedFrom.isBlank()) {
            return inheritedFrom;
        }
        String resolvedType = stringValue(ref.get("resolved_type"));
        if (!resolvedType.isBlank()) {
            return resolvedType;
        }
        String methodUri = stringValue(ref.get("method_uri"));
        if (!methodUri.isBlank()) {
            SourceMethod method = parsed.methodsByUri().get(methodUri);
            if (method != null) {
                return method.typeName();
            }
        }
        String fieldUri = stringValue(ref.get("field_uri"));
        if (!fieldUri.isBlank()) {
            return parsed.fieldsByTypeName().values().stream()
                    .flatMap(List::stream)
                    .filter(field -> field.fieldUri().equals(fieldUri))
                    .map(SourceField::typeName)
                    .findFirst()
                    .orElse("");
        }
        String typeUri = stringValue(ref.get("type_uri"));
        if (!typeUri.isBlank()) {
            int hash = typeUri.indexOf('#');
            return hash >= 0 && hash + 1 < typeUri.length() ? typeUri.substring(hash + 1) : "";
        }
        return "";
    }

    private static String packageName(String qualifiedTypeName) {
        if (qualifiedTypeName == null || qualifiedTypeName.isBlank()) {
            return "";
        }
        int nested = qualifiedTypeName.indexOf('$');
        String value = nested >= 0 ? qualifiedTypeName.substring(0, nested) : qualifiedTypeName;
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void resolveMemberReference(ParsedProject parsed, CtType<?> owner,
                                               String target, Map<String, Object> ref) {
        int hash = target.indexOf('#');
        String rawType = stripModulePrefix(target.substring(0, hash).trim());
        String member = target.substring(hash + 1).trim();
        String typeName = resolveTypeName(parsed, owner, rawType);
        MemberReference memberReference = parseMemberReference(member);

        ref.put("kind", "member_reference");
        ref.put("resolved_type", typeName);
        ref.put("referenced_member", member);

        if (typeName.isBlank()) {
            resolveExternalMemberReference(parsed, owner, rawType, memberReference, member, ref);
            return;
        }

        if (memberReference.name().isBlank()) {
            ref.put("resolution", "unresolved");
            return;
        }

        if (resolveMethodCandidate(parsed, typeName, memberReference, false, ref)) {
            return;
        }
        if (resolveFieldCandidate(parsed, typeName, memberReference, false, ref)) {
            return;
        }
        if (resolveInheritedMemberCandidate(parsed, typeName, memberReference, ref)) {
            return;
        }

        ref.put("resolution", parsed.typesByQualifiedName().containsKey(typeName)
                ? "type_resolved_member_unresolved"
                : (isExternalReference(rawType) ? "external_symbol" : "unresolved"));
        if (!parsed.typesByQualifiedName().containsKey(typeName)) {
            resolveExternalMemberReference(parsed, owner, rawType, memberReference, member, ref);
        }
    }

    private static void resolveTypeReference(ParsedProject parsed, CtType<?> owner,
                                             String target, Map<String, Object> ref) {
        target = stripModulePrefix(target);
        String typeName = resolveTypeName(parsed, owner, target);
        if (!typeName.isBlank()) {
            ref.put("kind", "type_reference");
            ref.put("resolution", "resolved_type");
            ref.put("resolved_type", typeName);
            putTypeDetails(parsed, typeName, ref);
        } else {
            ExternalType external = resolveExternalType(parsed, owner, target);
            ref.put("kind", "type_reference");
            ref.put("resolution", external.resolved() ? "external_symbol" : "unresolved");
            ref.put("external_type", external.qualifiedName());
            ref.put("external_resolution", external.confidence());
        }
    }

    private static void resolveExternalMemberReference(ParsedProject parsed, CtType<?> owner,
                                                       String rawType, MemberReference memberReference,
                                                       String rawMember, Map<String, Object> ref) {
        ExternalType external = resolveExternalType(parsed, owner, rawType);
        ref.put("external_type", external.qualifiedName());
        ref.put("external_member", rawMember);
        ref.put("external_resolution", external.confidence());
        if (!external.resolved()) {
            ref.put("resolution", "unresolved");
            return;
        }

        ExternalMember member = classifyExternalMember(parsed, external.qualifiedName(), memberReference);
        ref.put("kind", member.kind());
        ref.put("external_member_kind", member.memberKind());
        ref.put("external_member_resolution", member.confidence());
        ref.put("resolution", "unknown".equals(member.memberKind()) ? "unresolved" : "external_symbol");
    }

    private static ExternalType resolveExternalType(ParsedProject parsed, CtType<?> owner, String rawType) {
        String target = rawType == null ? "" : rawType.trim().replaceAll("<.*>", "");
        if (target.isBlank()) {
            return ExternalType.unresolved(rawType);
        }
        String[] parts = target.replace('$', '.').split("\\.", 2);
        String leading = parts[0];
        String suffix = parts.length == 2 ? "." + parts[1] : "";
        var lexical = scopedReferenceTypeName(owner, leading);
        if (lexical.status() != InheritedJavadocResolver.TypeNameResolutionStatus.UNRESOLVED) {
            if (lexical.status() == InheritedJavadocResolver.TypeNameResolutionStatus.AMBIGUOUS
                    || lexical.canonicalName().startsWith("type-parameter:")
                    || parsed.typesByQualifiedName().containsKey(lexical.canonicalName())) {
                return ExternalType.unresolved(target);
            }
            return externalMemberPath(parsed, lexical.canonicalName(), suffix, "lexical_external_type");
        }
        ImportContext imports = importContext(parsed, owner);
        String explicit = imports.explicit().get(leading);
        if (explicit != null) {
            return externalMemberPath(parsed, explicit, suffix, "explicit_import");
        }
        String ownerPackage = owner != null && owner.getPackage() != null
                ? owner.getPackage().getQualifiedName() : "";
        String samePackage = ownerPackage.isBlank() ? leading : ownerPackage + "." + leading;
        if (!qualifiedReferenceTypeName(parsed, samePackage).isBlank()) {
            return ExternalType.unresolved(target);
        }
        Class<?> local = externalClass(parsed, samePackage);
        if (local != null) {
            return externalMemberPath(parsed, local.getName(), suffix, "same_package_symbol");
        }
        // Resolve the visible outer first, including ambiguity across on-demand imports.
        Set<String> candidates = new LinkedHashSet<>();
        for (String prefix : java.util.stream.Stream.concat(
                java.util.stream.Stream.of("java.lang"), imports.wildcard().stream()).toList()) {
            String name = prefix + "." + leading;
            String project = qualifiedReferenceTypeName(parsed, name);
            Class<?> external = externalClass(parsed, name);
            if (!project.isBlank()) candidates.add(project);
            else if (external != null) candidates.add(external.getName());
        }
        if (!candidates.isEmpty()) {
            if (candidates.size() != 1) return ExternalType.unresolved(target);
            String name = candidates.iterator().next();
            return parsed.typesByQualifiedName().containsKey(name) ? ExternalType.unresolved(target)
                    : externalMemberPath(parsed, name, suffix, name.equals("java.lang." + leading)
                            ? "implicit_java_lang" : "wildcard_import_symbol");
        }
        Class<?> qualified = externalClass(parsed, target);
        if (qualified != null) return ExternalType.resolved(qualified.getName(), "qualified_symbol");
        // Retain the existing bare-name JDK convenience lookup, but do not use
        // it to invent visibility for a qualified outer/member path.
        if (suffix.isBlank()) {
            for (String prefix : COMMON_JDK_PACKAGES) {
                Class<?> candidate = externalClass(parsed, prefix + "." + target);
                if (candidate != null) candidates.add(candidate.getName());
            }
            if (candidates.size() == 1) {
                return ExternalType.resolved(candidates.iterator().next(), "common_jdk_probe");
            }
        }
        return ExternalType.unresolved(rawType);
    }

    private static ExternalType externalMemberPath(ParsedProject parsed, String base, String suffix,
                                                    String confidence) {
        Class<?> type = externalClass(parsed, base);
        if (type != null && !suffix.isBlank()) type = externalMemberPath(type, suffix.substring(1));
        return type == null ? ExternalType.unresolved(base + suffix)
                : ExternalType.resolved(type.getName(), confidence);
    }

    private static Class<?> externalMemberPath(Class<?> type, String path) {
        for (String segment : path.split("\\.", -1)) {
            Set<Class<?>> members = new LinkedHashSet<>();
            // Reflection exposes the actual declaring class for inherited members.
            for (Class<?> member : type.getClasses()) {
                if (segment.equals(member.getSimpleName())) members.add(member);
            }
            // A direct declaration hides inherited names.
            for (Class<?> member : type.getDeclaredClasses()) {
                if (segment.equals(member.getSimpleName())) {
                    if (!java.lang.reflect.Modifier.isPublic(member.getModifiers())) return null;
                    members.clear();
                    members.add(member);
                    break;
                }
            }
            if (members.size() != 1) return null;
            type = members.iterator().next();
        }
        return type;
    }

    private static Class<?> externalClass(ParsedProject parsed, String spelling) {
        try {
            // Locate a real package-qualified outer, then traverse declared members;
            // blindly changing dots to dollars can accept nonexistent source paths.
            try { return classForName(parsed, spelling); }
            catch (ClassNotFoundException ignored) { }
            for (int dot = spelling.indexOf('.'); dot >= 0; dot = spelling.indexOf('.', dot + 1)) {
                String prefix = spelling.substring(0, dot);
                if (!prefix.contains(".")) continue;
                Class<?> outer;
                try { outer = classForName(parsed, prefix); }
                catch (ClassNotFoundException ignored) { continue; }
                return externalMemberPath(outer, spelling.substring(dot + 1));
            }
        } catch (LinkageError | SecurityException failure) {
            ResourceFailures.rethrowIfPresent(failure);
        }
        return null;
    }

    private static String stripModulePrefix(String target) {
        if (target == null) {
            return "";
        }
        String value = target.trim();
        int slash = value.indexOf('/');
        if (slash > 0 && slash + 1 < value.length()) {
            return value.substring(slash + 1).trim();
        }
        return value;
    }

    private static ImportContext importContext(ParsedProject parsed, CtType<?> owner) {
        Optional<Path> source = sourceFile(owner);
        return source.map(path -> parsed.importsByFile().getOrDefault(path, ImportContext.empty()))
                .orElseGet(ImportContext::empty);
    }

    private static ExternalMember classifyExternalMember(ParsedProject parsed, String typeName,
                                                         MemberReference memberReference) {
        if (typeName == null || typeName.isBlank() || memberReference.name().isBlank()) {
            return new ExternalMember("member_reference", "unknown", "unresolved");
        }
        try {
            Class<?> clazz = classForName(parsed, typeName);
            if (!memberReference.hasParameters()) {
                for (java.lang.reflect.Field field : clazz.getFields()) {
                    if (field.getName().equals(memberReference.name())) {
                        return new ExternalMember("field_reference", "field", "reflection_public_field");
                    }
                }
                for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                    if (field.getName().equals(memberReference.name())) {
                        return new ExternalMember("field_reference", "field", "reflection_declared_field");
                    }
                }
            }
            for (java.lang.reflect.Method method : clazz.getMethods()) {
                if (method.getName().equals(memberReference.name())
                        && externalParametersMatch(method.getParameterTypes(), memberReference)) {
                    return new ExternalMember("member_reference", "method", "reflection_public_method");
                }
            }
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(memberReference.name())
                        && externalParametersMatch(method.getParameterTypes(), memberReference)) {
                    return new ExternalMember("member_reference", "method", "reflection_declared_method");
                }
            }
        } catch (Throwable ignored) {
            // Reflection is used only to classify external symbols. Source
            // extraction remains valid when symbols cannot be loaded.
        }
        return new ExternalMember("member_reference", "unknown", "symbol_only");
    }

    private static boolean externalParametersMatch(Class<?>[] parameterTypes, MemberReference memberReference) {
        if (!memberReference.hasParameters()) {
            return true;
        }
        if (parameterTypes.length != memberReference.parameterTypes().size()) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            String reference = normalizeTypeForMatch(memberReference.parameterTypes().get(i));
            String binary = normalizeTypeForMatch(reflectionTypeName(parameterTypes[i]));
            String simple = normalizeTypeForMatch(simpleTypeName(reflectionTypeName(parameterTypes[i])));
            if (!reference.equals(binary) && !reference.equals(simple)) {
                return false;
            }
        }
        return true;
    }

    private static String reflectionTypeName(Class<?> type) {
        if (type.isArray()) {
            return reflectionTypeName(type.getComponentType()) + "[]";
        }
        String name = type.getCanonicalName();
        return name == null ? type.getName().replace('$', '.') : name;
    }

    private static Class<?> classForName(ParsedProject parsed, String typeName) throws ClassNotFoundException {
        ClassLoader loader = parsed != null && parsed.projectClassLoader() != null
                ? parsed.projectClassLoader()
                : ClassLoader.getSystemClassLoader();
        return Class.forName(typeName, false, loader);
    }

    private static boolean classExists(ParsedProject parsed, String typeName) {
        try {
            classForName(parsed, typeName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ClassLoader projectClassLoader(ProjectModel project) {
        try {
            List<URL> urls = new ArrayList<>();
            java.util.stream.Stream.concat(project.classOutputDirs().stream(), project.dependencyClasspath().stream())
                    .distinct()
                    .filter(Files::exists)
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .forEach(urls::add);
            if (!urls.isEmpty()) {
                return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getSystemClassLoader());
            }
        } catch (Exception ignored) {
            // External Javadoc resolution remains best-effort.
        }
        return ClassLoader.getSystemClassLoader();
    }

    private static boolean resolveMethodCandidate(ParsedProject parsed, String typeName,
                                                  MemberReference memberReference, boolean inherited,
                                                  Map<String, Object> ref) {
        List<SourceMethod> nameMatches = parsed.methodsByTypeName().getOrDefault(typeName, List.of()).stream()
                .filter(method -> method.methodName().equals(memberReference.name()))
                .toList();
        if (nameMatches.isEmpty()) {
            return false;
        }

        List<SourceMethod> candidates = nameMatches.stream()
                .filter(method -> memberReference.matches(method))
                .toList();
        if (candidates.size() == 1) {
            SourceMethod method = candidates.get(0);
            ref.put("resolution", inherited ? "resolved_inherited_method" : "resolved_method");
            ref.put("method_uri", method.methodUri());
            ref.put("signature", method.typeName() + "." + method.signature());
            ref.put("source_set", method.sourceSet());
            ref.put("referenced_method", referencedMethodContext(parsed, method));
            if (inherited) {
                ref.put("inherited_from", typeName);
            }
            return true;
        }
        if (candidates.size() > 1 || (!memberReference.hasParameters() && nameMatches.size() > 1)) {
            List<SourceMethod> ambiguous = candidates.isEmpty() ? nameMatches : candidates;
            ref.put("resolution", "overload_ambiguous");
            ref.put("ambiguity_reason", memberReference.hasParameters()
                    ? "explicit_parameter_types_match_multiple_overloads"
                    : "target_omits_parameter_types");
            ref.put("candidate_method_uris", ambiguous.stream()
                    .map(SourceMethod::methodUri)
                    .limit(20)
                    .toList());
            if (inherited) {
                ref.put("inherited_from", typeName);
            }
            return true;
        }
        return false;
    }

    private static boolean resolveFieldCandidate(ParsedProject parsed, String typeName,
                                                 MemberReference memberReference, boolean inherited,
                                                 Map<String, Object> ref) {
        if (memberReference.hasParameters()) {
            return false;
        }
        List<SourceField> fields = parsed.fieldsByTypeName().getOrDefault(typeName, List.of()).stream()
                .filter(field -> field.fieldName().equals(memberReference.name()))
                .toList();
        if (fields.isEmpty()) {
            return false;
        }
        if (fields.size() == 1) {
            SourceField field = fields.get(0);
            ref.put("kind", "field_reference");
            ref.put("resolution", inherited ? "resolved_inherited_field" : "resolved_field");
            ref.put("field_uri", field.fieldUri());
            ref.put("field_name", field.fieldName());
            ref.put("field_type", field.type());
            ref.put("field_erased_type", field.erasedType());
            ref.put("field_modifiers", field.modifiers());
            ref.put("source_set", field.sourceSet());
            ref.put("field_javadoc", field.javadoc());
            if (inherited) {
                ref.put("inherited_from", typeName);
            }
            return true;
        }
        ref.put("kind", "field_reference");
        ref.put("resolution", "ambiguous_field");
        ref.put("candidate_field_uris", fields.stream()
                .map(SourceField::fieldUri)
                .limit(20)
                .toList());
        if (inherited) {
            ref.put("inherited_from", typeName);
        }
        return true;
    }

    private static boolean resolveInheritedMemberCandidate(ParsedProject parsed, String typeName,
                                                           MemberReference memberReference,
                                                           Map<String, Object> ref) {
        for (String parent : localSupertypes(parsed, typeName)) {
            if (resolveMethodCandidate(parsed, parent, memberReference, true, ref)) {
                return true;
            }
            if (resolveFieldCandidate(parsed, parent, memberReference, true, ref)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> localSupertypes(ParsedProject parsed, String typeName) {
        CtType<?> type = parsed.typesByQualifiedName().get(typeName);
        if (type == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        collectLocalSupertypes(parsed, type, result, new LinkedHashSet<>());
        return result;
    }

    private static void collectLocalSupertypes(ParsedProject parsed, CtType<?> type,
                                               List<String> result, Set<String> seen) {
        if (type == null) {
            return;
        }
        seen.add(type.getQualifiedName());
        List<CtTypeReference<?>> refs = new ArrayList<>();
        if (type.getSuperclass() != null) {
            refs.add(type.getSuperclass());
        }
        refs.addAll(type.getSuperInterfaces());
        for (CtTypeReference<?> ref : refs) {
            String parentName = resolveTypeName(parsed, type, typeName(ref));
            if (parentName.isBlank() || !seen.add(parentName)) {
                continue;
            }
            result.add(parentName);
            CtType<?> parentType = parsed.typesByQualifiedName().get(parentName);
            if (parentType != null) {
                List<CtTypeReference<?>> parentRefs = new ArrayList<>();
                if (parentType.getSuperclass() != null) {
                    parentRefs.add(parentType.getSuperclass());
                }
                parentRefs.addAll(parentType.getSuperInterfaces());
                for (CtTypeReference<?> parentRef : parentRefs) {
                    String ancestorName = resolveTypeName(parsed, parentType, typeName(parentRef));
                    if (!ancestorName.isBlank() && seen.add(ancestorName)) {
                        result.add(ancestorName);
                        collectLocalSupertypes(parsed, parsed.typesByQualifiedName().get(ancestorName), result, seen);
                    }
                }
            }
        }
    }

    private static void putTypeDetails(ParsedProject parsed, String typeName, Map<String, Object> ref) {
        CtType<?> type = parsed.typesByQualifiedName().get(typeName);
        ref.put("type_uri", typeUri(parsed.projectRoot(), type));
        ref.put("source_path", sourcePath(type));
        ref.put("line_number", lineNumber(type));
        ref.put("type_javadoc", docComment(type));
        ref.put("type_hierarchy", type != null ? typeHierarchy(type) : "");
        ref.put("hierarchy_resolution", hierarchyResolution(type));
    }

    private static Map<String, Object> referencedMethodContext(ParsedProject parsed, SourceMethod method) {
        Map<String, Object> details = new LinkedHashMap<>();
        CtExecutable<?> executable = parsed.executablesByUri().get(method.methodUri());
        CtType<?> owner = executable != null ? executable.getParent(CtType.class) : null;
        details.put("method_uri", method.methodUri());
        details.put("method_name", method.methodName());
        details.put("qualified_name", method.typeName() + "." + method.methodName());
        details.put("signature", method.typeName() + "." + method.signature());
        details.put("source_set", method.sourceSet());
        details.put("line_number", method.lineNumber());
        details.put("visibility", method.visibility());
        details.put("static", method.isStatic());
        details.put("constructor", method.constructor());
        details.put("return_type", method.returnType());
        details.put("erased_return_type", method.erasedReturnType());
        details.put("parameters", method.parameters().stream()
                .map(SpoonSourceModelBackend::parameterContext)
                .toList());
        details.put("annotations", method.annotations());
        details.put("throws", method.thrownExceptions());
        details.put("code", executable != null ? sourceSlice(executable) : "");
        details.put("javadoc", executable != null ? docComment(executable) : "");
        details.put("type_javadoc", owner != null ? docComment(owner) : "");
        return details;
    }

    private static Map<String, Object> parameterContext(SourceParameter parameter) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", parameter.name());
        details.put("type", parameter.type());
        details.put("erased_type", parameter.erasedType());
        details.put("modifiers", parameter.modifiers());
        details.put("annotations", parameter.annotations());
        return details;
    }

    // Finish each class scope (including inherited members) before looking
    // outward. An enclosing type parameter cannot shadow a nearer member type.
    private static InheritedJavadocResolver.TypeNameResolution scopedReferenceTypeName(
            CtType<?> owner, String leading) {
        for (CtType<?> scope : enclosingTypes(owner)) {
            if (leading.equals(scope.getSimpleName())) {
                return InheritedJavadocResolver.TypeNameResolution.resolved(scope.getQualifiedName());
            }
            CtType<?> declared = scope.getNestedType(leading);
            if (declared != null) {
                return InheritedJavadocResolver.TypeNameResolution.resolved(declared.getQualifiedName());
            }
            if (scope.getFormalCtTypeParameters().stream()
                    .anyMatch(parameter -> leading.equals(parameter.getSimpleName()))) {
                return InheritedJavadocResolver.TypeNameResolution.resolved("type-parameter:" + leading);
            }
            Set<String> inherited = inheritedMemberTypeNames(scope, leading);
            if (inherited.size() == 1) {
                return InheritedJavadocResolver.TypeNameResolution.resolved(inherited.iterator().next());
            }
            if (inherited.size() > 1) {
                return InheritedJavadocResolver.TypeNameResolution.ambiguous();
            }
        }
        return InheritedJavadocResolver.TypeNameResolution.unresolved();
    }

    private static String memberTypePath(ParsedProject parsed, String base, String suffix) {
        if (base.isBlank()) {
            return "";
        }
        String current = base;
        if (suffix.isBlank()) {
            return projectNestedTypeName(parsed, current);
        }
        for (String segment : suffix.substring(1).split("\\.", -1)) {
            CtType<?> declaration = parsed.typesByQualifiedName().get(current);
            if (declaration == null || segment.isBlank()) {
                return "";
            }
            CtType<?> direct = declaration.getNestedType(segment);
            if (direct != null) {
                current = direct.getQualifiedName();
            } else {
                Set<String> inherited = inheritedMemberTypeNames(declaration, segment);
                if (inherited.size() != 1) {
                    return "";
                }
                current = inherited.iterator().next();
            }
        }
        return projectNestedTypeName(parsed, current);
    }

    private static String qualifiedReferenceTypeName(ParsedProject parsed, String spelling) {
        String sourceName = spelling.replace('$', '.');
        // Identify the package-qualified outer declaration, then resolve each
        // member segment semantically; inherited members keep their true owner.
        for (int separator = sourceName.indexOf('.'); separator >= 0;
             separator = sourceName.indexOf('.', separator + 1)) {
            String prefix = sourceName.substring(0, separator);
            // A bare prefix would be an unnamed-package type, not a
            // package-qualified declaration visible from this source scope.
            if (!prefix.contains(".")) {
                continue;
            }
            String base = projectNestedTypeName(parsed, prefix);
            CtType<?> declaration = parsed.typesByQualifiedName().get(base);
            // A dotted prefix can itself be a nested unnamed-package type.
            // Check the declaration's package before accepting any prefix.
            if (declaration != null && declaration.getPackage() != null
                    && !declaration.getPackage().getQualifiedName().isBlank()) {
                return memberTypePath(parsed, base, sourceName.substring(separator));
            }
        }
        String canonical = projectNestedTypeName(parsed, sourceName);
        CtType<?> declaration = parsed.typesByQualifiedName().get(canonical);
        if (sourceName.contains(".") && declaration != null
                && (declaration.getPackage() == null || declaration.getPackage().getQualifiedName().isBlank())) {
            return "";
        }
        return canonical;
    }

    private static String resolveTypeName(ParsedProject parsed, CtType<?> owner, String rawType) {
        String target = rawType == null ? "" : rawType.trim();
        if (target.isBlank()) {
            return owner != null ? owner.getQualifiedName() : "";
        }
        target = target.replaceAll("<.*>", "");
        if (owner == null) {
            return target.contains(".") ? qualifiedReferenceTypeName(parsed, target) : "";
        }
        // Resolve the outer source name first. Only then map member-type
        // suffixes to canonical binary identities; never search by simple name.
        String[] parts = target.replace('$', '.').split("\\.", 2);
        String leading = parts[0];
        String suffix = parts.length == 2 ? "." + parts[1] : "";
        var lexical = scopedReferenceTypeName(owner, leading);
        if (lexical.status() == InheritedJavadocResolver.TypeNameResolutionStatus.RESOLVED) {
            return lexical.canonicalName().startsWith("type-parameter:") ? ""
                    : memberTypePath(parsed, lexical.canonicalName(), suffix);
        }
        if (lexical.status() == InheritedJavadocResolver.TypeNameResolutionStatus.AMBIGUOUS) {
            return "";
        }
        ImportContext imports = importContext(parsed, owner);
        String explicit = imports.explicit().get(leading);
        if (explicit != null) {
            return memberTypePath(parsed, qualifiedReferenceTypeName(parsed, explicit), suffix);
        }
        String ownerPackage = owner.getPackage() != null ? owner.getPackage().getQualifiedName() : "";
        String samePackage = ownerPackage.isBlank() ? leading : ownerPackage + "." + leading;
        String resolved = qualifiedReferenceTypeName(parsed, samePackage);
        if (!resolved.isBlank()) {
            return memberTypePath(parsed, resolved, suffix);
        }
        Set<String> onDemand = new LinkedHashSet<>();
        String javaLang = "java.lang." + leading;
        for (String wildcard : imports.wildcard()) {
            String candidate = qualifiedReferenceTypeName(parsed, wildcard + "." + leading);
            if (!candidate.isBlank()) {
                onDemand.add(candidate);
            }
        }
        if (classExists(parsed, javaLang)) {
            onDemand.add(javaLang);
        }
        if (!onDemand.isEmpty()) {
            return onDemand.size() == 1
                    ? memberTypePath(parsed, onDemand.iterator().next(), suffix) : "";
        }
        // A fully qualified spelling is valid without an import. Bare names
        // are deliberately excluded, including unnamed-package pseudo-types.
        return target.contains(".") ? qualifiedReferenceTypeName(parsed, target) : "";
    }

    private static String projectTypeName(ParsedProject parsed, CtType<?> owner, CtTypeReference<?> type) {
        if (type == null) {
            return owner != null ? owner.getQualifiedName() : "";
        }
        try {
            CtType<?> declaration = type.getDeclaration();
            if (declaration != null && !(declaration instanceof CtTypeParameter) && declaration.getQualifiedName() != null
                    && parsed.typesByQualifiedName().containsKey(declaration.getQualifiedName())) {
                return declaration.getQualifiedName();
            }
        } catch (Exception | StackOverflowError ignored) {
            // Fall back to textual names below.
        }

        List<String> candidates = new ArrayList<>();
        String qualified = type.getQualifiedName();
        if (qualified != null && !qualified.isBlank()) {
            candidates.add(qualified);
        }
        String rendered = typeName(type);
        if (rendered != null && !rendered.isBlank()) {
            candidates.add(rendered);
        }
        String simple = type.getSimpleName();
        if (simple != null && !simple.isBlank()) {
            candidates.add(simple);
        }

        for (String candidate : candidates) {
            String normalized = stripModulePrefix(candidate);
            if (parsed.typesByQualifiedName().containsKey(normalized)) {
                return normalized;
            }
            String resolved = resolveTypeName(parsed, owner, normalized);
            if (!resolved.isBlank()) {
                return resolved;
            }
            String nested = projectNestedTypeName(parsed, normalized);
            if (!nested.isBlank()) {
                return nested;
            }
        }
        return "";
    }

    private static String projectNestedTypeName(ParsedProject parsed, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        if (parsed.typesByQualifiedName().containsKey(candidate)) {
            return candidate;
        }
        String dotted = candidate.replace('$', '.');
        return parsed.typesByQualifiedName().keySet().stream()
                .filter(type -> type.replace('$', '.').equals(dotted))
                .findFirst()
                .orElse("");
    }

    private static String externalTypeName(CtTypeReference<?> type) {
        if (type == null) {
            return "";
        }
        String qualified = type.getQualifiedName();
        if (qualified != null && !qualified.isBlank()) {
            return stripModulePrefix(qualified.replaceAll("<.*>", ""));
        }
        return stripModulePrefix(typeName(type).replaceAll("<.*>", ""));
    }

    private static MemberReference parseMemberReference(String member) {
        String value = member == null ? "" : member.trim();
        int open = value.indexOf('(');
        int close = value.lastIndexOf(')');
        if (open < 0 || close < open) {
            return new MemberReference(value, false, List.of());
        }
        String name = value.substring(0, open).trim();
        String params = value.substring(open + 1, close).trim();
        if (params.isBlank()) {
            return new MemberReference(name, true, List.of());
        }
        return new MemberReference(name, true, splitReferenceParameters(params));
    }

    private static List<String> splitReferenceParameters(String params) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                parts.add(cleanReferenceParameter(params.substring(start, i)));
                start = i + 1;
            }
        }
        parts.add(cleanReferenceParameter(params.substring(start)));
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    private static String cleanReferenceParameter(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("@[\\w.]+(?:\\([^)]*\\))?\\s*", "");
        value = value.replaceAll("\\bfinal\\s+", "");
        value = value.replaceAll("<.*>", "");
        value = value.replace("...", "[]");
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length > 1) {
            value = tokens[0];
        }
        return value.trim();
    }

    private static boolean typeMatches(String referenceType, SourceParameter parameter) {
        String ref = normalizeTypeForMatch(referenceType);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizeTypeForMatch(parameter.type()));
        candidates.add(normalizeTypeForMatch(parameter.erasedType()));
        candidates.add(normalizeTypeForMatch(simpleTypeName(parameter.type())));
        candidates.add(normalizeTypeForMatch(simpleTypeName(parameter.erasedType())));
        return candidates.contains(ref);
    }

    private static String normalizeTypeForMatch(String type) {
        if (type == null) {
            return "";
        }
        return type.replaceAll("<.*>", "")
                .replace("...", "[]")
                .replaceAll("\\s+", "")
                .trim();
    }

    private static String simpleTypeName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String suffix = "";
        String value = type.trim();
        while (value.endsWith("[]")) {
            suffix += "[]";
            value = value.substring(0, value.length() - 2);
        }
        int dot = value.lastIndexOf('.');
        int nested = value.lastIndexOf('$');
        int index = Math.max(dot, nested);
        return (index >= 0 ? value.substring(index + 1) : value) + suffix;
    }

    private static boolean isExternalReference(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return false;
        }
        String target = rawType.replaceAll("<.*>", "").trim();
        return target.contains(".") && !target.startsWith(".");
    }

    private static int parameterCountFromReference(String member) {
        int open = member.indexOf('(');
        int close = member.lastIndexOf(')');
        if (open < 0 || close < open) {
            return -1;
        }
        String params = member.substring(open + 1, close).trim();
        if (params.isBlank()) {
            return 0;
        }
        int count = 1;
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            if (c == '>') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    private static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private static boolean isDeprecated(CtExecutable<?> executable, String javadoc) {
        return annotations(executable).stream().anyMatch(a -> a.endsWith("Deprecated"))
                || !rawBlockTagBodies(javadoc, "deprecated").isEmpty();
    }

    private static String deprecationText(String javadoc) {
        return rawBlockTagBodies(javadoc, "deprecated").stream().findFirst().orElse("");
    }

    private static Integer maxSourceFiles() {
        Integer requestScoped = SourceBackends.maxSourceFiles();
        if (requestScoped != null) {
            return requestScoped;
        }
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

    private static String erasedReturnType(CtExecutable<?> executable, String fallback) {
        if (executable instanceof CtMethod<?> method) {
            return erasedType(method.getType(), fallback);
        }
        return "void";
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

    private static List<String> methodModifiers(CtExecutable<?> executable) {
        List<String> result = new ArrayList<>(executable instanceof CtModifiable modifiable
                ? modifiers(modifiable) : List.of());
        // Spoon stores the interface default keyword separately from ModifierKind.
        if (executable instanceof CtMethod<?> method && method.isDefaultMethod()) {
            result.add("default");
        }
        return result.stream().distinct().sorted().toList();
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
        } catch (Exception | StackOverflowError e) {
            return ref.toString();
        }
    }

    private static String erasedType(CtTypeReference<?> ref, String fallback) {
        if (ref == null) {
            return fallback;
        }
        try {
            CtTypeReference<?> erasure = ref.getTypeErasure();
            String name = typeName(erasure);
            return name == null || name.isBlank() ? fallback : name;
        } catch (Exception | StackOverflowError e) {
            return fallback;
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

    private static String methodUri(Path projectRoot, Path sourceFile, String typeName, String signature) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedFile = sourceFile.toAbsolutePath().normalize();
        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        return relative + "#" + typeName + "." + signature.replaceAll("\\s+", " ").trim();
    }

    private static String fieldUri(Path projectRoot, Path sourceFile, String typeName, String fieldName,
                                   String erasedType) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedFile = sourceFile.toAbsolutePath().normalize();
        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        String type = erasedType == null || erasedType.isBlank() ? "unknown" : erasedType;
        return relative + "#" + typeName + "." + fieldName + ":" + type;
    }

    private static String typeUri(Path projectRoot, CtType<?> type) {
        if (type == null || type.getPosition() == null || !type.getPosition().isValidPosition()) {
            return "";
        }
        try {
            Path sourceFile = type.getPosition().getFile().toPath().toAbsolutePath().normalize();
            Path root = projectRoot.toAbsolutePath().normalize();
            String path = sourceFile.startsWith(root)
                    ? root.relativize(sourceFile).toString().replace('\\', '/')
                    : sourceFile.toString();
            return path + "#" + type.getQualifiedName();
        } catch (Exception e) {
            return "#" + type.getQualifiedName();
        }
    }

    private static String sourcePath(CtElement element) {
        if (element == null || element.getPosition() == null || !element.getPosition().isValidPosition()) {
            return "";
        }
        try {
            return element.getPosition().getFile().toPath().toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int lineNumber(CtElement element) {
        if (element == null || element.getPosition() == null || !element.getPosition().isValidPosition()) {
            return -1;
        }
        return element.getPosition().getLine();
    }

    static String sourceSet(ProjectModel project, Path sourceFile) {
        Path normalizedSource = sourceFile.toAbsolutePath().normalize();
        String modeledSourceSet = project.metadata().getModuleSourceSets().stream()
                .flatMap(sourceSet -> sourceSet.sources().stream()
                        .map(root -> Map.entry(root.toAbsolutePath().normalize(), sourceSet.sourceSet())))
                .filter(entry -> normalizedSource.startsWith(entry.getKey()))
                .max(Comparator.comparingInt(entry -> entry.getKey().getNameCount()))
                .map(Map.Entry::getValue)
                .map(SpoonSourceModelBackend::normalizeSourceSet)
                .orElse("");
        if (!modeledSourceSet.isBlank()) {
            return modeledSourceSet;
        }
        if (project.testSourceRoots().stream().anyMatch(normalizedSource::startsWith)) {
            return "test";
        }
        if (project.sourceRoots().stream().anyMatch(normalizedSource::startsWith)) {
            return "main";
        }
        return sourceSetFromPath(project.projectPath(), normalizedSource);
    }

    private static String normalizeSourceSet(String sourceSet) {
        String normalized = sourceSet == null ? "" : sourceSet.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "main" -> "main";
            case "test" -> "test";
            case "integrationtest", "integration-test", "integration_test", "it", "itest" ->
                    "integration_test";
            default -> normalized;
        };
    }

    private static String sourceSetFromPath(Path projectRoot, Path sourceFile) {
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
        if (lower.contains("/src/main/") || lower.startsWith("src/main/")) {
            return "main";
        }
        if (lower.contains("example") || lower.contains("sample") || lower.contains("demo")) {
            return "example";
        }
        return "unknown";
    }

    private record ParsedProject(
            Path projectRoot,
            List<SourceMethod> methods,
            Map<String, SourceMethod> methodsByUri,
            Map<String, CtExecutable<?>> executablesByUri,
            Map<String, CtType<?>> typesByQualifiedName,
            Map<String, List<SourceMethod>> methodsByTypeName,
            Map<String, List<SourceField>> fieldsByTypeName,
            Map<Path, ImportContext> importsByFile,
            Map<String, TypeContext> typeContextsByTypeAndMethod,
            Map<Path, String> sourceTextByFile,
            Map<String, Map<String, String>> typeMethodsByType,
            ClassLoader projectClassLoader,
            String mode,
            SourceParseStats parseStats) {
        private ParsedProject {
            projectRoot = projectRoot != null ? projectRoot.toAbsolutePath().normalize() : Path.of(".").toAbsolutePath().normalize();
            methods = List.copyOf(methods);
            methodsByUri = Map.copyOf(methodsByUri);
            executablesByUri = Map.copyOf(executablesByUri);
            typesByQualifiedName = Map.copyOf(typesByQualifiedName);
            methodsByTypeName = methodsByTypeName.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            fieldsByTypeName = fieldsByTypeName.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            importsByFile = importsByFile != null ? Map.copyOf(importsByFile) : Map.of();
            mode = mode != null ? mode : "";
            parseStats = parseStats != null ? parseStats : SourceParseStats.empty();
        }

        private void close() throws IOException {
            if (projectClassLoader instanceof URLClassLoader loader) {
                loader.close();
            }
        }
    }

    private record ImportContext(Map<String, String> explicit, Set<String> wildcard) {
        private ImportContext {
            explicit = explicit != null ? Map.copyOf(explicit) : Map.of();
            wildcard = wildcard != null ? Set.copyOf(wildcard) : Set.of();
        }

        static ImportContext empty() {
            return new ImportContext(Map.of(), Set.of());
        }
    }

    private record ExternalType(String qualifiedName, boolean resolved, String confidence) {
        static ExternalType resolved(String qualifiedName, String confidence) {
            return new ExternalType(qualifiedName, true, confidence);
        }

        static ExternalType unresolved(String rawName) {
            return new ExternalType(rawName == null ? "" : rawName, false, "unresolved");
        }
    }

    private record ExternalMember(String kind, String memberKind, String confidence) {
    }

    private record SourceField(
            String fieldUri,
            String typeName,
            String fieldName,
            String type,
            String erasedType,
            Path sourceFile,
            int lineNumber,
            List<String> modifiers,
            List<String> annotations,
            String javadoc,
            String sourceSet) {
        private SourceField {
            fieldUri = fieldUri != null ? fieldUri : "";
            typeName = typeName != null ? typeName : "";
            fieldName = fieldName != null ? fieldName : "";
            type = type != null ? type : "";
            erasedType = erasedType != null ? erasedType : "";
            sourceFile = sourceFile != null ? sourceFile : Path.of("");
            modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
            annotations = annotations != null ? List.copyOf(annotations) : List.of();
            javadoc = javadoc != null ? javadoc : "";
            sourceSet = sourceSet != null ? sourceSet : "unknown";
        }
    }

    private record MemberReference(String name, boolean hasParameters, List<String> parameterTypes) {
        private MemberReference {
            name = name != null ? name.trim() : "";
            parameterTypes = parameterTypes != null ? List.copyOf(parameterTypes) : List.of();
        }

        boolean matches(SourceMethod method) {
            if (!method.methodName().equals(name)) {
                return false;
            }
            if (!hasParameters) {
                return true;
            }
            if (method.parameters().size() != parameterTypes.size()) {
                return false;
            }
            for (int i = 0; i < parameterTypes.size(); i++) {
                if (!typeMatches(parameterTypes.get(i), method.parameters().get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private record InlineJavadocReference(String tag, String raw, String target, String label) {
        private InlineJavadocReference {
            tag = tag != null ? tag : "";
            raw = raw != null ? raw : "";
            target = target != null ? target : "";
            label = label != null ? label : "";
        }
    }

    private record RawJavadocReference(String tag, String raw, String target, String label) {
        private RawJavadocReference {
            tag = tag != null ? tag : "";
            raw = raw != null ? raw : "";
            target = target != null ? target : "";
            label = label != null ? label : "";
        }
    }

    private record MatchedRawJavadocReference(Optional<RawJavadocReference> reference, String confidence) {
        private MatchedRawJavadocReference {
            reference = reference == null ? Optional.empty() : reference;
            confidence = confidence == null || confidence.isBlank() ? "none" : confidence;
        }

        static MatchedRawJavadocReference none() {
            return new MatchedRawJavadocReference(Optional.empty(), "none");
        }

        Optional<RawJavadocReference> reliableReference() {
            return "high".equals(confidence) ? reference : Optional.empty();
        }
    }

    private record ModelBuild(CtModel model, String mode) {
    }

    private record ParsedModels(List<CtModel> models, String mode, SourceParseStats stats) {
        private ParsedModels {
            models = models != null ? List.copyOf(models) : List.of();
            mode = mode != null ? mode : "";
            stats = stats != null ? stats : SourceParseStats.empty();
        }
    }

    private record TypeContext(
            Map<String, String> typeMethods,
            List<String> sameTypeMethods,
            List<String> overloadGroup) {
        private TypeContext {
            typeMethods = typeMethods != null ? Map.copyOf(typeMethods) : Map.of();
            sameTypeMethods = sameTypeMethods != null ? List.copyOf(sameTypeMethods) : List.of();
            overloadGroup = overloadGroup != null ? List.copyOf(overloadGroup) : List.of();
        }

        static TypeContext empty() {
            return new TypeContext(Map.of(), List.of(), List.of());
        }
    }
}
