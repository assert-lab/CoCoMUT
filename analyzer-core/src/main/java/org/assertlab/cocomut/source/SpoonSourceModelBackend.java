package org.assertlab.cocomut.source;

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
import java.net.URL;
import java.net.URLClassLoader;
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
 * projects and does not use legacy fallback parsing.
 */
final class SpoonSourceModelBackend implements SourceModelBackend {
    private static final Pattern PARAM_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@param\\s+(\\S+)");
    private static final Pattern THROWS_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@(throws|exception)\\s+(\\S+)");
    private static final Pattern SINCE_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@since\\s+(.+)$");
    private static final Pattern SEE_TAG = Pattern.compile("(?m)^\\s*\\*?\\s*@see\\s+(.+)$");
    private static final Pattern ANCHOR_HREF = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?ms)(?:^|\\R)\\s*\\*?\\s*@(param|return|throws|exception|since|deprecated|see|apiNote|implSpec|implNote)\\s*(.*?)(?=\\R\\s*\\*?\\s*@|\\z)");
    private static final Pattern JAVADOC_FILE_REFERENCE = Pattern.compile(
            "(?:\\{@docRoot}/)?(?:[\\w.$-]+/)*(?:doc-files/)?[\\w.$-]+\\.(?:png|svg|gif|jpg|jpeg|html|htm|txt|java)",
            Pattern.CASE_INSENSITIVE);
    private static final String MAX_SOURCE_FILES_PROPERTY = "cocomut.maxSourceFiles";
    private static final String MAX_SOURCE_FILES_ENV = "COCOMUT_MAX_SOURCE_FILES";
    private static final int MAX_CLASS_METHOD_CONTEXT = 500;
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
                importsByFile, new java.util.concurrent.ConcurrentHashMap<>(),
                projectClassLoader(project), parsedModels.mode(), parsedModels.stats());
    }

    private ParsedModels parseModels(ProjectModel project) throws IOException {
        Integer maxSourceFiles = maxSourceFiles();
        if (maxSourceFiles != null) {
            return parseJavaFilesWithLimit(allSourceRoots(project),
                    complianceLevel(project.javaVersion()), maxSourceFiles, project);
        }

        return parseCtModels(project);
    }

    private ParsedModels parseCtModels(ProjectModel project) throws IOException {
        List<Path> roots = allSourceRoots(project);
        List<Path> javaFiles = javaFiles(roots, 0);
        if (roots.isEmpty()) {
            return new ParsedModels(List.of(buildModel(List.of(), complianceLevel(project.javaVersion()), project)),
                    "classpath", SourceParseStats.empty());
        }

        try {
            return new ParsedModels(List.of(buildModel(roots, complianceLevel(project.javaVersion()), project)),
                    "classpath", new SourceParseStats(javaFiles.size(), javaFiles.size(), List.of()));
        } catch (RuntimeException combinedFailure) {
            List<CtModel> models = new ArrayList<>();
            List<Path> failedFiles = new ArrayList<>();
            for (Path root : roots) {
                try {
                    models.add(buildModel(List.of(root), complianceLevel(project.javaVersion()), project));
                } catch (RuntimeException rootFailure) {
                    ParsedModels parsedRoot = parseJavaFilesIndividually(root, complianceLevel(project.javaVersion()), project);
                    models.addAll(parsedRoot.models());
                    failedFiles.addAll(parsedRoot.stats().failedFiles());
                }
            }
            if (models.isEmpty()) {
                throw combinedFailure;
            }
            return new ParsedModels(models, "classpath",
                    new SourceParseStats(javaFiles.size(), javaFiles.size() - failedFiles.size(), failedFiles));
        }
    }

    private ParsedModels parseJavaFilesWithLimit(List<Path> roots, int complianceLevel, int maxSourceFiles,
                                                  ProjectModel project)
            throws IOException {
        if (roots.isEmpty()) {
            return new ParsedModels(List.of(buildModel(List.of(), complianceLevel, project)),
                    "classpath_limited", SourceParseStats.empty());
        }
        List<CtModel> models = new ArrayList<>();
        List<Path> files = javaFiles(roots, maxSourceFiles);
        List<Path> failedFiles = new ArrayList<>();
        for (Path file : files) {
            try {
                models.add(buildModel(List.of(file), complianceLevel, project));
            } catch (RuntimeException ignored) {
                failedFiles.add(file);
            }
        }
        if (models.isEmpty()) {
            models = List.of(buildModel(List.of(), complianceLevel, project));
        }
        return new ParsedModels(models, "classpath_limited",
                new SourceParseStats(files.size(), files.size() - failedFiles.size(), failedFiles));
    }

    private static List<Path> allSourceRoots(ProjectModel project) {
        List<Path> roots = new ArrayList<>();
        roots.addAll(project.sourceRoots());
        roots.addAll(project.testSourceRoots());
        return roots.stream().distinct().toList();
    }

    private ParsedModels parseJavaFilesIndividually(Path root, int complianceLevel, ProjectModel project) throws IOException {
        List<CtModel> models = new ArrayList<>();
        List<Path> files = javaFiles(List.of(root), 0);
        List<Path> failedFiles = new ArrayList<>();
        for (Path file : files) {
            try {
                models.add(buildModel(List.of(file), complianceLevel, project));
            } catch (RuntimeException ignored) {
                failedFiles.add(file);
            }
        }
        return new ParsedModels(models, "classpath", new SourceParseStats(files.size(),
                files.size() - failedFiles.size(), failedFiles));
    }

    private static List<Path> javaFiles(List<Path> roots, int limit) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    if (limit > 0 && files.size() >= limit) {
                        return files;
                    }
                    files.add(file);
                }
            }
        }
        return files;
    }

    private CtModel buildModel(List<Path> inputs, int complianceLevel, ProjectModel project) {
        try {
            return launcher(inputs, complianceLevel, project).buildModel();
        } catch (RuntimeException firstFailure) {
            if (complianceLevel == 17) {
                throw firstFailure;
            }
            return launcher(inputs, 17, project).buildModel();
        }
    }

    private Launcher launcher(List<Path> inputs, int complianceLevel, ProjectModel project) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(false);
        launcher.getEnvironment().setCommentEnabled(true);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setShouldCompile(false);
        launcher.getEnvironment().setComplianceLevel(complianceLevel);
        List<String> classpath = classpathEntries(project);
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
                // Some generic declarations can recurse inside Spoon resolution.
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
        metrics.put("inline_link_count", inlineLinkTargets(normalized).size());
        return metrics;
    }

    private static Map<String, Object> javadocMetadata(ParsedProject parsed, CtType<?> owner,
                                                       CtExecutable<?> executable, SourceMethod method,
                                                       String javadoc) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        metadata.put("since", matches(SINCE_TAG, normalized, 1));
        metadata.put("see", matches(SEE_TAG, normalized, 1));
        metadata.put("inline_links", inlineLinkTargets(normalized));
        metadata.put("javadoc_references", javadocReferences(parsed, owner, normalized));
        metadata.put("file_references", fileReferences(parsed.projectRoot(), method, normalized));
        metadata.put("structured_tags", structuredTags(normalized));
        metadata.put("uses_inheritdoc", normalized.contains("{@inheritDoc}") || normalized.contains("@inheritDoc"));
        metadata.put("deprecated", isDeprecated(executable, normalized));
        metadata.put("deprecation_text", deprecationText(normalized));
        metadata.put("inheritdoc_resolution", inheritdocResolution(executable, normalized));
        metadata.put("inherited_javadoc_candidates", inheritedJavadocCandidates(executable));
        return metadata;
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

        for (InlineJavadocReference inline : inlineLinkReferences(javadoc)) {
            references.add(resolveJavadocReference(parsed, owner, inline.tag(), inline.raw(),
                    inline.target(), inline.label()));
        }

        return references;
    }

    private static List<String> inlineLinkTargets(String javadoc) {
        return inlineLinkReferences(javadoc).stream()
                .map(InlineJavadocReference::target)
                .toList();
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

    private static Map<String, Object> resolveJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                               String tag, String raw, String target,
                                                               String label) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("tag", tag);
        ref.put("raw", raw);
        ref.put("target", target);
        ref.put("label", label == null ? "" : label);

        if (target.startsWith("\"")) {
            ref.put("kind", "text_reference");
            ref.put("text", target.replaceAll("^\"|\"$", ""));
            ref.put("resolution", "text");
            enrichReferenceTaxonomy(parsed, owner, ref);
            return ref;
        }

        Matcher anchor = ANCHOR_HREF.matcher(target);
        if (anchor.find()) {
            ref.put("kind", "external_url");
            ref.put("url", anchor.group(1).trim());
            ref.put("label", anchor.group(2).replaceAll("\\s+", " ").trim());
            ref.put("resolution", "external");
            enrichReferenceTaxonomy(parsed, owner, ref);
            return ref;
        }

        if (target.startsWith("http://") || target.startsWith("https://")) {
            ref.put("kind", "external_url");
            ref.put("url", target);
            ref.put("resolution", "external");
            enrichReferenceTaxonomy(parsed, owner, ref);
            return ref;
        }

        String cleaned = stripModulePrefix(target.replaceAll("#$", "").trim());
        if (cleaned.isBlank()) {
            ref.put("kind", "unknown");
            ref.put("resolution", "empty_target");
            enrichReferenceTaxonomy(parsed, owner, ref);
            return ref;
        }

        if (cleaned.contains("#")) {
            resolveMemberReference(parsed, owner, cleaned, ref);
        } else {
            resolveTypeReference(parsed, owner, cleaned, ref);
        }
        enrichReferenceTaxonomy(parsed, owner, ref);
        return ref;
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
            String externalClass = stringValue(ref.get("external_class"));
            if (externalClass.startsWith("java.") || externalClass.startsWith("javax.")) {
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
        String resolvedClass = stringValue(ref.get("resolved_class"));
        if (!resolvedClass.isBlank()) {
            return resolvedClass;
        }
        String methodUri = stringValue(ref.get("method_uri"));
        if (!methodUri.isBlank()) {
            SourceMethod method = parsed.methodsByUri().get(methodUri);
            if (method != null) {
                return method.className();
            }
        }
        String fieldUri = stringValue(ref.get("field_uri"));
        if (!fieldUri.isBlank()) {
            return parsed.fieldsByClassName().values().stream()
                    .flatMap(List::stream)
                    .filter(field -> field.fieldUri().equals(fieldUri))
                    .map(SourceField::className)
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
        target = stripModulePrefix(target);
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
        // Javadoc references are source text, not typed AST references. Resolve
        // unqualified targets in the same order a Java reader would: explicit
        // imports, language-defined java.lang, wildcard imports, then cautious
        // JDK probing only when the runtime can prove the symbol exists.
        if (target.contains(".")) {
            return classExists(parsed, target) ? ExternalType.resolved(target, "qualified_symbol")
                    : ExternalType.unresolved(target);
        }
        ImportContext imports = importContext(parsed, owner);
        String explicit = imports.explicit().get(target);
        if (explicit != null && classExists(parsed, explicit)) {
            return ExternalType.resolved(explicit, "explicit_import");
        }
        String javaLang = "java.lang." + target;
        if (classExists(parsed, javaLang)) {
            return ExternalType.resolved(javaLang, "implicit_java_lang");
        }
        for (String packageName : imports.wildcard()) {
            String candidate = packageName + "." + target;
            if (classExists(parsed, candidate)) {
                return ExternalType.resolved(candidate, "wildcard_import_symbol");
            }
        }
        for (String packageName : COMMON_JDK_PACKAGES) {
            String candidate = packageName + "." + target;
            if (classExists(parsed, candidate)) {
                return ExternalType.resolved(candidate, "common_jdk_probe");
            }
        }
        return ExternalType.unresolved(rawType);
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

    private static ExternalMember classifyExternalMember(ParsedProject parsed, String className,
                                                         MemberReference memberReference) {
        if (className == null || className.isBlank() || memberReference.name().isBlank()) {
            return new ExternalMember("member_reference", "unknown", "unresolved");
        }
        try {
            Class<?> clazz = classForName(parsed, className);
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

    private static Class<?> classForName(ParsedProject parsed, String className) throws ClassNotFoundException {
        ClassLoader loader = parsed != null && parsed.projectClassLoader() != null
                ? parsed.projectClassLoader()
                : ClassLoader.getSystemClassLoader();
        return Class.forName(className, false, loader);
    }

    private static boolean classExists(ParsedProject parsed, String className) {
        try {
            classForName(parsed, className);
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
            ref.put("referenced_method", referencedMethodContext(parsed, method));
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
            ref.put("field_javadoc", field.javadoc());
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
        ref.put("class_javadoc", docComment(type));
        ref.put("class_hierarchy", type != null ? classHierarchy(type) : "");
        ref.put("hierarchy_resolution", hierarchyResolution(type));
    }

    private static Map<String, Object> referencedMethodContext(ParsedProject parsed, SourceMethod method) {
        Map<String, Object> details = new LinkedHashMap<>();
        CtExecutable<?> executable = parsed.executablesByUri().get(method.methodUri());
        CtType<?> owner = executable != null ? executable.getParent(CtType.class) : null;
        details.put("method_uri", method.methodUri());
        details.put("method_name", method.methodName());
        details.put("qualified_name", method.className() + "." + method.methodName());
        details.put("signature", method.className() + "." + method.signature());
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
        details.put("class_javadoc", owner != null ? docComment(owner) : "");
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
            ImportContext imports = importContext(parsed, owner);
            String explicit = imports.explicit().get(target);
            if (explicit != null && parsed.typesByQualifiedName().containsKey(explicit)) {
                return explicit;
            }
            String packageName = owner.getPackage() != null ? owner.getPackage().getQualifiedName() : "";
            String samePackage = packageName.isBlank() ? target : packageName + "." + target;
            if (parsed.typesByQualifiedName().containsKey(samePackage)) {
                return samePackage;
            }
            String nested = owner.getQualifiedName() + "$" + target;
            if (parsed.typesByQualifiedName().containsKey(nested)) {
                return nested;
            }
            for (String wildcard : imports.wildcard()) {
                String imported = wildcard + "." + target;
                if (parsed.typesByQualifiedName().containsKey(imported)) {
                    return imported;
                }
            }
        }
        return "";
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
            Map<String, ClassContext> classContextsByTypeAndMethod,
            ClassLoader projectClassLoader,
            String mode,
            SourceParseStats parseStats) {
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

    private record InlineJavadocReference(String tag, String raw, String target, String label) {
        private InlineJavadocReference {
            tag = tag != null ? tag : "";
            raw = raw != null ? raw : "";
            target = target != null ? target : "";
            label = label != null ? label : "";
        }
    }

    private record ParsedModels(List<CtModel> models, String mode, SourceParseStats stats) {
        private ParsedModels {
            models = models != null ? List.copyOf(models) : List.of();
            mode = mode != null ? mode : "";
            stats = stats != null ? stats : SourceParseStats.empty();
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
