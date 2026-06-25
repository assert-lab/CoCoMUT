package org.assertlab.cocomut;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaIdentifierFactory;
import sootup.java.core.types.JavaClassType;
import sootup.java.core.views.JavaView;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.callgraph.RapidTypeAnalysisAlgorithm;

/**
 * Phase 3 of the method context extraction pipeline.
 *
 * The single SootUp facade for the analyzer pipeline — all SootUp interactions
 * are encapsulated here. Other phases consume only plain Java types.
 *
 * Capabilities:
 * - Builds CHA or RTA call graph over compiled classes
 * - Resolves caller/callee relationships
 * - Provides signature→methodUri reverse lookup
 * - Queries transitive class hierarchy (superclasses, interfaces, subclasses)
 * - Exposes raw call graph text for human-readable {@code Output_CallGraph_<ALGORITHM>.txt}
 */
public class CallGraphGenerator {
    private final ProjectMetadata projectMetadata;
    private final Algorithm algorithm;
    private final Map<String, CallGraphResult> cache;
    private boolean initialized;

    // SootUp state
    private JavaView view;
    private CallGraph cg;
    private Map<String, List<SootMethod>> methodsByClass;

    // Reverse lookup: SootUp signature string → project methodUri
    private Map<String, String> signatureToMethodUri;
    private Map<SourceMethodKey, List<MethodInfo>> sourceMethodsByKey;
    private Set<String> projectSourceClasses;
    private Map<String, List<MethodInfo>> sourceMethodsByClass;
    private Map<String, SourceClassSummary> sourceClassSummaries;

    // Class hierarchy cache
    private final Map<String, ClassHierarchyInfo> hierarchyCache = new HashMap<>();

    public enum Algorithm {
        CHA, RTA
    }

    /**
     * Plain data class for class hierarchy information.
     * Encapsulates SootUp type resolution without leaking SootUp types.
     */
    public static class ClassHierarchyInfo {
        private final String className;
        private final String simpleName;
        private final String packageName;
        private final List<String> superclasses;
        private final List<String> interfaces;
        private final List<String> directSubclasses;

        public ClassHierarchyInfo(String className, String simpleName, String packageName,
                                  List<String> superclasses, List<String> interfaces,
                                  List<String> directSubclasses) {
            this.className = className;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.superclasses = Collections.unmodifiableList(superclasses);
            this.interfaces = Collections.unmodifiableList(interfaces);
            this.directSubclasses = Collections.unmodifiableList(directSubclasses);
        }

        public String getClassName() { return className; }
        public String getSimpleName() { return simpleName; }
        public String getPackageName() { return packageName; }
        public List<String> getSuperclasses() { return superclasses; }
        public List<String> getInterfaces() { return interfaces; }
        public List<String> getDirectSubclasses() { return directSubclasses; }
    }

    public CallGraphGenerator(ProjectMetadata projectMetadata) {
        this(projectMetadata, Algorithm.CHA);
    }

    public CallGraphGenerator(ProjectMetadata projectMetadata, Algorithm algorithm) {
        this.projectMetadata = Objects.requireNonNull(projectMetadata, "projectMetadata cannot be null");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm cannot be null");
        this.cache = new HashMap<>();
        this.signatureToMethodUri = new HashMap<>();
        this.sourceMethodsByKey = new HashMap<>();
        this.projectSourceClasses = new HashSet<>();
        this.sourceMethodsByClass = new HashMap<>();
        this.sourceClassSummaries = new HashMap<>();
        this.initialized = false;
    }

