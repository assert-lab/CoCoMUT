package org.assertlab.cocomut.source;

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
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
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
            "(?:\\{@docRoot\\}/)?(?:[\\w.$-]+/)*(?:doc-files/)?[\\w.$-]+\\.(?:png|svg|gif|jpg|jpeg|html|htm|txt|java)",
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
        String rawJavadoc = rawDocComment(executable).orElse(javadoc);
        List<JavadocElement> javadocElements = spoonJavadocElements(executable);
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
                javadocMetadata(parsed, owner, executable, method, javadocElements, javadoc, rawJavadoc),
                documentationMetrics(method, javadocElements, javadoc),
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
        project.dependencyJars().forEach(path -> entries.add(path.toString()));
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

    private static Optional<String> rawDocComment(CtElement element) {
        try {
            SourcePosition position = element.getPosition();
            if (position == null || !position.isValidPosition() || position.getFile() == null) {
                return Optional.empty();
            }
            String source = Files.readString(position.getFile().toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, Math.min(position.getSourceStart(), source.length()));
            int open = source.lastIndexOf("/**", start);
            if (open < 0) {
                return Optional.empty();
            }
            int close = source.indexOf("*/", open);
            if (close < 0 || close > start) {
                return Optional.empty();
            }
            String between = source.substring(close + 2, start);
            if (!between.replaceAll("@\\w+(?:\\([^)]*\\))?", "").trim().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(cleanRawJavadoc(source.substring(open, close + 2)));
        } catch (Exception ignored) {
            return Optional.empty();
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

    private static Map<String, Object> documentationMetrics(SourceMethod method, List<JavadocElement> elements,
                                                            String javadoc) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        Map<String, Object> structuredTags = !elements.isEmpty()
                ? structuredTagsFromElements(elements)
                : fallbackStructuredTags(normalized);
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
        metrics.put("has_summary", !elements.isEmpty() ? hasSummary(elements) : hasSummary(normalized));
        metrics.put("has_param_tags", !paramTags.isEmpty());
        metrics.put("missing_param_tags", missingParams);
        metrics.put("has_return_tag", !structuredList(structuredTags, "return").isEmpty());
        metrics.put("has_throws_tag", !throwsTags.isEmpty());
        metrics.put("mentions_null", normalized.toLowerCase(Locale.ROOT).contains("null"));
        metrics.put("mentions_examples", mentionsExample(normalized));
        metrics.put("uses_inheritdoc", !elements.isEmpty()
                ? usesInheritDoc(elements)
                : normalized.contains("{@inheritDoc}") || normalized.contains("@inheritDoc"));
        metrics.put("has_since_tag", !structuredList(structuredTags, "since").isEmpty());
        metrics.put("has_see_tag", hasBlockTag(elements, StandardJavadocTagType.SEE)
                || !matches(SEE_TAG, normalized, 1).isEmpty());
        metrics.put("inline_link_count", inlineLinkTargets(elements, normalized).size());
        return metrics;
    }

    private static Map<String, Object> javadocMetadata(ParsedProject parsed, CtType<?> owner,
                                                       CtExecutable<?> executable, SourceMethod method,
                                                       List<JavadocElement> elements,
                                                       String javadoc, String rawJavadoc) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String normalized = javadoc == null ? "" : javadoc.strip();
        List<Map<String, Object>> references = javadocReferences(parsed, owner, elements, normalized);
        boolean usesInheritDoc = !elements.isEmpty()
                ? usesInheritDoc(elements)
                : normalized.contains("{@inheritDoc}") || normalized.contains("@inheritDoc");
        metadata.put("since", !elements.isEmpty()
                ? blockTagTexts(elements, StandardJavadocTagType.SINCE)
                : matches(SINCE_TAG, normalized, 1));
        metadata.put("see", referenceTargetsByTag(references, "see"));
        metadata.put("inline_links", inlineReferenceTargets(references));
        metadata.put("javadoc_references", references);
        metadata.put("file_references", fileReferences(parsed.projectRoot(), method, rawJavadoc));
        metadata.put("structured_tags", structuredTags(elements, normalized));
        metadata.put("uses_inheritdoc", usesInheritDoc);
        metadata.put("inheritdoc_policy", usesInheritDoc ? "candidate_only" : "not_applicable");
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
            return Files.readString(sourceFile, StandardCharsets.UTF_8).contains(text);
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
        if (!elements.isEmpty()) {
            return structuredTagsFromElements(elements);
        }
        return fallbackStructuredTags(javadoc);
    }

    private static Map<String, Object> structuredTagsFromElements(List<JavadocElement> elements) {
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
                case "param" -> params.add(namedBlockTag(blockElements, "name"));
                case "return" -> returns.add(elementsText(blockElements));
                case "throws", "exception" -> throwsTags.add(namedBlockTag(blockElements, "type"));
                case "since" -> since.add(elementsText(blockElements));
                case "apiNote" -> apiNotes.add(elementsText(blockElements));
                case "implSpec" -> implSpecs.add(elementsText(blockElements));
                case "implNote" -> implNotes.add(elementsText(blockElements));
                case "deprecated" -> deprecated.add(elementsText(blockElements));
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

    private static List<JavadocBlockTag> blockTags(List<JavadocElement> elements) {
        List<JavadocBlockTag> blocks = new ArrayList<>();
        for (JavadocElement element : elements == null ? List.<JavadocElement>of() : elements) {
            if (element instanceof JavadocBlockTag block) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private static List<String> blockTagTexts(List<JavadocElement> elements, StandardJavadocTagType tagType) {
        return blockTags(elements).stream()
                .filter(block -> tagType.equals(block.getTagType()))
                .map(block -> elementsText(block.getElements()))
                .filter(text -> !text.isBlank())
                .toList();
    }

    private static Map<String, String> namedBlockTag(List<JavadocElement> elements, String key) {
        if (elements == null || elements.isEmpty()) {
            return Map.of(key, "", "text", "");
        }
        String name = elementText(elements.get(0));
        String text = elementsText(elements.subList(1, elements.size()));
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

    private static boolean hasSummary(List<JavadocElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return false;
        }
        List<JavadocElement> summaryElements = new ArrayList<>();
        for (JavadocElement element : elements) {
            if (element instanceof JavadocBlockTag) {
                break;
            }
            summaryElements.add(element);
        }
        return !elementsText(summaryElements).isBlank();
    }

    private static boolean usesInheritDoc(List<JavadocElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return false;
        }
        for (JavadocElement element : elements) {
            if (element instanceof JavadocInlineTag inline) {
                if (StandardJavadocTagType.INHERIT_DOC.equals(inline.getTagType())
                        || usesInheritDoc(inline.getElements())) {
                    return true;
                }
            } else if (element instanceof JavadocBlockTag block && usesInheritDoc(block.getElements())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBlockTag(List<JavadocElement> elements, StandardJavadocTagType tagType) {
        return blockTags(elements).stream().anyMatch(block -> tagType.equals(block.getTagType()));
    }

    private static String elementsText(List<JavadocElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JavadocElement element : elements) {
            String text = elementText(element);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join(" ", parts).replaceAll("\\s+", " ").trim();
    }

    private static String elementText(JavadocElement element) {
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
            return elementsText(inline.getElements());
        }
        if (element instanceof JavadocBlockTag block) {
            return elementsText(block.getElements());
        }
        return "";
    }

    private static List<Map<String, Object>> javadocReferences(ParsedProject parsed, CtType<?> owner,
                                                               List<JavadocElement> elements, String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> references = new ArrayList<>();
        List<RawJavadocReference> rawReferences = new ArrayList<>(rawJavadocReferences(javadoc));

        for (JavadocElement element : elements) {
            addSpoonJavadocReference(parsed, owner, element, rawReferences, references);
        }

        Set<String> represented = new LinkedHashSet<>();
        for (Map<String, Object> reference : references) {
            represented.add(javadocReferenceKey(reference));
        }
        for (Map<String, Object> fallback : fallbackJavadocReferences(parsed, owner, javadoc)) {
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
        } catch (RuntimeException | StackOverflowError ignored) {
            return List.of();
        }
    }

    private static void addSpoonJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                 JavadocElement element,
                                                 List<RawJavadocReference> rawReferences,
                                                 List<Map<String, Object>> references) {
        if (element instanceof JavadocInlineTag inline) {
            if (isInlineReferenceTag(inline)) {
                String canonicalTarget = firstReferenceTarget(inline).orElse("");
                references.add(spoonJavadocReference(parsed, owner, inline.getTagType().getName(), inline.getElements(),
                        nextRaw(rawReferences, inline.getTagType().getName(), canonicalTarget)));
            }
            for (JavadocElement nested : inline.getElements()) {
                addSpoonJavadocReference(parsed, owner, nested, rawReferences, references);
            }
        } else if (element instanceof JavadocBlockTag block) {
            if (StandardJavadocTagType.SEE.equals(block.getTagType())) {
                String canonicalTarget = firstReferenceTarget(block.getElements()).orElse("");
                references.add(spoonJavadocReference(parsed, owner, block.getTagType().getName(), block.getElements(),
                        nextRaw(rawReferences, block.getTagType().getName(), canonicalTarget)));
            }
            for (JavadocElement nested : block.getElements()) {
                addSpoonJavadocReference(parsed, owner, nested, rawReferences, references);
            }
        }
    }

    private static boolean isInlineReferenceTag(JavadocInlineTag tag) {
        return StandardJavadocTagType.LINK.equals(tag.getTagType())
                || StandardJavadocTagType.LINKPLAIN.equals(tag.getTagType())
                || StandardJavadocTagType.VALUE.equals(tag.getTagType());
    }

    private static Map<String, Object> spoonJavadocReference(ParsedProject parsed, CtType<?> owner,
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
            Map<String, Object> ref = resolveTypedJavadocReference(parsed, owner, tag, raw, target,
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
        Map<String, Object> ref = resolveJavadocReference(parsed, owner, tag, raw, target, resolvedLabel);
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

    private static List<Map<String, Object>> fallbackJavadocReferences(ParsedProject parsed, CtType<?> owner, String javadoc) {
        List<Map<String, Object>> references = new ArrayList<>();

        for (RawJavadocReference rawReference : rawJavadocReferences(javadoc)) {
            Map<String, Object> ref = resolveJavadocReference(parsed, owner, rawReference.tag(), rawReference.raw(),
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

        Matcher blockMatcher = BLOCK_TAG.matcher(javadoc);
        while (blockMatcher.find()) {
            if (!"see".equals(blockMatcher.group(1))) {
                continue;
            }
            String raw = blockMatcher.group(2).replaceAll("\\s+", " ").trim();
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

    private static Map<String, Object> resolveJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                               String tag, String raw, String target,
                                                               String label) {
        Map<String, Object> ref = baseJavadocReference(tag, raw, target, label);
        resolveStringJavadocReference(parsed, owner, target, ref);
        enrichReferenceTaxonomy(parsed, owner, ref);
        return ref;
    }

    private static Map<String, Object> resolveTypedJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                                    String tag, String raw, String target,
                                                                    String label, CtReference spoonReference) {
        Map<String, Object> ref = baseJavadocReference(tag, raw, target, label);
        if (spoonReference instanceof CtExecutableReference<?> executable) {
            resolveExecutableJavadocReference(parsed, owner, executable, ref);
        } else if (spoonReference instanceof CtFieldReference<?> field) {
            resolveFieldJavadocReference(parsed, owner, field, ref);
        } else if (spoonReference instanceof CtTypeReference<?> type) {
            resolveTypeJavadocReference(parsed, owner, type, ref);
        } else if (spoonReference instanceof CtPackageReference packageReference) {
            ref.put("kind", "type_reference");
            ref.put("resolution", "external_symbol");
            ref.put("external_class", packageReference.getQualifiedName());
            ref.put("external_resolution", "package_reference");
        } else {
            resolveStringJavadocReference(parsed, owner, target, ref);
        }
        enrichReferenceTaxonomy(parsed, owner, ref);
        return ref;
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
        String className = projectClassName(parsed, owner, executable.getDeclaringType());
        String memberName = executable.getSimpleName();
        if ("<init>".equals(memberName) && !className.isBlank()) {
            memberName = simpleTypeName(className);
        }
        List<String> parameterTypes = executable.getParameters().stream()
                .map(SpoonSourceModelBackend::typeName)
                .toList();
        MemberReference memberReference = typedMemberReference(memberName, parameterTypes, stringValue(ref.get("target")));
        ref.put("kind", "member_reference");
        ref.put("resolved_class", className);
        ref.put("referenced_member", memberReference.hasParameters()
                ? memberName + "(" + String.join(",", parameterTypes) + ")"
                : memberName);
        boolean inheritedRelativeReference = inheritedRelativeReference(owner, className, stringValue(ref.get("target")));

        if (!className.isBlank()) {
            if (resolveMethodCandidate(parsed, className, memberReference, inheritedRelativeReference, ref)) {
                return;
            }
            if (!inheritedRelativeReference && resolveInheritedMemberCandidate(parsed, className, memberReference, ref)) {
                return;
            }
            ref.put("resolution", "class_resolved_member_unresolved");
            return;
        }

        String externalClass = externalClassName(executable.getDeclaringType());
        if (!externalClass.isBlank()) {
            resolveExternalMemberReference(parsed, owner, externalClass, memberReference,
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
        String className = projectClassName(parsed, owner, field.getDeclaringType());
        MemberReference memberReference = new MemberReference(field.getSimpleName(), false, List.of());
        ref.put("kind", "field_reference");
        ref.put("resolved_class", className);
        ref.put("referenced_member", field.getSimpleName());
        boolean inheritedRelativeReference = inheritedRelativeReference(owner, className, stringValue(ref.get("target")));

        if (!className.isBlank()) {
            if (resolveFieldCandidate(parsed, className, memberReference, inheritedRelativeReference, ref)) {
                return;
            }
            if (!inheritedRelativeReference && resolveInheritedMemberCandidate(parsed, className, memberReference, ref)) {
                return;
            }
            ref.put("resolution", "class_resolved_member_unresolved");
            return;
        }

        String externalClass = externalClassName(field.getDeclaringType());
        if (!externalClass.isBlank()) {
            resolveExternalMemberReference(parsed, owner, externalClass, memberReference,
                    field.getSimpleName(), ref);
            return;
        }
        resolveStringJavadocReference(parsed, owner, stringValue(ref.get("target")), ref);
    }

    private static boolean inheritedRelativeReference(CtType<?> owner, String className, String sourceTarget) {
        if (owner == null || className == null || className.isBlank() || sourceTarget == null) {
            return false;
        }
        return sourceTarget.trim().startsWith("#") && !owner.getQualifiedName().equals(className);
    }

    private static void resolveTypeJavadocReference(ParsedProject parsed, CtType<?> owner,
                                                    CtTypeReference<?> type,
                                                    Map<String, Object> ref) {
        String className = projectClassName(parsed, owner, type);
        ref.put("kind", "type_reference");
        if (!className.isBlank()) {
            ref.put("resolution", "resolved_type");
            ref.put("resolved_class", className);
            putTypeDetails(parsed, className, ref);
            return;
        }

        String externalClass = externalClassName(type);
        if (!externalClass.isBlank()) {
            ExternalType external = resolveExternalType(parsed, owner, externalClass);
            ref.put("resolution", external.resolved() ? "external_symbol" : "unresolved");
            ref.put("external_class", external.qualifiedName());
            ref.put("external_resolution", external.confidence());
            return;
        }
        resolveStringJavadocReference(parsed, owner, stringValue(ref.get("target")), ref);
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
            java.util.stream.Stream.concat(project.classOutputDirs().stream(), project.dependencyJars().stream())
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

    private static String projectClassName(ParsedProject parsed, CtType<?> owner, CtTypeReference<?> type) {
        if (type == null) {
            return owner != null ? owner.getQualifiedName() : "";
        }
        try {
            CtType<?> declaration = type.getDeclaration();
            if (declaration != null && declaration.getQualifiedName() != null
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
            String resolved = resolveClassName(parsed, owner, normalized);
            if (!resolved.isBlank()) {
                return resolved;
            }
            String nested = projectNestedClassName(parsed, normalized);
            if (!nested.isBlank()) {
                return nested;
            }
        }
        return "";
    }

    private static String projectNestedClassName(ParsedProject parsed, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        String dotted = candidate.replace('$', '.');
        return parsed.typesByQualifiedName().keySet().stream()
                .filter(type -> type.replace('$', '.').equals(dotted))
                .findFirst()
                .orElse("");
    }

    private static String externalClassName(CtTypeReference<?> type) {
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
