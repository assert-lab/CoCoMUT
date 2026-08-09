package org.assertlab.cocomut.source;

import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.adaption.TypeAdaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves inherited method documentation without confusing Spoon's top-most
 * method definitions with the standard doclet's nearest-supertype search.
 *
 * <p>The resolver deliberately keeps declared and effective documentation
 * separate. It uses Spoon only to decide Java override relationships and
 * traverses the hierarchy in Javadoc order: superclass ancestry first,
 * interfaces in declaration order second, and {@code java.lang.Object} last.
 * Missing documentation items are resolved independently because Javadoc can
 * inherit a parameter or return contract even when the overriding method does
 * not contain an explicit {@code inheritDoc} tag.
 */
final class InheritedJavadocResolver {
    private static final int MAX_VISITED_TYPES = 256;
    private static final Pattern INHERIT_DOC = Pattern.compile("\\{@inheritDoc(?:\\s+([^\\s}]+))?\\s*}",
            Pattern.CASE_INSENSITIVE);

    private InheritedJavadocResolver() {
    }

    static Resolution resolve(CtMethod<?> focal,
                              boolean usesInheritDoc,
                              Documentation declaredDocumentation,
                              Function<CtMethod<?>, Documentation> documentationExtractor) {
        if (focal == null) {
            return Resolution.notApplicable(declaredDocumentation);
        }

        Traversal traversal = new Traversal(focal);
        traversal.run();
        List<Candidate> candidates = traversal.methods().stream()
                .map(method -> new Candidate(method.method(), method.relationship(), method.distance(),
                        method.searchOrder(), documentationExtractor.apply(method.method())))
                .toList();

        String resolution = inheritanceResolution(usesInheritDoc, candidates, traversal.unresolved());
        Map<String, Object> effective = effectiveDocumentation(
                focal, declaredDocumentation, documentationExtractor);
        List<Map<String, Object>> evidence = new ArrayList<>();
        candidates.forEach(candidate -> evidence.add(candidate.asMap()));
        traversal.unresolved().forEach(unresolved -> evidence.add(unresolved.asMap()));
        evidence.sort((left, right) -> Integer.compare(
                intValue(left.get("search_order")), intValue(right.get("search_order"))));

        long documented = candidates.stream()
                .filter(candidate -> candidate.documentation().availability() == Availability.PRESENT)
                .count();
        return new Resolution(
                resolution,
                usesInheritDoc || traversal.hasInheritanceEvidence(),
                List.copyOf(evidence),
                effective,
                evidence.size(),
                (int) documented,
                traversal.truncated());
    }

    private static String inheritanceResolution(boolean usesInheritDoc,
                                                List<Candidate> candidates,
                                                List<UnresolvedAncestor> unresolved) {
        if (candidates.stream().anyMatch(candidate ->
                candidate.documentation().availability() == Availability.PRESENT)) {
            return "resolved_candidate";
        }
        if (!unresolved.isEmpty() || candidates.stream().anyMatch(candidate ->
                candidate.documentation().availability().isIndeterminate())) {
            return "indeterminate";
        }
        if (!candidates.isEmpty()) {
            return "no_documentation";
        }
        return usesInheritDoc ? "unresolved" : "not_applicable";
    }