    public boolean initialize() {
        if (initialized) return true;

        try {
            Set<String> seenPaths = new LinkedHashSet<>();
            List<AnalysisInputLocation> inputLocations = new ArrayList<>();

            List<Path> bytecodeClasspath = new ArrayList<>();
            bytecodeClasspath.addAll(projectMetadata.getMainClassOutputs());
            bytecodeClasspath.addAll(projectMetadata.getTestClassOutputs());
            bytecodeClasspath.addAll(projectMetadata.getProjectArtifactJars());
            bytecodeClasspath.addAll(projectMetadata.getDependencyClasspath());
            if (bytecodeClasspath.isEmpty()) {
                bytecodeClasspath.addAll(projectMetadata.getClasspath());
            }
            for (Path cp : bytecodeClasspath) {
                if ((cp.toFile().isDirectory() && containsClassFiles(cp))
                        || (cp.toString().endsWith(".jar") && Files.isRegularFile(cp))) {
                    if (seenPaths.add(cp.toAbsolutePath().toString())) {
                        inputLocations.add(new JavaClassPathAnalysisInputLocation(cp.toString()));
                    }
                }
            }

            if (inputLocations.isEmpty()) {
                System.err.println("[CallGraphGenerator] no bytecode locations found under " + projectMetadata.getProjectPath());
                return false;
            }

            view = new JavaView(inputLocations);

            methodsByClass = new HashMap<>();

            for (JavaSootClass sootClass : view.getClasses().sequential().collect(Collectors.toList())) {
                String className = sootClass.getType().toString();
                List<SootMethod> methods = new ArrayList<>();
                for (SootMethod m : sootClass.getMethods()) {
                    methods.add(m);
                }
                methodsByClass.put(className, methods);
            }

            initialized = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public CallGraphResult generateForMethod(MethodInfo method) {
        if (!initialized) {
            throw new IllegalStateException("CallGraphGenerator not initialized. Call initialize() first.");
        }

        String cacheKey = method.getMethodUri();
        if (cache.containsKey(cacheKey)) return cache.get(cacheKey);

        try {
            if (cg == null) {
                indexMethodSignatures(List.of(method));
                initializeCallGraphFromProjectMethods();
            }
            long startTime = System.currentTimeMillis();

            Set<CallGraphEdge> callers = new HashSet<>();
            Set<CallGraphEdge> callees = new HashSet<>();

            MethodSignature sig = findMethodSignature(method);
            if (sig != null) {
                signatureToMethodUri.put(sig.toString(), method.getMethodUri());

                for (CallGraph.Call call : cg.callsTo(sig)) {
                    MethodSignature callerSig = call.getSourceMethodSignature();
                    if (!isClassInitializer(callerSig)) {
                        callers.add(edgeFor(callerSig));
                    }
                }
                for (CallGraph.Call call : cg.callsFrom(sig)) {
                    MethodSignature calleeSig = call.getTargetMethodSignature();
                    if (!isClassInitializer(calleeSig)) {
                        callees.add(edgeFor(calleeSig));
                    }
                }
            }

            long endTime = System.currentTimeMillis();

            CallGraphResult result = new CallGraphResult.Builder()
                    .methodUri(method.getMethodUri())
                    .methodName(method.getMethodName())
                    .classname(method.getClassname())
                    .callers(callers)
                    .callees(callees)
                    .algorithm(algorithm.toString())
                    .generationTime(endTime - startTime)
                    .methodMatched(sig != null)
                    .build();

            cache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, CallGraphResult> generateForMethods(List<MethodInfo> methods) {
        return generateForMethods(methods, methods);
    }

    public Map<String, CallGraphResult> generateForMethods(List<MethodInfo> analysisUniverse,
                                                           List<MethodInfo> focalMethods) {
        if (!initialized) {
            throw new IllegalStateException("CallGraphGenerator not initialized. Call initialize() first.");
        }
        indexMethodSignatures(analysisUniverse == null ? List.of() : analysisUniverse);
        initializeCallGraphFromProjectMethods();
        return (focalMethods == null ? List.<MethodInfo>of() : focalMethods).stream()
                .map(this::generateForMethod)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(CallGraphResult::getMethodUri, r -> r));
    }

    private void initializeCallGraphFromProjectMethods() {
        if (cg != null) {
            return;
        }
        Set<String> applicationClasses = projectSourceClasses == null || projectSourceClasses.isEmpty()
                ? methodsByClass.keySet()
                : projectSourceClasses;
        List<MethodSignature> entryPoints = new ArrayList<>();
        for (String className : applicationClasses) {
            for (String bytecodeClass : bytecodeClassNamesForSourceClass(className)) {
                for (SootMethod method : methodsByClass.getOrDefault(bytecodeClass, List.of())) {
                    if (method.hasBody() && method.isConcrete() && !isClassInitializer(method.getSignature())) {
                        entryPoints.add(method.getSignature());
                    }
                }
            }
        }
        if (algorithm == Algorithm.RTA) {
            cg = new RapidTypeAnalysisAlgorithm(view).initialize(entryPoints);
        } else {
            cg = new ClassHierarchyAnalysisAlgorithm(view).initialize(entryPoints);
        }
    }

    // ---- Signature resolution ----

    public String resolveSignatureToMethodUri(String sootUpSignature) {
        return signatureToMethodUri != null ? signatureToMethodUri.get(sootUpSignature) : null;
    }

    private void indexMethodSignatures(List<MethodInfo> methods) {
        sourceMethodsByKey = new HashMap<>();
        projectSourceClasses = new HashSet<>();
        sourceMethodsByClass = new HashMap<>();
        sourceClassSummaries = new HashMap<>();
        for (MethodInfo method : methods) {
            projectSourceClasses.add(method.getClassname());
            sourceMethodsByClass.computeIfAbsent(method.getClassname(), ignored -> new ArrayList<>()).add(method);
            SourceMethodKey key = SourceMethodKey.from(method);
            sourceMethodsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(method);
        }
        sourceMethodsByClass.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparing(MethodInfo::getMethodUri))
                .toList());
        sourceMethodsByKey.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparing(MethodInfo::getMethodUri))
                .toList());
        for (Map.Entry<String, List<MethodInfo>> entry : sourceMethodsByClass.entrySet()) {
            sourceClassSummaries.put(entry.getKey(), SourceClassSummary.from(entry.getValue()));
        }

