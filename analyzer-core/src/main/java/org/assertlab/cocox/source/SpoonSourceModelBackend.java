package org.assertlab.cocox.source;

import org.assertlab.cocox.AnalysisOptions;
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
import spoon.reflect.declaration.CtField;
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
import java.util.zip.ZipFile;

/**
 * Spoon-backed source model.
 *
 * <p>The backend uses no-classpath mode by default. That is a deliberate mining
 * choice: CoCoX should extract source/Javadoc context from imperfect public
 * repositories and record unresolved type precision in provenance rather than
 * failing just because dependencies are unavailable.
 */
final class SpoonSourceModelBackend implements SourceModelBackend {
    private static final Pattern PARAM_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@param\\s+(\\S+)");
    private static final Pattern THROWS_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@(throws|exception)\\s+(\\S+)");
    private static final Pattern SINCE_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@since\\s+(.+)$");
    private static final Pattern SEE_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@see\\s+(.+)$");
    private static final Pattern INLINE_LINK = Pattern.compile("\\{@(?:link|linkplain)\\s+([^}\\s]+)");
    private static final Pattern INLINE_LINK_FULL = Pattern.compile("\\{@(link|linkplain)\\s+([^}\\s]+)(?:\\s+([^}]+))?}");
    private static final Pattern ANCHOR_HREF = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?ms)(?:^|\\R)\\s*\\*?\\s*@(param|return|throws|exception|since|deprecated|see|apiNote|implSpec|implNote)\\s*(.*?)(?=\\R\\s*\\*?\\s*@|\\z)");
    private static final Pattern JAVADOC_FILE_REFERENCE = Pattern.compile(
            "(?:\\{@docRoot}/)?(?:[\\w.$-]+/)*(?:doc-files/)?[\\w.$-]+\\.(?:png|svg|gif|jpg|jpeg|html|htm|txt|java)",
            Pattern.CASE_INSENSITIVE);
    private static final String MAX_SOURCE_FILES_PROPERTY = "cocox.maxSourceFiles";
    private static final String MAX_SOURCE_FILES_ENV = "COCOX_MAX_SOURCE_FILES";
    private static final int MAX_CLASS_METHOD_CONTEXT = 500;
    private static final int MAX_OVERLOAD_CONTEXT = 200;
    private static final List<String> COMMON_JDK_PACKAGES = List.of(
            "java.util",
            "java.util.regex",
            "java.util.stream",
            "java.time",
            "java.text",
            "java.io");
    private final AnalysisOptions.SourceResolution requestedResolution;
    private final Map<String, ParsedProject> cache = new ConcurrentHashMap<>();

    SpoonSourceModelBackend(AnalysisOptions.SourceResolution requestedResolution) {
        this.requestedResolution = requestedResolution != null
                ? requestedResolution
                : AnalysisOptions.SourceResolution.NOCLASSPATH;
    }

    @Override
    public String name() {
        return "spoon";
    }

    @Override
    public String mode() {
        return switch (requestedResolution) {
            case CLASSPATH -> "classpath";
            case AUTO -> "auto";
            case NOCLASSPATH -> "noclasspath";
        };
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
        ClassContext classContext = owner != null ? classContext(parsed, owner, method.methodName()) : ClassContext.empty();

        return Optional.of(new SourceContext(
                method,
                methodBody,
                javadoc,
                classJavadoc,
                classHierarchy,
                hierarchyResolution,
                classContext.classMethods(),
                fieldReads(executable),
                fieldWrites(executable),
                classContext.siblingMethods(),
                classContext.overloadGroup(),
                dynamicFeatures(executable),
                javadocMetadata(parsed, owner, executable, method, javadoc),
                documentationMetrics(method, javadoc),
                parsed.mode()));
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

    private String cacheKey(ProjectModel project) {
        String limit = String.valueOf(maxSourceFiles());
        String roots = project.sourceRoots().stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .sorted()
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
        return project.projectPath().toAbsolutePath().normalize()
                + "::resolution=" + requestedResolution
                + "::roots=" + roots
                + "::maxSourceFiles=" + limit;
    }

    private ParsedProject parse(ProjectModel project) throws IOException {
        Integer maxSourceFiles = maxSourceFiles();
        if (requestedResolution == AnalysisOptions.SourceResolution.AUTO
                && maxSourceFiles == null
                && hasClasspathEvidence(project)) {
            ParsedProject classpath = null;
            try {
                classpath = buildParsedProject(project,
                        new ParsedModels(parseModels(project, false), "classpath"));
            } catch (RuntimeException ignored) {
                // Fall through to the no-classpath coverage baseline.
            }
            ParsedProject noClasspath = buildParsedProject(project,
                    new ParsedModels(parseModels(project, true), "noclasspath_fallback"));
            if (classpath != null && preservesCoverage(classpath, noClasspath)) {
                return classpath;
            }
            return noClasspath;
        }
        return buildParsedProject(project, parseModels(project));
    }

    private static boolean preservesCoverage(ParsedProject candidate, ParsedProject baseline) {
        if (baseline.methods().isEmpty()) {
            return true;
        }
        return candidate.methods().size() >= Math.ceil(baseline.methods().size() * 0.8);
    }

    private ParsedProject buildParsedProject(ProjectModel project, ParsedModels parsedModels) {
        List<SourceMethod> methods = new ArrayList<>();
        Map<String, SourceMethod> methodsByUri = new LinkedHashMap<>();
        Map<String, CtExecutable<?>> executablesByUri = new LinkedHashMap<>();
        Map<String, CtType<?>> typesByQualifiedName = new LinkedHashMap<>();
        Map<String, List<SourceMethod>> methodsByClassName = new LinkedHashMap<>();
        Map<String, List<SourceField>> fieldsByClassName = new LinkedHashMap<>();
        Map<Path, ImportContext> importsByFile = new LinkedHashMap<>();

        List<CtExecutable<?>> executables = new ArrayList<>();
        for (CtModel model : parsedModels.models()) {
            for (CtType<?> type : model.getElements(new TypeFilter<>(CtType.class))) {
                String qualifiedName = type.getQualifiedName();
                if (qualifiedName != null && !qualifiedName.isBlank()) {
                    sourceFile(type).ifPresent(path ->
                            importsByFile.computeIfAbsent(path, SpoonSourceModelBackend::parseImportContext));
                    typesByQualifiedName.putIfAbsent(qualifiedName, type);
                    for (CtField<?> field : type.getFields()) {
                        toSourceField(project, type, field)
                                .ifPresent(sourceField -> fieldsByClassName
                                        .computeIfAbsent(sourceField.className(), ignored -> new ArrayList<>())
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
                methodsByClassName.computeIfAbsent(sourceMethod.className(), ignored -> new ArrayList<>())
                        .add(sourceMethod);
            }
        }

        return new ParsedProject(project.projectPath(), methods, methodsByUri, executablesByUri,
                typesByQualifiedName, methodsByClassName, fieldsByClassName,
                importsByFile, sourceArchives(project), new ConcurrentHashMap<>(), parsedModels.mode());
    }

    private ParsedModels parseModels(ProjectModel project) throws IOException {
        Integer maxSourceFiles = maxSourceFiles();
        if (maxSourceFiles != null) {
            return new ParsedModels(parseJavaFilesWithLimit(project.sourceRoots(),
                    complianceLevel(project.javaVersion()), maxSourceFiles, true), "noclasspath_limited");
        }

        if (requestedResolution == AnalysisOptions.SourceResolution.CLASSPATH) {
            return new ParsedModels(parseModels(project, false), "classpath");
        }

        return new ParsedModels(parseModels(project, true), "noclasspath");
    }

    private List<CtModel> parseModels(ProjectModel project, boolean noClasspath) throws IOException {
        if (project.sourceRoots().isEmpty()) {
            return List.of(buildModel(List.of(), complianceLevel(project.javaVersion()), project, noClasspath));
        }

        try {
            return List.of(buildModel(project.sourceRoots(), complianceLevel(project.javaVersion()), project, noClasspath));
        } catch (RuntimeException combinedFailure) {
            List<CtModel> models = new ArrayList<>();
            for (Path root : project.sourceRoots()) {
                try {
                    models.add(buildModel(List.of(root), complianceLevel(project.javaVersion()), project, noClasspath));
                } catch (RuntimeException rootFailure) {
                    models.addAll(parseJavaFilesIndividually(root, complianceLevel(project.javaVersion()), project, noClasspath));
                }
            }
            if (models.isEmpty()) {
                throw combinedFailure;
            }
            return models;
        }
    }

    private static boolean hasClasspathEvidence(ProjectModel project) {
        return !project.classOutputDirs().isEmpty() || !project.dependencyJars().isEmpty()
                || project.compileSucceeded();
    }

    private List<CtModel> parseJavaFilesWithLimit(List<Path> roots, int complianceLevel, int maxSourceFiles,
                                                  boolean noClasspath)
            throws IOException {
        if (roots.isEmpty()) {
            return List.of(buildModel(List.of(), complianceLevel, null, noClasspath));
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
                        models.add(buildModel(List.of(file), complianceLevel, null, noClasspath));
                    } catch (RuntimeException ignored) {
                        // A few invalid/newer-syntax files should not drop a capped smoke run.
                    }
                }
            }
        }
        return models.isEmpty() ? List.of(buildModel(List.of(), complianceLevel, null, noClasspath)) : models;
    }

    private List<CtModel> parseJavaFilesIndividually(Path root, int complianceLevel, ProjectModel project,
                                                     boolean noClasspath) throws IOException {
        List<CtModel> models = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                try {
                    models.add(buildModel(List.of(file), complianceLevel, project, noClasspath));
                } catch (RuntimeException ignored) {
                    // A few invalid/newer-syntax files should not drop an entire repository.
                }
            }
        }
        return models;
    }

    private CtModel buildModel(List<Path> inputs, int complianceLevel, ProjectModel project, boolean noClasspath) {
        try {
            return launcher(inputs, complianceLevel, project, noClasspath).buildModel();
        } catch (RuntimeException firstFailure) {
            if (complianceLevel == 17) {
                throw firstFailure;
            }
            return launcher(inputs, 17, project, noClasspath).buildModel();
        }
    }

    private Launcher launcher(List<Path> inputs, int complianceLevel, ProjectModel project, boolean noClasspath) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(noClasspath);
        launcher.getEnvironment().setCommentEnabled(true);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setShouldCompile(false);
        launcher.getEnvironment().setComplianceLevel(complianceLevel);
        if (!noClasspath) {
            List<String> classpath = classpathEntries(project);
            if (!classpath.isEmpty()) {
                launcher.getEnvironment().setSourceClasspath(classpath.toArray(String[]::new));
            }
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
        project.dependencyJars().forEach(path -> entries.add(path.toString()));
        return entries;
    }

    private static List<Path> sourceArchives(ProjectModel project) {
        LinkedHashSet<Path> archives = new LinkedHashSet<>();
        Path javaHome = Path.of(System.getProperty("java.home", ""));
        List<Path> jdkCandidates = List.of(
                javaHome.resolve("lib/src.zip"),
                javaHome.getParent() != null ? javaHome.getParent().resolve("lib/src.zip") : javaHome.resolve("missing"));
        for (Path candidate : jdkCandidates) {
            if (Files.isRegularFile(candidate)) {
                archives.add(candidate.toAbsolutePath().normalize());
            }
        }
        for (Path jar : project.dependencyJars()) {
            String name = jar.getFileName() != null ? jar.getFileName().toString() : "";
            if (!name.endsWith(".jar") || name.endsWith("-sources.jar")) {
                continue;
            }
            Path sibling = jar.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
            if (Files.isRegularFile(sibling)) {
                archives.add(sibling.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(archives);
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
                    .matcher(Files.readString(sourceFile, StandardCharsets.UTF_8));
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
        String className = owner.getQualifiedName();
        String methodName = methodName(executable, owner);
        List<SourceParameter> parameters = parameters(executable);
        String displaySignature = methodName + "(" + parameterSignature(parameters, false) + ")";
        String returnType = returnType(executable);
        String erasedReturnType = erasedReturnType(executable, returnType);
        String identitySignature = identitySignature(methodName, parameters, erasedReturnType);
        String uri = methodUri(project.projectPath(), sourceFile, className, identitySignature);

        return Optional.of(new SourceMethod(
                uri,
                className,
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
                thrownExceptions(executable),
                sourceSet(project.projectPath(), sourceFile),
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
                sourceSet(project.projectPath(), sourceFile)));
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
            String source = Files.readString(position.getFile().toPath(), StandardCharsets.UTF_8);
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
                        } catch (Exception | StackOverflowError e) {
                            return true;
                        }
                    });
            return unresolvedSuperclass || unresolvedInterface ? "partial" : "resolved";
        } catch (Exception | StackOverflowError e) {
            return "partial";
        }
    }

    private static ClassContext classContext(ParsedProject parsed, CtType<?> type, String methodName) {
        String key = type.getQualifiedName() + "#" + methodName;
        return parsed.classContextsByTypeAndMethod().computeIfAbsent(key, ignored -> {
            Map<String, String> classMethods = classMethods(type);
            List<String> siblingMethods = classMethods.keySet().stream().sorted().toList();
            List<String> overloadGroup = siblingMethods.stream()
                    .filter(sig -> sig.startsWith(methodName + "("))
                    .limit(MAX_OVERLOAD_CONTEXT)
                    .toList();
            return new ClassContext(classMethods, siblingMethods, overloadGroup);
        });
    }

    private static Map<String, String> classMethods(CtType<?> type) {
        Map<String, String> methods = new LinkedHashMap<>();
        for (CtExecutable<?> executable : type.getTypeMembers().stream()
                .filter(CtExecutable.class::isInstance)
                .map(CtExecutable.class::cast)
                .toList()) {
            try {
                String name = executable instanceof CtConstructor<?> ? type.getSimpleName() : executable.getSimpleName();
                String signature = name + "(" + parameterSignature(parameters(executable), false) + ")";
                methods.put(signature, signature);
            } catch (Exception | StackOverflowError ignored) {
                // Some no-classpath generic declarations can recurse inside Spoon resolution.
                // Class/sibling method context is optional, so keep the focal method.
            }
            if (methods.size() >= MAX_CLASS_METHOD_CONTEXT) {
                break;
            }
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

    private static Map<String, Object> javadocMetadata(ParsedProject parsed, CtType<?> owner,
                                                       CtExecutable<?> executable, SourceMethod method,
                                                       String javadoc) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        metadata.put("since", matches(SINCE_TAG, normalized, 1));
        metadata.put("see", matches(SEE_TAG, normalized, 1));
        metadata.put("inline_links", matches(INLINE_LINK, normalized, 1));
        metadata.put("javadoc_references", javadocReferences(parsed, owner, normalized));
        metadata.put("file_references", fileReferences(method, normalized));
        metadata.put("structured_tags", structuredTags(normalized));
        metadata.put("uses_inheritdoc", normalized.contains("{@inheritDoc}") || normalized.contains("@inheritDoc"));
        metadata.put("deprecated", isDeprecated(executable, normalized));
        metadata.put("deprecation_text", deprecationText(normalized));
        metadata.put("inheritdoc_resolution", inheritdocResolution(executable, normalized));
        metadata.put("inherited_javadoc_candidates", inheritedJavadocCandidates(executable));
        return metadata;
    }

    private static List<Map<String, Object>> fileReferences(SourceMethod method, String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> references = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = JAVADOC_FILE_REFERENCE.matcher(javadoc);
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                continue;
            }
            String normalized = raw.replace("{@docRoot}/", "");
            if (!seen.add(normalized)) {
                continue;
            }
            Path resolved = resolveJavadocFile(method.sourceFile(), normalized);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("raw", raw);
            ref.put("path", normalized);
            ref.put("kind", fileReferenceKind(normalized));
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

    private static Path resolveJavadocFile(Path sourceFile, String rawPath) {
        if (sourceFile == null || rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path sourceDir = sourceFile.toAbsolutePath().normalize().getParent();
        if (sourceDir == null) {
            return null;
        }
        Path direct = sourceDir.resolve(rawPath).normalize();
        if (Files.exists(direct)) {
            return direct;
        }
        int docFiles = rawPath.indexOf("doc-files/");
        if (docFiles >= 0) {
            Path docFile = sourceDir.resolve(rawPath.substring(docFiles)).normalize();
            if (Files.exists(docFile)) {
                return docFile;
            }
        }
        return direct;
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

    private static Map<String, Object> structuredTags(String javadoc) {
        Map<String, Object> tags = new LinkedHashMap<>();
        List<Map<String, String>> params = new ArrayList<>();
        List<Map<String, String>> throwsTags = new ArrayList<>();
        List<String> returns = new ArrayList<>();
        List<String> since = new ArrayList<>();
        List<String> apiNotes = new ArrayList<>();
        List<String> implSpecs = new ArrayList<>();
        List<String> implNotes = new ArrayList<>();
        List<String> deprecated = new ArrayList<>();

        Matcher matcher = BLOCK_TAG.matcher(javadoc == null ? "" : javadoc);
        while (matcher.find()) {
            String tag = matcher.group(1);
            String body = matcher.group(2).replaceAll("\\s+", " ").trim();
            switch (tag) {
                case "param" -> {
                    String[] parts = body.split("\\s+", 2);
                    Map<String, String> param = new LinkedHashMap<>();
                    param.put("name", parts.length > 0 ? parts[0] : "");
                    param.put("text", parts.length > 1 ? parts[1] : "");
                    params.add(param);
                }
                case "return" -> returns.add(body);
                case "throws", "exception" -> {
                    String[] parts = body.split("\\s+", 2);
                    Map<String, String> thrown = new LinkedHashMap<>();
                    thrown.put("type", parts.length > 0 ? parts[0] : "");
                    thrown.put("text", parts.length > 1 ? parts[1] : "");
                    throwsTags.add(thrown);
                }
                case "since" -> since.add(body);
                case "apiNote" -> apiNotes.add(body);
                case "implSpec" -> implSpecs.add(body);
                case "implNote" -> implNotes.add(body);
                case "deprecated" -> deprecated.add(body);
                default -> {
                    // Keep the switch exhaustive for known tags above.
                }
            }
        }

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

    private static List<Map<String, Object>> javadocReferences(ParsedProject parsed, CtType<?> owner, String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> references = new ArrayList<>();

        Matcher seeMatcher = SEE_TAG.matcher(javadoc);
        while (seeMatcher.find()) {
            String raw = seeMatcher.group(1).trim();
            String[] targetAndLabel = splitReferenceTargetAndLabel(raw);
            references.add(resolveJavadocReference(parsed, owner, "see", raw, targetAndLabel[0], targetAndLabel[1]));
        }

        Matcher inlineMatcher = INLINE_LINK_FULL.matcher(javadoc);
        while (inlineMatcher.find()) {
            String tag = inlineMatcher.group(1);
            String target = inlineMatcher.group(2).trim();
            String label = inlineMatcher.group(3) != null ? inlineMatcher.group(3).trim() : "";
            references.add(resolveJavadocReference(parsed, owner, tag, inlineMatcher.group(0), target, label));
        }

        return references;
    }

    private static String[] splitReferenceTargetAndLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[]{"", ""};
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("<a ") || trimmed.startsWith("<A ")
                || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return new String[]{trimmed, ""};
        }
        int firstWhitespace = -1;
        int parenDepth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (Character.isWhitespace(c) && parenDepth == 0) {
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

    private static Map<String, Object> resolveJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                               String tag, String raw, String target,
                                                               String label) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("tag", tag);
        ref.put("raw", raw);
        ref.put("target", target);
        ref.put("label", label == null ? "" : label);

        Matcher anchor = ANCHOR_HREF.matcher(target);
        if (anchor.find()) {
            ref.put("kind", "external_url");
            ref.put("url", anchor.group(1).trim());
            ref.put("label", anchor.group(2).replaceAll("\\s+", " ").trim());
            ref.put("resolution", "external");
            return ref;
        }

        if (target.startsWith("http://") || target.startsWith("https://")) {
            ref.put("kind", "external_url");
            ref.put("url", target);
            ref.put("resolution", "external");
            return ref;
        }

        String cleaned = target.replaceAll("#$", "").trim();
        if (cleaned.isBlank()) {
            ref.put("kind", "unknown");
            ref.put("resolution", "empty_target");
            return ref;
        }

        if (cleaned.contains("#")) {
            resolveMemberReference(parsed, owner, cleaned, ref);
        } else {
            resolveTypeReference(parsed, owner, cleaned, ref);
        }
        return ref;
    }

    private static void resolveMemberReference(ParsedProject parsed, CtType<?> owner,
                                               String target, Map<String, Object> ref) {
        int hash = target.indexOf('#');
        String rawType = target.substring(0, hash).trim();
        String member = target.substring(hash + 1).trim();
        String className = resolveClassName(parsed, owner, rawType);
        MemberReference memberReference = parseMemberReference(member);

        ref.put("kind", "member_reference");
        ref.put("resolved_class", className);
        ref.put("referenced_member", member);

        if (className.isBlank()) {
            resolveExternalMemberReference(parsed, owner, rawType, memberReference, member, ref);
            return;
        }

        if (memberReference.name().isBlank()) {
            ref.put("resolution", "unresolved");
            return;
        }

        if (resolveMethodCandidate(parsed, className, memberReference, false, ref)) {
            return;
        }
        if (resolveFieldCandidate(parsed, className, memberReference, false, ref)) {
            return;
        }
        if (resolveInheritedMemberCandidate(parsed, className, memberReference, ref)) {
            return;
        }

        ref.put("resolution", parsed.typesByQualifiedName().containsKey(className)
                ? "class_resolved_member_unresolved"
                : (isExternalReference(rawType) ? "external_symbol" : "unresolved"));
        if (!parsed.typesByQualifiedName().containsKey(className)) {
            resolveExternalMemberReference(parsed, owner, rawType, memberReference, member, ref);
        }
    }

    private static void resolveTypeReference(ParsedProject parsed, CtType<?> owner,
                                             String target, Map<String, Object> ref) {
        String className = resolveClassName(parsed, owner, target);
        if (!className.isBlank()) {
            ref.put("kind", "type_reference");
            ref.put("resolution", "resolved_type");
            ref.put("resolved_class", className);
            putTypeDetails(parsed, className, ref);
        } else {
            ExternalType external = resolveExternalType(parsed, owner, target);
            ref.put("kind", "type_reference");
            ref.put("resolution", external.resolved() ? "external_symbol" : "unresolved");
            ref.put("external_class", external.qualifiedName());
            ref.put("external_resolution", external.confidence());
            putExternalExcerpt(parsed, external.qualifiedName(), "", ref);
        }
    }

    private static void resolveExternalMemberReference(ParsedProject parsed, CtType<?> owner,
                                                       String rawType, MemberReference memberReference,
                                                       String rawMember, Map<String, Object> ref) {
        ExternalType external = resolveExternalType(parsed, owner, rawType);
        ref.put("external_class", external.qualifiedName());
        ref.put("external_member", rawMember);
        ref.put("external_resolution", external.confidence());
        if (!external.resolved()) {
            ref.put("resolution", "unresolved");
            return;
        }

        ExternalMember member = classifyExternalMember(external.qualifiedName(), memberReference);
        ref.put("kind", member.kind());
        ref.put("resolution", "external_symbol");
        ref.put("external_member_kind", member.memberKind());
        ref.put("external_member_resolution", member.confidence());
        putExternalExcerpt(parsed, external.qualifiedName(), rawMember, ref);
    }

    private static ExternalType resolveExternalType(ParsedProject parsed, CtType<?> owner, String rawType) {
        String target = rawType == null ? "" : rawType.trim().replaceAll("<.*>", "");
        if (target.isBlank()) {
            return ExternalType.unresolved(rawType);
        }
        // Javadoc references are source text, not typed AST references. Resolve
        // unqualified targets in the same order a Java reader would: explicit
        // imports, language-defined java.lang, wildcard imports, then cautious
        // JDK probing only when the runtime can prove the symbol exists.
        if (target.contains(".")) {
            return classExists(target) ? ExternalType.resolved(target, "qualified_symbol")
                    : ExternalType.unresolved(target);
        }
        ImportContext imports = importContext(parsed, owner);
        String explicit = imports.explicit().get(target);
        if (explicit != null && classExists(explicit)) {
            return ExternalType.resolved(explicit, "explicit_import");
        }
        String javaLang = "java.lang." + target;
        if (classExists(javaLang)) {
            return ExternalType.resolved(javaLang, "implicit_java_lang");
        }
        for (String packageName : imports.wildcard()) {
            String candidate = packageName + "." + target;
            if (classExists(candidate)) {
                return ExternalType.resolved(candidate, "wildcard_import_symbol");
            }
        }
        for (String packageName : COMMON_JDK_PACKAGES) {
            String candidate = packageName + "." + target;
            if (classExists(candidate)) {
                return ExternalType.resolved(candidate, "common_jdk_probe");
            }
        }
        return ExternalType.unresolved(rawType);
    }

    private static ImportContext importContext(ParsedProject parsed, CtType<?> owner) {
        Optional<Path> source = sourceFile(owner);
        return source.map(path -> parsed.importsByFile().getOrDefault(path, ImportContext.empty()))
                .orElseGet(ImportContext::empty);
    }

    private static ExternalMember classifyExternalMember(String className, MemberReference memberReference) {
        if (className == null || className.isBlank() || memberReference.name().isBlank()) {
            return new ExternalMember("member_reference", "unknown", "unresolved");
        }
        try {
            Class<?> clazz = Class.forName(className, false, ClassLoader.getSystemClassLoader());
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
                        && (!memberReference.hasParameters()
                        || method.getParameterCount() == memberReference.parameterTypes().size())) {
                    return new ExternalMember("member_reference", "method", "reflection_public_method");
                }
            }
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(memberReference.name())
                        && (!memberReference.hasParameters()
                        || method.getParameterCount() == memberReference.parameterTypes().size())) {
                    return new ExternalMember("member_reference", "method", "reflection_declared_method");
                }
            }
        } catch (Throwable ignored) {
            // Reflection is used only to classify external symbols. Source
            // extraction remains valid when symbols cannot be loaded.
        }
        return new ExternalMember("member_reference", "unknown", "symbol_only");
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void putExternalExcerpt(ParsedProject parsed, String className, String member,
                                           Map<String, Object> ref) {
        ExternalExcerpt excerpt = externalExcerpt(parsed.sourceArchives(), className, member);
        ref.put("external_doc_source", excerpt.source());
        if (!excerpt.text().isBlank()) {
            ref.put("external_javadoc_excerpt", excerpt.text());
        }
    }

    private static boolean resolveMethodCandidate(ParsedProject parsed, String className,
                                                  MemberReference memberReference, boolean inherited,
                                                  Map<String, Object> ref) {
        List<SourceMethod> nameMatches = parsed.methodsByClassName().getOrDefault(className, List.of()).stream()
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
            ref.put("signature", method.className() + "." + method.signature());
            ref.put("source_set", method.sourceSet());
            ref.put("javadoc_excerpt", excerpt(docComment(parsed.executablesByUri().get(method.methodUri()))));
            if (inherited) {
                ref.put("inherited_from", className);
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
                ref.put("inherited_from", className);
            }
            return true;
        }
        return false;
    }

    private static boolean resolveFieldCandidate(ParsedProject parsed, String className,
                                                 MemberReference memberReference, boolean inherited,
                                                 Map<String, Object> ref) {
        if (memberReference.hasParameters()) {
            return false;
        }
        List<SourceField> fields = parsed.fieldsByClassName().getOrDefault(className, List.of()).stream()
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
            ref.put("javadoc_excerpt", excerpt(field.javadoc()));
            if (inherited) {
                ref.put("inherited_from", className);
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
            ref.put("inherited_from", className);
        }
        return true;
    }

    private static boolean resolveInheritedMemberCandidate(ParsedProject parsed, String className,
                                                           MemberReference memberReference,
                                                           Map<String, Object> ref) {
        for (String parent : localSupertypes(parsed, className)) {
            if (resolveMethodCandidate(parsed, parent, memberReference, true, ref)) {
                return true;
            }
            if (resolveFieldCandidate(parsed, parent, memberReference, true, ref)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> localSupertypes(ParsedProject parsed, String className) {
        CtType<?> type = parsed.typesByQualifiedName().get(className);
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
            String parentName = resolveClassName(parsed, type, typeName(ref));
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
                    String ancestorName = resolveClassName(parsed, parentType, typeName(parentRef));
                    if (!ancestorName.isBlank() && seen.add(ancestorName)) {
                        result.add(ancestorName);
                        collectLocalSupertypes(parsed, parsed.typesByQualifiedName().get(ancestorName), result, seen);
                    }
                }
            }
        }
    }

    private static void putTypeDetails(ParsedProject parsed, String className, Map<String, Object> ref) {
        CtType<?> type = parsed.typesByQualifiedName().get(className);
        ref.put("type_uri", typeUri(parsed.projectRoot(), type));
        ref.put("source_path", sourcePath(type));
        ref.put("line_number", lineNumber(type));
        ref.put("class_javadoc_excerpt", excerpt(docComment(type)));
        ref.put("class_hierarchy", type != null ? classHierarchy(type) : "");
        ref.put("hierarchy_resolution", hierarchyResolution(type));
    }

    private static String resolveClassName(ParsedProject parsed, CtType<?> owner, String rawType) {
        String target = rawType == null ? "" : rawType.trim();
        if (target.isBlank()) {
            return owner != null ? owner.getQualifiedName() : "";
        }
        target = target.replaceAll("<.*>", "");
        if (parsed.typesByQualifiedName().containsKey(target)) {
            return target;
        }
        if (owner != null && !target.contains(".")) {
            String packageName = owner.getPackage() != null ? owner.getPackage().getQualifiedName() : "";
            String samePackage = packageName.isBlank() ? target : packageName + "." + target;
            if (parsed.typesByQualifiedName().containsKey(samePackage)) {
                return samePackage;
            }
            String nested = owner.getQualifiedName() + "$" + target;
            if (parsed.typesByQualifiedName().containsKey(nested)) {
                return nested;
            }
        }
        String lookupTarget = target;
        List<String> simpleMatches = parsed.typesByQualifiedName().keySet().stream()
                .filter(name -> name.endsWith("." + lookupTarget)
                        || name.endsWith("$" + lookupTarget)
                        || name.equals(lookupTarget))
                .limit(2)
                .toList();
        return simpleMatches.size() == 1 ? simpleMatches.get(0) : "";
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

    private static ExternalExcerpt externalExcerpt(List<Path> sourceArchives, String className, String member) {
        if (className == null || className.isBlank()) {
            return ExternalExcerpt.missing();
        }
        String entrySuffix = className.replace('.', '/') + ".java";
        for (Path archive : sourceArchives) {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(entrySuffix)) {
                        continue;
                    }
                    String source = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    String doc = member == null || member.isBlank()
                            ? classDocFromSource(source, simpleTypeName(className))
                            : memberDocFromSource(source, memberName(member));
                    if (!doc.isBlank()) {
                        return new ExternalExcerpt(excerpt(cleanJavadocSource(doc)),
                                archive.getFileName() + "!" + entry.getName());
                    }
                }
            } catch (Exception ignored) {
                // Source/Javadoc archives are optional enrichment.
            }
        }
        return ExternalExcerpt.missing();
    }

    private static String classDocFromSource(String source, String simpleName) {
        Pattern pattern = Pattern.compile("(?s)(/\\*\\*.*?\\*/)\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:public\\s+|protected\\s+|private\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                + "(?:class|interface|enum|record|@interface)\\s+" + Pattern.quote(simpleName) + "\\b");
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String memberDocFromSource(String source, String memberName) {
        Pattern pattern = Pattern.compile("(?s)(/\\*\\*.*?\\*/)\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+|final\\s+|synchronized\\s+|native\\s+|abstract\\s+|default\\s+|transient\\s+|volatile\\s+)*[^;{}()=]*\\b"
                + Pattern.quote(memberName) + "\\s*(?:\\(|[=;])");
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String memberName(String member) {
        String value = member == null ? "" : member.trim();
        int open = value.indexOf('(');
        if (open >= 0) {
            return value.substring(0, open).trim();
        }
        return value;
    }

    private static String cleanJavadocSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("(?s)^\\s*/\\*\\*\\s*", "")
                .replaceAll("(?s)\\s*\\*/\\s*$", "")
                .replaceAll("(?m)^\\s*\\* ?", "")
                .replaceAll("\\s+", " ")
                .trim();
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
        } catch (Exception | StackOverflowError ignored) {
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
        } catch (Exception | StackOverflowError ignored) {
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

    private static String methodUri(Path projectRoot, Path sourceFile, String className, String signature) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedFile = sourceFile.toAbsolutePath().normalize();
        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        return relative + "#" + className + "." + signature.replaceAll("\\s+", " ").trim();
    }

    private static String fieldUri(Path projectRoot, Path sourceFile, String className, String fieldName,
                                   String erasedType) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedFile = sourceFile.toAbsolutePath().normalize();
        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        String type = erasedType == null || erasedType.isBlank() ? "unknown" : erasedType;
        return relative + "#" + className + "." + fieldName + ":" + type;
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
            Map<String, List<SourceMethod>> methodsByClassName,
            Map<String, List<SourceField>> fieldsByClassName,
            Map<Path, ImportContext> importsByFile,
            List<Path> sourceArchives,
            Map<String, ClassContext> classContextsByTypeAndMethod,
            String mode) {
        private ParsedProject {
            projectRoot = projectRoot != null ? projectRoot.toAbsolutePath().normalize() : Path.of(".").toAbsolutePath().normalize();
            methods = List.copyOf(methods);
            methodsByUri = Map.copyOf(methodsByUri);
            executablesByUri = Map.copyOf(executablesByUri);
            typesByQualifiedName = Map.copyOf(typesByQualifiedName);
            methodsByClassName = methodsByClassName.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            fieldsByClassName = fieldsByClassName.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            importsByFile = importsByFile != null ? Map.copyOf(importsByFile) : Map.of();
            sourceArchives = sourceArchives != null ? List.copyOf(sourceArchives) : List.of();
            mode = mode != null ? mode : "";
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

    private record ExternalExcerpt(String text, String source) {
        static ExternalExcerpt missing() {
            return new ExternalExcerpt("", "unavailable");
        }
    }

    private record SourceField(
            String fieldUri,
            String className,
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
            className = className != null ? className : "";
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

    private record ParsedModels(List<CtModel> models, String mode) {
        private ParsedModels {
            models = models != null ? List.copyOf(models) : List.of();
            mode = mode != null ? mode : "";
        }
    }

    private record ClassContext(
            Map<String, String> classMethods,
            List<String> siblingMethods,
            List<String> overloadGroup) {
        private ClassContext {
            classMethods = classMethods != null ? Map.copyOf(classMethods) : Map.of();
            siblingMethods = siblingMethods != null ? List.copyOf(siblingMethods) : List.of();
            overloadGroup = overloadGroup != null ? List.copyOf(overloadGroup) : List.of();
        }

        static ClassContext empty() {
            return new ClassContext(Map.of(), List.of(), List.of());
        }
    }
}