    private static Map<String, Object> effectiveDocumentation(
            CtMethod<?> focal,
            Documentation declared,
            Function<CtMethod<?>, Documentation> documentationExtractor) {
        Map<String, Object> effective = new LinkedHashMap<>();
        List<Map<String, Object>> params = new ArrayList<>();
        List<Map<String, Object>> typeParams = new ArrayList<>();
        List<Map<String, Object>> returns = new ArrayList<>();
        List<Map<String, Object>> throwsTags = new ArrayList<>();
        boolean[] incomplete = {false};

        effective.put("description", effectiveDescription(
                focal, declared.description(), documentationExtractor, incomplete));

        Map<String, String> declaredParams = namedTags(declared.structuredTags(), "params", "name");
        for (int index = 0; index < focal.getParameters().size(); index++) {
            String name = focal.getParameters().get(index).getSimpleName();
            String local = declaredParams.get(name);
            params.add(effectiveNamedItem("name", name, local, "param", index,
                    focal, documentationExtractor, incomplete));
        }

        Map<String, String> declaredTypeParams = namedTags(declared.structuredTags(), "params", "name");
        List<CtTypeParameter> focalTypeParams = focal.getFormalCtTypeParameters();
        for (int index = 0; index < focalTypeParams.size(); index++) {
            String name = focalTypeParams.get(index).getSimpleName();
            String local = firstNonNull(declaredTypeParams.get("<" + name + ">"), declaredTypeParams.get(name));
            typeParams.add(effectiveNamedItem("name", name, local, "type_param", index,
                    focal, documentationExtractor, incomplete));
        }

        String localReturn = firstText(declared.structuredTags(), "return");
        if (!"void".equals(normalizedTypeName(focal.getType()))) {
            returns.add(effectiveNamedItem(null, null, localReturn, "return", 0,
                    focal, documentationExtractor, incomplete));
        }

        Map<String, String> declaredThrows = namedTags(declared.structuredTags(), "throws", "type");
        Set<String> representedThrows = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : declaredThrows.entrySet()) {
            representedThrows.add(simpleTypeName(entry.getKey()));
            throwsTags.add(effectiveNamedItem("type", entry.getKey(), entry.getValue(), "throws", 0,
                    focal, documentationExtractor, incomplete));
        }
        for (CtTypeReference<?> thrown : focal.getThrownTypes()) {
            String type = normalizedTypeName(thrown);
            if (representedThrows.add(simpleTypeName(type))) {
                throwsTags.add(effectiveNamedItem("type", type, null, "throws", 0,
                        focal, documentationExtractor, incomplete));
            }
        }