        for (MethodInfo method : methods) {
            MethodSignature sig = findMethodSignature(method);
            if (sig != null) {
                signatureToMethodUri.put(sig.toString(), method.getMethodUri());
            }
        }
    }

    private CallGraphEdge edgeFor(MethodSignature sig) {
        String raw = sig.toString();
        String methodUri = resolveSignatureToMethodUri(raw);
        String declaringClass = sig.getDeclClassType() != null ? sig.getDeclClassType().toString() : "";
        String methodName = sig.getName();
        if (methodUri != null && !methodUri.isBlank()) {
            return CallGraphEdge.resolved(methodUri, raw, declaringClass, methodName);
        }
        return resolveSootSignature(sig)
                .orElseGet(() -> CallGraphEdge.unresolved(raw, declaringClass, methodName,
                        targetKindFor(raw, declaringClass, methodName), unresolvedReason(sig)));
    }

    private Optional<CallGraphEdge> resolveSootSignature(MethodSignature sig) {
        if (sourceMethodsByKey == null || sourceMethodsByKey.isEmpty()) {
            return Optional.empty();
        }

        String raw = sig.toString();
        String declaringClass = sig.getDeclClassType() != null ? sig.getDeclClassType().toString() : "";
        String methodName = sig.getName();
        BytecodeMethodKey exactKey = BytecodeMethodKey.from(sig, true);
        BytecodeMethodKey returnAgnosticKey = BytecodeMethodKey.from(sig, false);

        List<MethodInfo> exactCandidates = sourceMethodsByKey
                .getOrDefault(SourceMethodKey.from(exactKey), List.of());
        if (exactCandidates.size() == 1) {
            MethodInfo method = exactCandidates.get(0);
            signatureToMethodUri.put(raw, method.getMethodUri());
            return Optional.of(CallGraphEdge.resolved(method.getMethodUri(), raw, declaringClass,
                    methodName, "resolved_normalized_exact"));
        }
        if (exactCandidates.size() > 1) {
            return Optional.of(CallGraphEdge.ambiguous(raw, declaringClass, methodName,
                    methodUris(exactCandidates), "multiple_source_methods_match_normalized_exact_signature"));
        }

        List<MethodInfo> returnAgnosticCandidates = sourceMethodsByKey.entrySet().stream()
                .filter(entry -> entry.getKey().sameClassNameParams(returnAgnosticKey))
                .flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparing(MethodInfo::getMethodUri))
                .toList();
        if (returnAgnosticCandidates.size() == 1) {
            MethodInfo method = returnAgnosticCandidates.get(0);
            signatureToMethodUri.put(raw, method.getMethodUri());
            return Optional.of(CallGraphEdge.resolved(method.getMethodUri(), raw, declaringClass,
                    methodName, "resolved_return_mismatch_unique"));
        }
        if (returnAgnosticCandidates.size() > 1) {
            return Optional.of(CallGraphEdge.ambiguous(raw, declaringClass, methodName,
                    methodUris(returnAgnosticCandidates), "multiple_source_methods_match_name_and_parameters"));
        }

        List<MethodInfo> sameNameCandidates = sourceMethodsByKey.entrySet().stream()
                .filter(entry -> entry.getKey().sameClassName(returnAgnosticKey))
                .flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparing(MethodInfo::getMethodUri))
                .toList();
        if (sameNameCandidates.size() == 1 && parametersCompatibleForSingleCandidate(sig, sameNameCandidates.get(0))) {
            MethodInfo method = sameNameCandidates.get(0);
            signatureToMethodUri.put(raw, method.getMethodUri());
            return Optional.of(CallGraphEdge.resolved(method.getMethodUri(), raw, declaringClass,
                    methodName, "resolved_parameter_normalized_unique"));
        }
        if (sameNameCandidates.size() > 1) {
            List<MethodInfo> compatible = sameNameCandidates.stream()
                    .filter(method -> parametersCompatibleForSingleCandidate(sig, method))
                    .toList();
            if (compatible.size() == 1) {
                MethodInfo method = compatible.get(0);
                signatureToMethodUri.put(raw, method.getMethodUri());
                return Optional.of(CallGraphEdge.resolved(method.getMethodUri(), raw, declaringClass,
                        methodName, "resolved_parameter_normalized_unique"));
            }
            if (compatible.size() > 1) {
                return Optional.of(CallGraphEdge.ambiguous(raw, declaringClass, methodName,
                        methodUris(compatible), "multiple_source_methods_match_normalized_parameters"));
            }
        }
        return Optional.empty();
    }

    private static List<String> methodUris(List<MethodInfo> methods) {
        return methods.stream()
                .map(MethodInfo::getMethodUri)
                .distinct()
                .sorted()
                .toList();
    }

    private String unresolvedReason(MethodSignature sig) {
        String declaringClass = sig.getDeclClassType() != null ? sig.getDeclClassType().toString() : "";
        String methodName = sig.getName();
        if (declaringClass.startsWith("sootup.dummy.InvokeDynamic")) {
            return "invokedynamic_or_lambda_bytecode_artifact";
        }
        if (isJdkOrPlatformClass(declaringClass)) {
            return "jdk_or_platform_method_outside_project_source";
        }
        if (declaringClass.matches(".*\\$\\d+(\\D.*)?$")) {
            return "anonymous_or_local_class_bytecode";
        }
        if (outerSourceClass(declaringClass).isPresent()) {
            return "nested_bytecode_class_without_unique_source_method";
        }
        if (projectSourceClasses.contains(declaringClass)) {
            return sourceMethodsByKey.keySet().stream()
                    .anyMatch(key -> key.className().equals(declaringClass) && key.methodName().equals(methodName))
                    ? "project_method_name_present_but_signature_not_unique_or_compatible"
                    : projectMethodAbsentReason(sig, declaringClass, methodName);
        }
        return "external_or_unmodeled_bytecode_method";
    }

    private String projectMethodAbsentReason(MethodSignature sig, String declaringClass, String methodName) {
        if (isSyntheticMethodName(methodName)) {
            return "project_class_present_method_absent_synthetic_or_compiler_method";
        }
        SourceClassSummary summary = sourceClassSummaries.get(declaringClass);
        if (summary != null) {
            if (summary.kind() == SourceClassKind.ENUM && isEnumGeneratedMethod(methodName, sig)) {
                return "project_class_present_method_absent_enum_generated_method";
            }
            if (summary.kind() == SourceClassKind.RECORD && summary.recordComponents().contains(methodName)) {
                return "project_class_present_method_absent_record_component_accessor";
            }
        }
        if (bytecodeMethodNameExists(declaringClass, methodName)) {
            return "project_class_present_method_absent_bytecode_method_not_selected";
        }
        return "project_class_present_method_absent_no_matching_bytecode_method";
    }

    private static boolean isSyntheticMethodName(String methodName) {
        return methodName != null
                && (methodName.startsWith("access$")
                || methodName.startsWith("lambda$")
                || methodName.contains("$default$")
                || methodName.startsWith("$"));
    }

    private static boolean isEnumGeneratedMethod(String methodName, MethodSignature sig) {
        if ("values".equals(methodName) && sig.getParameterTypes().isEmpty()) {
            return true;
        }
        return "valueOf".equals(methodName)
                && sig.getParameterTypes().size() == 1
                && "java.lang.String".equals(normalizeType(sig.getParameterTypes().get(0).toString()));
    }

    private boolean bytecodeMethodNameExists(String declaringClass, String methodName) {
        if (methodsByClass == null || methodName == null) {
            return false;
        }
        return methodsByClass.getOrDefault(declaringClass, List.of()).stream()
                .anyMatch(method -> methodName.equals(method.getName()));
    }

    private String targetKindFor(String raw, String declaringClass, String methodName) {
        if (raw.contains("sootup.dummy.InvokeDynamic")) {
            return "invokedynamic_method";
        }
        if (methodName != null && (methodName.startsWith("access$")
                || methodName.startsWith("lambda$")
                || methodName.contains("$default$"))) {
            return "synthetic_or_compiler_method";
        }
        if (isJdkOrPlatformClass(declaringClass)) {
            return "jdk_method";
        }
        if (projectSourceClasses.contains(declaringClass) || outerSourceClass(declaringClass).isPresent()) {
            return "unresolved_project_method";
        }
        return "external_method";
    }

    private Optional<String> outerSourceClass(String declaringClass) {
        String current = declaringClass;
        while (current.contains("$")) {
            current = current.substring(0, current.lastIndexOf('$'));
            if (projectSourceClasses.contains(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    private static boolean isJdkOrPlatformClass(String declaringClass) {
        return declaringClass != null
                && (declaringClass.startsWith("java.")
                || declaringClass.startsWith("javax.")
                || declaringClass.startsWith("jdk.")
                || declaringClass.startsWith("sun.")
                || declaringClass.startsWith("com.sun.")
                || declaringClass.startsWith("org.w3c.dom.")
                || declaringClass.startsWith("org.xml.sax."));
    }

    // ---- Class hierarchy queries ----

    public ClassHierarchyInfo getClassHierarchy(String fullyQualifiedClassName) {
        if (!initialized || view == null) return null;
        if (hierarchyCache.containsKey(fullyQualifiedClassName)) {
            return hierarchyCache.get(fullyQualifiedClassName);
        }

        try {
            List<SootMethod> methods = methodsByClass.get(fullyQualifiedClassName);
            if (methods == null || methods.isEmpty()) return null;

            // Get the class from the view
            JavaSootClass targetClass = null;
            for (JavaSootClass sc : view.getClasses().collect(Collectors.toList())) {
                if (sc.getType().toString().equals(fullyQualifiedClassName)) {
                    targetClass = sc;
                    break;
                }
            }
            if (targetClass == null) return null;

            String className = fullyQualifiedClassName;
            int lastDot = className.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
            String packageName = lastDot >= 0 ? className.substring(0, lastDot) : "";

            // Transitive superclasses
            List<String> superclasses = new ArrayList<>();
            try {
                Optional<JavaClassType> superOpt = targetClass.getSuperclass();
                while (superOpt.isPresent()) {
                    String superName = superOpt.get().toString();
                    superclasses.add(superName);
                    if ("java.lang.Object".equals(superName)) break;
                    Optional<JavaSootClass> parentOpt = view.getClass(superOpt.get());
                    if (parentOpt.isPresent()) {
                        superOpt = parentOpt.get().getSuperclass();
                    } else {
                        break;
                    }
                }
            } catch (Exception ignored) {}

            // Interfaces
            List<String> interfaces = new ArrayList<>();
            try {
                for (ClassType iface : targetClass.getInterfaces()) {
                    interfaces.add(iface.toString());
                }
            } catch (Exception ignored) {}

            // Direct subclasses (scan all classes)
            List<String> directSubclasses = new ArrayList<>();
            ClassType targetType = targetClass.getType();
            for (JavaSootClass sc : view.getClasses().collect(Collectors.toList())) {
                try {
                    Optional<JavaClassType> parentOpt = sc.getSuperclass();
                    if (parentOpt.isPresent() && parentOpt.get().equals(targetType)) {
                        directSubclasses.add(sc.getType().toString());
                    }
                } catch (Exception ignored) {}
            }

            ClassHierarchyInfo info = new ClassHierarchyInfo(
                    className, simpleName, packageName, superclasses, interfaces, directSubclasses);
            hierarchyCache.put(fullyQualifiedClassName, info);
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Call graph text ----

    public String getCallGraphText() {
        if (!initialized || cg == null) return "";
        return cg.toString();
    }

    // ---- Method matching ----

    private MethodSignature findMethodSignature(MethodInfo method) {
        String className = method.getClassname();
        String methodName = method.getMethodName();

        List<String> bytecodeClassNames = bytecodeClassNamesForSourceClass(className);
        if (bytecodeClassNames.isEmpty()) return null;

        SourceMethodKey expected = SourceMethodKey.from(method);
        List<SootMethod> exactMatches = new ArrayList<>();
        List<SootMethod> returnAgnosticMatches = new ArrayList<>();

        for (String bytecodeClassName : bytecodeClassNames) {
            for (SootMethod m : methodsByClass.getOrDefault(bytecodeClassName, List.of())) {
                MethodSignature signature = m.getSignature();
                BytecodeMethodKey bytecodeKey = BytecodeMethodKey.from(signature, true);
                if (!expected.sameClassNameParams(bytecodeKey)
                        && !(uniqueOwnerFallback(className, bytecodeClassName)
                        && expected.sameMethodNameParams(bytecodeKey))) {
                    continue;
                }
                if (expected.returnType().equals(bytecodeKey.returnType())) {
                    exactMatches.add(m);
                } else {
                    returnAgnosticMatches.add(m);
                }
            }
        }
        if (exactMatches.size() == 1) {
            return exactMatches.get(0).getSignature();
        }
        if (exactMatches.isEmpty() && returnAgnosticMatches.size() == 1) {
            return returnAgnosticMatches.get(0).getSignature();
        }
        return parseSourceBackedSignature(method, bytecodeClassNames.size() == 1 ? bytecodeClassNames.get(0) : className);
    }

    private static MethodSignature parseSourceBackedSignature(MethodInfo method, String bytecodeClassName) {
        try {
            String owner = normalizeClassName(bytecodeClassName);
            String methodName = bytecodeMethodName(method);
            String returnType = normalizeType(method.getErasedReturnType());
            if ("<init>".equals(methodName)) {
                returnType = "void";
            }
            String params = String.join(",", sourceParameterTypes(method));
            return JavaIdentifierFactory.getInstance()
                    .parseMethodSignature("<" + owner + ": " + returnType + " " + methodName + "(" + params + ")>");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean uniqueOwnerFallback(String sourceClassName, String bytecodeClassName) {
        return !normalizeClassName(sourceClassName).equals(normalizeClassName(bytecodeClassName));
    }

    private List<String> bytecodeClassNamesForSourceClass(String sourceClassName) {
        if (sourceClassName == null || sourceClassName.isBlank() || methodsByClass == null || methodsByClass.isEmpty()) {
            return List.of();
        }
        if (methodsByClass.containsKey(sourceClassName)) {
            return List.of(sourceClassName);
        }
        String normalizedSource = normalizeClassName(sourceClassName);
        List<String> exactNormalized = methodsByClass.keySet().stream()
                .filter(name -> normalizeClassName(name).equals(normalizedSource))
                .sorted()
                .toList();
        if (!exactNormalized.isEmpty()) {
            return exactNormalized;
        }
        String simpleSource = simpleBytecodeClassName(sourceClassName);
        List<String> simpleMatches = methodsByClass.keySet().stream()
                .filter(name -> simpleBytecodeClassName(name).equals(simpleSource))
                .sorted()
                .toList();
        return simpleMatches.size() == 1 ? simpleMatches : List.of();
    }

    private static String normalizeClassName(String className) {
        if (className == null) {
            return "";
        }
        return className.replace('/', '.')
                .replace("L;", "")
                .replaceAll("^L", "")
                .replaceAll("^class\\s+", "")
                .trim();
    }

    private static String simpleBytecodeClassName(String className) {
        String value = normalizeClassName(className);
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static boolean parametersCompatibleForSingleCandidate(MethodSignature sig, MethodInfo method) {
        List<String> bytecodeParams = BytecodeMethodKey.from(sig, false).parameterTypes();
        List<String> sourceParams = SourceMethodKey.from(method).parameterTypes();
        if (bytecodeParams.size() != sourceParams.size()) {
            return false;
        }
        for (int i = 0; i < bytecodeParams.size(); i++) {
            String left = bytecodeParams.get(i);
            String right = sourceParams.get(i);
            if (left.equals(right)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private record SourceMethodKey(String className, String methodName,
                                   List<String> parameterTypes, String returnType) {
        private SourceMethodKey {
            parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
            returnType = normalizeType(returnType);
        }

        private static SourceMethodKey from(MethodInfo method) {
            return new SourceMethodKey(method.getClassname(), bytecodeMethodName(method),
                    sourceParameterTypes(method),
                    normalizeType(method.getErasedReturnType()));
        }

        private static SourceMethodKey from(BytecodeMethodKey key) {
            return new SourceMethodKey(key.className(), key.methodName(), key.parameterTypes(), key.returnType());
        }

        private boolean sameClassNameParams(BytecodeMethodKey key) {
            return className.equals(key.className())
                    && methodName.equals(key.methodName())
                    && parameterTypes.equals(key.parameterTypes());
        }

        private boolean sameClassName(BytecodeMethodKey key) {
            return className.equals(key.className()) && methodName.equals(key.methodName());
        }

        private boolean sameMethodNameParams(BytecodeMethodKey key) {
            return methodName.equals(key.methodName()) && parameterTypes.equals(key.parameterTypes());
        }
    }

    private record BytecodeMethodKey(String className, String methodName,
                                     List<String> parameterTypes, String returnType) {
        private BytecodeMethodKey {
            parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
            returnType = normalizeType(returnType);
        }

        private static BytecodeMethodKey from(MethodSignature signature, boolean includeReturn) {
            String owner = signature.getDeclClassType() != null ? signature.getDeclClassType().toString() : "";
            List<String> params = signature.getParameterTypes().stream()
                    .map(Object::toString)
                    .map(CallGraphGenerator::normalizeType)
                    .toList();
            String ret = includeReturn ? normalizeType(signature.getType().toString()) : "";
            return new BytecodeMethodKey(owner, signature.getName(), params, ret);
        }
    }

    private enum SourceClassKind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, UNKNOWN
    }

    private record SourceClassSummary(SourceClassKind kind, Set<String> recordComponents) {
        private static SourceClassSummary from(List<MethodInfo> methods) {
            if (methods == null || methods.isEmpty()) {
                return new SourceClassSummary(SourceClassKind.UNKNOWN, Set.of());
            }
            Path sourceFile = methods.get(0).getSourceFile();
            if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
                return new SourceClassSummary(SourceClassKind.UNKNOWN, Set.of());
            }
            try {
                String source = Files.readString(sourceFile);
                String simpleName = simpleClassName(methods.get(0).getClassname());
                SourceClassKind kind = inferSourceClassKind(source, simpleName);
                Set<String> components = kind == SourceClassKind.RECORD
                        ? inferRecordComponents(source, simpleName)
                        : Set.of();
                return new SourceClassSummary(kind, components);
            } catch (Exception ignored) {
                return new SourceClassSummary(SourceClassKind.UNKNOWN, Set.of());
            }
        }

        private static String simpleClassName(String className) {
            if (className == null || className.isBlank()) {
                return "";
            }
            String simple = className.substring(className.lastIndexOf('.') + 1);
            int nested = simple.lastIndexOf('$');
            return nested >= 0 ? simple.substring(nested + 1) : simple;
        }

        private static SourceClassKind inferSourceClassKind(String source, String simpleName) {
            if (source == null || simpleName == null || simpleName.isBlank()) {
                return SourceClassKind.UNKNOWN;
            }
            String name = java.util.regex.Pattern.quote(simpleName);
            if (source.matches("(?s).*\\b@interface\\s+" + name + "\\b.*")) {
                return SourceClassKind.ANNOTATION;
            }
            if (source.matches("(?s).*\\benum\\s+" + name + "\\b.*")) {
                return SourceClassKind.ENUM;
            }
            if (source.matches("(?s).*\\brecord\\s+" + name + "\\s*\\(.*")) {
                return SourceClassKind.RECORD;
            }
            if (source.matches("(?s).*\\binterface\\s+" + name + "\\b.*")) {
                return SourceClassKind.INTERFACE;
            }
            if (source.matches("(?s).*\\bclass\\s+" + name + "\\b.*")) {
                return SourceClassKind.CLASS;
            }
            return SourceClassKind.UNKNOWN;
        }

        private static Set<String> inferRecordComponents(String source, String simpleName) {
            int recordIndex = source.indexOf("record " + simpleName);
            if (recordIndex < 0) {
                return Set.of();
            }
            int open = source.indexOf('(', recordIndex);
            if (open < 0) {
                return Set.of();
            }
            int close = matchingParen(source, open);
            if (close <= open) {
                return Set.of();
            }
            Set<String> names = new LinkedHashSet<>();
            for (String component : splitTopLevelCommas(source.substring(open + 1, close))) {
                String normalized = component
                        .replaceAll("@[\\w.$]+(?:\\s*\\([^)]*\\))?", " ")
                        .replaceAll("\\b(final)\\b", " ")
                        .trim();
                String[] tokens = normalized.split("\\s+");
                if (tokens.length >= 2) {
                    String candidate = tokens[tokens.length - 1].replace("...", "").trim();
                    if (candidate.matches("[A-Za-z_$][\\w$]*")) {
                        names.add(candidate);
                    }
                }
            }
            return Set.copyOf(names);
        }

        private static int matchingParen(String source, int open) {
            int depth = 0;
            for (int i = open; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    private static List<String> normalizeSourceParameters(String methodSignature) {
        if (methodSignature == null) {
            return List.of();
        }
        int open = methodSignature.indexOf('(');
        int close = methodSignature.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return List.of();
        }
        String raw = methodSignature.substring(open + 1, close).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> params = new ArrayList<>();
        for (String param : splitTopLevelCommas(raw)) {
            String type = stripParameterName(param);
            params.add(normalizeType(type));
        }
        return params;
    }

    private static List<String> sourceParameterTypes(MethodInfo method) {
        if (method.getErasedParameterTypes() != null && !method.getErasedParameterTypes().isEmpty()) {
            return method.getErasedParameterTypes().stream()
                    .map(CallGraphGenerator::normalizeType)
                    .toList();
        }
        return normalizeSourceParameters(method.getMethodSignature());
    }

    private static List<String> splitTopLevelCommas(String raw) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                parts.add(raw.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(raw.substring(start));
        return parts;
    }

    private static String stripParameterName(String rawParam) {
        String s = rawParam == null ? "" : rawParam.trim();
        s = s.replaceAll("@[\\w.$]+(?:\\s*\\([^)]*\\))?", " ");
        s = s.replaceAll("\\b(final|volatile)\\b", " ").trim();
        s = s.replace("...", "[]");
        String[] tokens = s.trim().split("\\s+");
        if (tokens.length <= 1) {
            return s;
        }
        String last = tokens[tokens.length - 1];
        if (last.matches("[A-Za-z_$][\\w$]*")) {
            return s.substring(0, s.lastIndexOf(last)).trim();
        }
        return s;
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String t = type.trim().replace("...", "[]").replace('$', '.');
        String previous;
        do {
            previous = t;
            t = t.replaceAll("<[^<>]*>", "");
        } while (!previous.equals(t));
        t = t.replaceAll("\\s+", "");
        t = primitiveDescriptorToJava(t);
        if (t.endsWith("[]")) {
            String component = t.substring(0, t.length() - 2);
            String normalizedComponent = normalizeType(component);
            return normalizedComponent.isBlank() ? t : normalizedComponent + "[]";
        }
        if (!t.contains(".") && JAVA_LANG_SIMPLE_TYPES.contains(t)) {
            return "java.lang." + t;
        }
        return t;
    }

    private static final Set<String> JAVA_LANG_SIMPLE_TYPES = Set.of(
            "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float",
            "Integer", "Long", "Number", "Object", "Short", "String", "StringBuilder",
            "StringBuffer", "Throwable", "Exception", "RuntimeException", "Void");

    private static String primitiveDescriptorToJava(String t) {
        return switch (t) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            case "V" -> "void";
            default -> t;
        };
    }

    private static String bytecodeMethodName(MethodInfo method) {
        return method.isConstructor() ? "<init>" : method.getMethodName();
    }

    private static boolean isClassInitializer(MethodSignature sig) {
        return "<clinit>".equals(sig.getName());
    }

    private static boolean containsClassFiles(Path dir) {
        // Walk the full subtree: deeply-nested packages (e.g. commons-numbers'
        // org/apache/commons/numbers/<module>/) push .class files past any small
        // fixed depth, so a bounded walk would wrongly report "no classes".
        // anyMatch short-circuits on the first .class, so this stays cheap.
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Accessors ----

    public CallGraphResult getCachedResult(String methodUri) {
        return cache.get(methodUri);
    }

    public void clearCache() {
        cache.clear();
    }

    public Map<String, Integer> getCacheStats() {
        return Map.of(
                "cached_entries", cache.size(),
                "total_callers", cache.values().stream().mapToInt(CallGraphResult::getCallerCount).sum(),
                "total_callees", cache.values().stream().mapToInt(CallGraphResult::getCalleeCount).sum()
        );
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }
}
