package org.assertlab.cocox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
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
 * - Provides signature→methodId reverse lookup
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

    // Reverse lookup: SootUp signature string → project methodId
    private Map<String, String> signatureToMethodId;

    // Class hierarchy cache
    private final Map<String, ClassHierarchyInfo> hierarchyCache = new HashMap<>();

    public enum Algorithm {
        NONE, CHA, RTA, AUTO
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
        this.signatureToMethodId = new HashMap<>();
        this.initialized = false;
    }

    public boolean initialize() {
        if (initialized) return true;

        try {
            Set<String> seenPaths = new LinkedHashSet<>();
            List<AnalysisInputLocation> inputLocations = new ArrayList<>();

            for (Path cp : projectMetadata.getClasspath()) {
                if (cp.toFile().isDirectory() && containsClassFiles(cp)) {
                    if (seenPaths.add(cp.toAbsolutePath().toString())) {
                        inputLocations.add(new JavaClassPathAnalysisInputLocation(cp.toString()));
                    }
                }
            }

            Path projectRoot = projectMetadata.getProjectPath();
            try (var stream = Files.walk(projectRoot, 4)) {
                stream.filter(p -> {
                            Path fn = p.getFileName();
                            return fn != null && fn.toString().equals("classes")
                                    && p.getParent() != null
                                    && "target".equals(p.getParent().getFileName().toString());
                        })
                        .filter(p -> p.toFile().isDirectory())
                        .filter(p -> containsClassFiles(p))
                        .forEach(p -> {
                            if (seenPaths.add(p.toAbsolutePath().toString())) {
                                inputLocations.add(new JavaClassPathAnalysisInputLocation(p.toString()));
                            }
                        });
            } catch (Exception ignored) {}

            if (inputLocations.isEmpty()) {
                System.err.println("[CallGraphGenerator] no class directories found under " + projectMetadata.getProjectPath());
                return false;
            }

            view = new JavaView(inputLocations);

            List<JavaSootClass> allClasses = view.getClasses().sequential().collect(Collectors.toList());
            List<MethodSignature> entryPoints = new ArrayList<>();
            methodsByClass = new HashMap<>();

            for (JavaSootClass sootClass : allClasses) {
                String className = sootClass.getType().toString();
                List<SootMethod> methods = new ArrayList<>();
                for (SootMethod m : sootClass.getMethods()) {
                    methods.add(m);
                    if (m.hasBody() && m.isConcrete() && !isSpecialMethod(m.getSignature())) {
                        entryPoints.add(m.getSignature());
                    }
                }
                methodsByClass.put(className, methods);
            }

            if (algorithm == Algorithm.RTA) {
                cg = new RapidTypeAnalysisAlgorithm(view).initialize(entryPoints);
            } else {
                cg = new ClassHierarchyAnalysisAlgorithm(view).initialize(entryPoints);
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

        String cacheKey = method.getId();
        if (cache.containsKey(cacheKey)) return cache.get(cacheKey);

        try {
            long startTime = System.currentTimeMillis();

            Set<CallGraphEdge> callers = new HashSet<>();
            Set<CallGraphEdge> callees = new HashSet<>();

            MethodSignature sig = findMethodSignature(method);
            if (sig != null) {
                signatureToMethodId.put(sig.toString(), method.getId());

                for (CallGraph.Call call : cg.callsTo(sig)) {
                    MethodSignature callerSig = call.getSourceMethodSignature();
                    if (!isSpecialMethod(callerSig)) {
                        callers.add(edgeFor(callerSig));
                    }
                }
                for (CallGraph.Call call : cg.callsFrom(sig)) {
                    MethodSignature calleeSig = call.getTargetMethodSignature();
                    if (!isSpecialMethod(calleeSig)) {
                        callees.add(edgeFor(calleeSig));
                    }
                }
            }

            long endTime = System.currentTimeMillis();

            CallGraphResult result = new CallGraphResult.Builder()
                    .methodId(method.getId())
                    .methodName(method.getMethodName())
                    .classname(method.getClassname())
                    .callers(callers)
                    .callees(callees)
                    .algorithm(algorithm.toString())
                    .generationTime(endTime - startTime)
                    .build();

            cache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, CallGraphResult> generateForMethods(List<MethodInfo> methods) {
        if (!initialized) {
            throw new IllegalStateException("CallGraphGenerator not initialized. Call initialize() first.");
        }
        indexMethodSignatures(methods);
        return methods.stream()
                .map(this::generateForMethod)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(CallGraphResult::getMethodId, r -> r));
    }

    // ---- Signature resolution ----

    public String resolveSignatureToMethodId(String sootUpSignature) {
        return signatureToMethodId != null ? signatureToMethodId.get(sootUpSignature) : null;
    }

    private void indexMethodSignatures(List<MethodInfo> methods) {
        for (MethodInfo method : methods) {
            MethodSignature sig = findMethodSignature(method);
            if (sig != null) {
                signatureToMethodId.put(sig.toString(), method.getId());
            }
        }
    }

    private CallGraphEdge edgeFor(MethodSignature sig) {
        String raw = sig.toString();
        String methodId = resolveSignatureToMethodId(raw);
        String declaringClass = sig.getDeclClassType() != null ? sig.getDeclClassType().toString() : "";
        String methodName = sig.getName();
        if (methodId != null && !methodId.isBlank()) {
            return CallGraphEdge.resolved(methodId, raw, declaringClass, methodName);
        }
        return CallGraphEdge.unresolved(raw, declaringClass, methodName);
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

        List<SootMethod> classMethods = methodsByClass.get(className);
        if (classMethods == null) return null;

        List<String> expectedParams = parseParamTypes(method.getMethodSignature());

        SootMethod best = null;
        int bestScore = Integer.MIN_VALUE;
        String bestSigStr = null;

        for (SootMethod m : classMethods) {
            if (!methodName.equals(m.getName())) continue;
            int score = m.hasBody() ? 5 : 0;

            if (!expectedParams.isEmpty() && paramsMatch(m.getParameterTypes(), expectedParams)) {
                score += 10;
            }
            if (returnTypeMatches(m.getSignature(), method.getErasedReturnType())) {
                score += 4;
            }

            String sigStr = m.getSignature().toString();
            if (score > bestScore || (score == bestScore && (bestSigStr == null || sigStr.compareTo(bestSigStr) < 0))) {
                best = m;
                bestScore = score;
                bestSigStr = sigStr;
            }
        }
        return best != null ? best.getSignature() : null;
    }

    private static List<String> parseParamTypes(String methodSignature) {
        if (methodSignature == null) return List.of();
        int open = methodSignature.indexOf('(');
        int close = methodSignature.lastIndexOf(')');
        if (open < 0 || close <= open) return List.of();
        String paramStr = methodSignature.substring(open + 1, close).trim();
        if (paramStr.isEmpty()) return List.of();
        List<String> types = new ArrayList<>();
        for (String p : paramStr.split(",")) {
            String trimmed = p.trim().replaceAll("<.*?>", "");
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 1) types.add(toSimpleName(parts[0]));
        }
        return types;
    }

    private static boolean paramsMatch(List<?> sootParams, List<String> expectedSimple) {
        if (sootParams.size() != expectedSimple.size()) return false;
        for (int i = 0; i < sootParams.size(); i++) {
            String sootSimple = toSimpleName(sootParams.get(i).toString().replaceAll("<.*?>", ""));
            if (!sootSimple.equals(expectedSimple.get(i))) return false;
        }
        return true;
    }

    private static boolean returnTypeMatches(MethodSignature signature, String expectedErasedReturnType) {
        if (expectedErasedReturnType == null || expectedErasedReturnType.isBlank()) {
            return false;
        }
        String sootReturn = toSimpleName(signature.getType().toString().replaceAll("<.*?>", ""));
        String expected = toSimpleName(expectedErasedReturnType.replaceAll("<.*?>", ""));
        return sootReturn.equals(expected);
    }

    private static String toSimpleName(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return fqcn;
        boolean isArray = fqcn.endsWith("[]");
        String base = isArray ? fqcn.substring(0, fqcn.length() - 2) : fqcn;
        int lastDot = base.lastIndexOf('.');
        String simple = (lastDot >= 0) ? base.substring(lastDot + 1) : base;
        return isArray ? simple + "[]" : simple;
    }

    private static boolean isSpecialMethod(MethodSignature sig) {
        String name = sig.getName();
        return "<init>".equals(name) || "<clinit>".equals(name);
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

    public CallGraphResult getCachedResult(String methodId) {
        return cache.get(methodId);
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