        effective.put("type_params", typeParams);
        effective.put("params", params);
        effective.put("return", returns);
        effective.put("throws", throwsTags);
        effective.put("resolution", incomplete[0] ? "partial" : "complete");
        return effective;
    }

    private static Map<String, Object> effectiveDescription(
            CtMethod<?> focal,
            String local,
            Function<CtMethod<?>, Documentation> documentationExtractor,
            boolean[] incomplete) {
        ResolvedText inherited = resolveInherited(
                "description", 0, "", focal, documentationExtractor, explicitSupertype(local), 0);
        return effectiveItem(null, null, local, inherited, incomplete);
    }

    private static Map<String, Object> effectiveNamedItem(String nameKey,
                                                           String name,
                                                           String local,
                                                           String kind,
                                                           int position,
                                                           CtMethod<?> focal,
                                                           Function<CtMethod<?>, Documentation> documentationExtractor,
                                                           boolean[] incomplete) {
        ResolvedText inherited = resolveInherited(kind, position, name == null ? "" : name,
                focal, documentationExtractor, explicitSupertype(local), 0);
        return effectiveItem(nameKey, name, local, inherited, incomplete);
    }

    private static Map<String, Object> effectiveItem(String nameKey,
                                                      String name,
                                                      String local,
                                                      ResolvedText inherited,
                                                      boolean[] incomplete) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (nameKey != null) {
            item.put(nameKey, name == null ? "" : name);
        }
        String localText = local == null ? "" : local.trim();
        boolean explicit = containsInheritDoc(localText);
        if (!localText.isBlank() && !explicit) {
            item.put("text", localText);
            item.put("source", "declared");
            item.put("inheritance_mode", "declared");
            item.put("resolution", "resolved");
            return item;
        }
        if (inherited.resolved()) {
            String text = explicit ? replaceInheritDoc(localText, inherited.text()) : inherited.text();
            item.put("text", text);
            item.put("source", "inherited");
            item.put("inheritance_mode", explicit ? "explicit_inheritdoc" : "implicit_missing_item");
            item.put("inherited_from", inherited.candidate().declaringType());
            item.put("inherited_method_uri", inherited.candidate().documentation().methodUri());
            item.put("hierarchy_distance", inherited.candidate().distance());
            item.put("resolution", "resolved");
            return item;
        }

        boolean indeterminate = explicit || inherited.indeterminate();
        item.put("text", localText);
        item.put("source", indeterminate ? "indeterminate" : "missing");
        item.put("inheritance_mode", explicit ? "explicit_inheritdoc" : "implicit_missing_item");
        item.put("resolution", indeterminate ? "indeterminate" : "missing");
        incomplete[0] |= indeterminate;
        return item;
    }

    /**
     * Resolves one documentation item by repeatedly applying the standard
     * doclet's supertype search. The search chooses an overriding declaration
     * before inspecting whether that declaration contains the requested item;
     * if the item is omitted, resolution restarts from that declaration. This
     * prevents an empty item on one interface branch from incorrectly falling
     * through to a sibling interface.
     */
    private static ResolvedText resolveInherited(
            String kind,
            int position,
            String name,
            CtMethod<?> method,
            Function<CtMethod<?>, Documentation> documentationExtractor,
            String requiredSupertype,
            int depth) {
        if (method == null || depth >= MAX_VISITED_TYPES) {
            return new ResolvedText("", Candidate.empty(), false, method != null);
        }

        Traversal traversal = new Traversal(method);
        traversal.run();
        List<Candidate> candidates = traversal.methods().stream()
                .map(ancestor -> new Candidate(ancestor.method(), ancestor.relationship(), ancestor.distance(),
                        ancestor.searchOrder(), documentationExtractor.apply(ancestor.method())))
                .toList();
        SelectedEvidence selected = selectEvidence(candidates, traversal.unresolved(), requiredSupertype);
        if (selected.unresolved() != null) {
            return new ResolvedText("", Candidate.empty(), false, true);
        }
        Candidate candidate = selected.candidate();
        if (candidate == null) {
            return new ResolvedText("", Candidate.empty(), false,
                    traversal.truncated() || !requiredSupertype.isBlank());
        }

        Documentation documentation = candidate.documentation();
        if (documentation.availability().isIndeterminate()) {
            return new ResolvedText("", Candidate.empty(), false, true);
        }
        String text = documentation.availability() == Availability.PRESENT
                ? candidateText(candidate, kind, position, name)
                : "";
        if (text.isBlank()) {
            return resolveInherited(kind, position, name, candidate.method(), documentationExtractor, "", depth + 1);
        }
        if (!containsInheritDoc(text)) {
            return new ResolvedText(text, candidate, true, false);
        }

        ResolvedText parent = resolveInherited(kind, position, name, candidate.method(), documentationExtractor,
                explicitSupertype(text), depth + 1);
        if (!parent.resolved()) {
            return parent;
        }
        Candidate provenance = hasTextBesidesInheritDoc(text) ? candidate : parent.candidate();
        return new ResolvedText(replaceInheritDoc(text, parent.text()), provenance, true, parent.indeterminate());
    }

    private static SelectedEvidence selectEvidence(List<Candidate> candidates,
                                                    List<UnresolvedAncestor> unresolved,
                                                    String requiredSupertype) {
        Candidate selectedCandidate = candidates.stream()
                .filter(candidate -> requiredSupertype.isBlank()
                        || typeNameMatches(requiredSupertype, candidate.declaringType()))
                .min((left, right) -> Integer.compare(left.searchOrder(), right.searchOrder()))
                .orElse(null);
        UnresolvedAncestor selectedUnresolved = unresolved.stream()
                .filter(candidate -> requiredSupertype.isBlank()
                        || typeNameMatches(requiredSupertype, candidate.declaringType()))
                .min((left, right) -> Integer.compare(left.searchOrder(), right.searchOrder()))
                .orElse(null);
        if (selectedCandidate == null) {
            return new SelectedEvidence(null, selectedUnresolved);
        }
        if (selectedUnresolved == null || selectedCandidate.searchOrder() < selectedUnresolved.searchOrder()) {
            return new SelectedEvidence(selectedCandidate, null);
        }
        return new SelectedEvidence(null, selectedUnresolved);
    }

    private static boolean hasTextBesidesInheritDoc(String text) {
        return !INHERIT_DOC.matcher(text == null ? "" : text).replaceAll("").trim().isEmpty();
    }

    private static String candidateText(Candidate candidate, String kind, int position, String requestedName) {
        Documentation documentation = candidate.documentation();
        return switch (kind) {
            case "description" -> documentation.description();
            case "param" -> parameterText(candidate.method(), documentation.structuredTags(), position, false);
            case "type_param" -> parameterText(candidate.method(), documentation.structuredTags(), position, true);
            case "return" -> firstText(documentation.structuredTags(), "return");
            case "throws" -> namedTagText(documentation.structuredTags(), "throws", "type", requestedName);
            default -> "";
        };
    }

    private static String parameterText(CtMethod<?> method,
                                        Map<String, Object> tags,
                                        int position,
                                        boolean typeParameter) {
        String candidateName;
        if (typeParameter) {
            if (position >= method.getFormalCtTypeParameters().size()) {
                return "";
            }
            candidateName = method.getFormalCtTypeParameters().get(position).getSimpleName();
            String angled = namedTagText(tags, "params", "name", "<" + candidateName + ">");
            return angled.isBlank() ? namedTagText(tags, "params", "name", candidateName) : angled;
        }
        if (position >= method.getParameters().size()) {
            return "";
        }
        candidateName = method.getParameters().get(position).getSimpleName();
        return namedTagText(tags, "params", "name", candidateName);
    }

    private static String namedTagText(Map<String, Object> tags,
                                       String field,
                                       String nameKey,
                                       String requestedName) {
        for (Map<String, String> item : tagMaps(tags, field)) {
            if (typeNameMatches(requestedName, item.getOrDefault(nameKey, ""))) {
                return item.getOrDefault("text", "");
            }
        }
        return "";
    }

    private static Map<String, String> namedTags(Map<String, Object> tags, String field, String nameKey) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map<String, String> item : tagMaps(tags, field)) {
            String name = item.getOrDefault(nameKey, "");
            if (!name.isBlank()) {
                values.putIfAbsent(name, item.getOrDefault("text", ""));
            }
        }
        return values;
    }

    private static List<Map<String, String>> tagMaps(Map<String, Object> tags, String field) {
        Object raw = tags == null ? null : tags.get(field);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, String>> values = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, String> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> converted.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
            values.add(converted);
        }
        return values;
    }

    private static String firstText(Map<String, Object> tags, String field) {
        Object raw = tags == null ? null : tags.get(field);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object first = list.get(0);
        if (first instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text == null ? "" : String.valueOf(text);
        }
        return first == null ? "" : String.valueOf(first);
    }

    private static String explicitSupertype(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = INHERIT_DOC.matcher(text);
        return matcher.find() && matcher.group(1) != null ? matcher.group(1).trim() : "";
    }

    private static boolean containsInheritDoc(String text) {
        return text != null && INHERIT_DOC.matcher(text).find();
    }

    private static String replaceInheritDoc(String text, String inherited) {
        return INHERIT_DOC.matcher(text == null ? "" : text)
                .replaceAll(Matcher.quoteReplacement(inherited == null ? "" : inherited))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    private static boolean typeNameMatches(String left, String right) {
        String normalizedLeft = normalizeTagType(left);
        String normalizedRight = normalizeTagType(right);
        return normalizedLeft.equals(normalizedRight)
                || simpleTypeName(normalizedLeft).equals(simpleTypeName(normalizedRight));
    }

    private static String normalizeTagType(String type) {
        if (type == null) {
            return "";
        }
        return type.replaceAll("<.*>", "")
                .replace("...", "[]")
                .replace("$", ".")
                .replaceAll("\\s+", "")
                .trim();
    }

    private static String simpleTypeName(String type) {
        String normalized = normalizeTagType(type);
        int index = normalized.lastIndexOf('.');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private static String normalizedTypeName(CtTypeReference<?> type) {
        if (type == null) {
            return "";
        }
        try {
            return normalizeTagType(type.getQualifiedName());
        } catch (RuntimeException | StackOverflowError ignored) {
            return normalizeTagType(type.toString());
        }
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
    }

    enum Availability {
        PRESENT("present"),
        ABSENT("absent"),
        SOURCE_UNAVAILABLE("source_unavailable"),
        PARSE_FAILED("parse_failed"),
        PARTIAL_RESOLUTION("partial_resolution"),
        NOT_ANALYZED("not_analyzed");

        private final String jsonValue;

        Availability(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        String jsonValue() {
            return jsonValue;
        }

        boolean isIndeterminate() {
            return this != PRESENT && this != ABSENT;
        }
    }

    record Documentation(Availability availability,
                         String methodUri,
                         String rawJavadoc,
                         String description,
                         Map<String, Object> structuredTags,
                         String parser,
                         String parseConfidence,
                         String diagnostic) {
        Documentation {
            availability = availability == null ? Availability.PARTIAL_RESOLUTION : availability;
            methodUri = methodUri == null ? "" : methodUri;
            rawJavadoc = rawJavadoc == null ? "" : rawJavadoc;
            description = description == null ? "" : description;
            structuredTags = structuredTags == null ? Map.of() : Map.copyOf(structuredTags);
            parser = parser == null ? "" : parser;
            parseConfidence = parseConfidence == null ? "" : parseConfidence;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    record Resolution(String resolution,
                      boolean hasInheritanceEvidence,
                      List<Map<String, Object>> candidates,
                      Map<String, Object> effectiveStructuredTags,
                      int candidateCount,
                      int documentedCandidateCount,
                      boolean truncated) {
        static Resolution notApplicable(Documentation declared) {
            boolean present = !declared.description().isBlank();
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("text", declared.description());
            description.put("source", present ? "declared" : "missing");
            description.put("inheritance_mode", present ? "declared" : "implicit_missing_item");
            description.put("resolution", present ? "resolved" : "missing");
            return new Resolution("not_applicable", false, List.of(),
                    Map.of("description", description,
                            "type_params", List.of(), "params", List.of(), "return", List.of(),
                            "throws", List.of(), "resolution", "complete"),
                    0, 0, false);
        }
    }

    private record Candidate(CtMethod<?> method,
                             String relationship,
                             int distance,
                             int searchOrder,
                             Documentation documentation) {
        static Candidate empty() {
            return new Candidate(null, "", 0, 0,
                    new Documentation(Availability.PARTIAL_RESOLUTION, "", "", "", Map.of(), "", "", ""));
        }

        String declaringType() {
            if (method == null || method.getDeclaringType() == null) {
                return "";
            }
            return method.getDeclaringType().getQualifiedName();
        }

        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            CtType<?> owner = method.getDeclaringType();
            value.put("declaring_type", declaringType());
            value.put("declaring_type_kind", owner != null && owner.isInterface() ? "interface" : "class");
            value.put("relationship", relationship);
            value.put("method_uri", documentation.methodUri());
            value.put("method_signature", method.getSignature());
            value.put("hierarchy_distance", distance);
            value.put("search_order", searchOrder);
            value.put("javadoc_availability", documentation.availability().jsonValue());
            value.put("raw_javadoc", documentation.rawJavadoc());
            value.put("structured_tags", documentation.structuredTags());
            value.put("parser", documentation.parser());
            value.put("parse_confidence", documentation.parseConfidence());
            value.put("resolution_confidence", "high");
            if (!documentation.diagnostic().isBlank()) {
                value.put("diagnostic", documentation.diagnostic());
            }
            return value;
        }
    }

    private record ResolvedText(String text, Candidate candidate, boolean resolved, boolean indeterminate) {
    }

    private record SelectedEvidence(Candidate candidate, UnresolvedAncestor unresolved) {
    }

    private record AncestorMethod(CtMethod<?> method, String relationship, int distance, int searchOrder) {
    }

    private record UnresolvedAncestor(String declaringType,
                                      String relationship,
                                      String methodSignature,
                                      int distance,
                                      int searchOrder,
                                      Availability availability,
                                      String diagnostic) {
        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("declaring_type", declaringType);
            value.put("declaring_type_kind", "unknown");
            value.put("relationship", relationship);
            value.put("method_uri", "");
            value.put("method_signature", methodSignature);
            value.put("hierarchy_distance", distance);
            value.put("search_order", searchOrder);
            value.put("javadoc_availability", availability.jsonValue());
            value.put("raw_javadoc", "");
            value.put("structured_tags", Map.of());
            value.put("parser", "");
            value.put("parse_confidence", "none");
            value.put("resolution_confidence", "low");
            value.put("diagnostic", diagnostic);
            return value;
        }
    }

    /** Traverses supertypes in the automatic-search order defined by Javadoc. */
    private static final class Traversal {
        private final CtMethod<?> focal;
        private final List<AncestorMethod> methods = new ArrayList<>();
        private final List<UnresolvedAncestor> unresolved = new ArrayList<>();
        private final Set<String> visitedTypes = new LinkedHashSet<>();
        private final Set<String> visitedMethods = new LinkedHashSet<>();
        private CtTypeReference<?> deferredObject;
        private int deferredObjectDistance;
        private int searchOrder;
        private boolean truncated;

        private Traversal(CtMethod<?> focal) {
            this.focal = focal;
        }

        void run() {
            CtType<?> owner = focal.getDeclaringType();
            if (owner == null) {
                return;
            }
            visit(owner.getSuperclass(), 1);
            for (CtTypeReference<?> superInterface : owner.getSuperInterfaces()) {
                visit(superInterface, 1);
            }
            if (deferredObject == null && !"java.lang.Object".equals(owner.getQualifiedName())) {
                // Spoon omits the implicit Object superclass from some source
                // declarations, but Javadoc always performs this final phase.
                deferredObject = owner.getFactory().Type().objectType();
                deferredObjectDistance = 1;
            }
            if (deferredObject != null && !truncated) {
                visitResolved(deferredObject, deferredObjectDistance, true);
            }
        }

        private void visit(CtTypeReference<?> reference, int distance) {
            if (reference == null || truncated) {
                return;
            }
            String name = safeQualifiedName(reference);
            if ("java.lang.Object".equals(name)) {
                if (deferredObject == null || distance < deferredObjectDistance) {
                    deferredObject = reference;
                    deferredObjectDistance = distance;
                }
                return;
            }
            visitResolved(reference, distance, false);
        }

        private void visitResolved(CtTypeReference<?> reference, int distance, boolean objectPhase) {
            if (reference == null || truncated) {
                return;
            }
            String name = safeQualifiedName(reference);
            if (!visitedTypes.add(name)) {
                return;
            }
            if (visitedTypes.size() > MAX_VISITED_TYPES) {
                truncated = true;
                unresolved.add(new UnresolvedAncestor(name, relationship(reference), "", distance, ++searchOrder,
                        Availability.NOT_ANALYZED, "hierarchy_traversal_limit"));
                return;
            }

            CtType<?> declaration = typeDeclaration(reference);
            if (declaration == null) {
                boolean matched = collectReferenceOnlyMethod(
                        reference, name, relationship(reference), distance, objectPhase);
                if (!matched) {
                    unresolved.add(new UnresolvedAncestor(name, relationship(reference), "", distance, ++searchOrder,
                            Availability.PARTIAL_RESOLUTION, "supertype_declaration_unavailable"));
                }
                visit(reference.getSuperclass(), distance + 1);
                for (CtTypeReference<?> superInterface : reference.getSuperInterfaces()) {
                    visit(superInterface, distance + 1);
                }
                return;
            }

            String relationship = declaration.isInterface() ? "superinterface" : "superclass";
            boolean matchedDeclaration = false;
            for (CtMethod<?> candidate : declaration.getMethodsByName(focal.getSimpleName())) {
                if (!overrides(candidate, objectPhase)) {
                    continue;
                }
                matchedDeclaration = true;
                String methodKey = name + "#" + candidate.getSignature();
                if (visitedMethods.add(methodKey)) {
                    methods.add(new AncestorMethod(candidate, relationship, distance, ++searchOrder));
                }
            }
            if (!matchedDeclaration) {
                collectReferenceOnlyMethod(reference, name, relationship, distance, objectPhase);
            }

            visit(declaration.getSuperclass(), distance + 1);
            for (CtTypeReference<?> superInterface : declaration.getSuperInterfaces()) {
                visit(superInterface, distance + 1);
            }
        }

        private boolean overrides(CtMethod<?> candidate, boolean objectPhase) {
            try {
                if (focal.isOverriding(candidate)) {
                    return true;
                }
                return objectPhase && focal.getDeclaringType() != null && focal.getDeclaringType().isInterface()
                        && new TypeAdaptor(focal).isSameSignature(focal, candidate);
            } catch (RuntimeException | StackOverflowError ignored) {
                return false;
            }
        }

        /**
         * Retains override evidence exposed only as an executable reference.
         * Spoon uses this representation for classpath/shadow methods whose
         * source declaration and Javadoc are unavailable.
         */
        private boolean collectReferenceOnlyMethod(CtTypeReference<?> owner,
                                                   String ownerName,
                                                   String relationship,
                                                   int distance,
                                                   boolean objectPhase) {
            boolean matched = false;
            try {
                for (CtExecutableReference<?> candidate : owner.getDeclaredExecutables()) {
                    if (!focal.getSimpleName().equals(candidate.getSimpleName())
                            || !overrides(candidate, objectPhase)) {
                        continue;
                    }
                    String methodKey = ownerName + "#" + candidate.getSignature();
                    if (!visitedMethods.add(methodKey)) {
                        continue;
                    }
                    matched = true;
                    unresolved.add(new UnresolvedAncestor(ownerName, relationship,
                            candidate.getSignature(), distance, ++searchOrder,
                            Availability.SOURCE_UNAVAILABLE, "method_source_unavailable"));
                }
            } catch (RuntimeException | StackOverflowError ignored) {
                // The caller records partial type resolution when no method
                // declaration or executable reference can be inspected.
            }
            return matched;
        }

        private boolean overrides(CtExecutableReference<?> candidate, boolean objectPhase) {
            try {
                if (focal.getReference().isOverriding(candidate)) {
                    return true;
                }
                return objectPhase && focal.getDeclaringType() != null && focal.getDeclaringType().isInterface()
                        && focal.getReference().getSignature().equals(candidate.getSignature());
            } catch (RuntimeException | StackOverflowError ignored) {
                return false;
            }
        }

        List<AncestorMethod> methods() {
            return List.copyOf(methods);
        }

        List<UnresolvedAncestor> unresolved() {
            return List.copyOf(unresolved);
        }

        boolean hasInheritanceEvidence() {
            return !methods.isEmpty() || !unresolved.isEmpty();
        }

        boolean truncated() {
            return truncated;
        }

        private static CtType<?> typeDeclaration(CtTypeReference<?> reference) {
            try {
                return reference.getTypeDeclaration();
            } catch (RuntimeException | StackOverflowError ignored) {
                return null;
            }
        }

        private static String relationship(CtTypeReference<?> reference) {
            try {
                return reference.isInterface() ? "superinterface" : "superclass";
            } catch (RuntimeException | StackOverflowError ignored) {
                return "unknown";
            }
        }

        private static String safeQualifiedName(CtTypeReference<?> reference) {
            try {
                String name = reference.getQualifiedName();
                return name == null || name.isBlank() ? reference.toString() : name;
            } catch (RuntimeException | StackOverflowError ignored) {
                return reference.toString();
            }
        }
    }
}
