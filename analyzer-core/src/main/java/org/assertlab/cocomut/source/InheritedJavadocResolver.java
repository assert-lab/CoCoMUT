package org.assertlab.cocomut.source;

import org.assertlab.cocomut.ContextRequest;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtFormalTypeDeclarer;

import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.adaption.TypeAdaptor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
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
 * method definitions with the JDK 25 standard doclet's nearest-supertype search.
 *
 * <p>The resolver deliberately keeps declared and effective documentation
 * separate. It uses Spoon only to decide Java override relationships and
 * traverses the hierarchy in JDK 25 order: superclass ancestry first,
 * interfaces in declaration order second, and {@code java.lang.Object} last.
 * Missing documentation items are resolved independently because Javadoc can
 * inherit a parameter or return contract even when the overriding method does
 * not contain an explicit {@code inheritDoc} tag.
 */
final class InheritedJavadocResolver {
    private static final int MAX_VISITED_TYPES = 256;
    static final char PROTECTED_AT_SIGN = '\uE000';
    static final char INLINE_RETURN_START = '\uE001';
    static final char INLINE_RETURN_END = '\uE002';
    private static final Pattern INHERIT_DOC = Pattern.compile(
            "\\{@inheritDoc(?:\\s+([^\\s}]+))?\\s*}");

    private InheritedJavadocResolver() {
    }

    static Resolution resolve(CtMethod<?> focal,
                              boolean usesInheritDoc,
                              Documentation declaredDocumentation,
                              Function<CtMethod<?>, Documentation> documentationExtractor,
                              ContextRequest.JavadocInheritancePolicy policy) {
        if (focal == null) {
            return Resolution.notApplicable(null, declaredDocumentation);
        }
        if (policy != ContextRequest.JavadocInheritancePolicy.JDK25_STANDARD_DOCLET) {
            throw new IllegalArgumentException("Unsupported Javadoc inheritance policy: " + policy);
        }

        ResolutionContext context = new ResolutionContext(documentationExtractor);
        Evidence focalEvidence = context.evidence(focal);
        List<Candidate> candidates = focalEvidence.candidates();

        String resolution = inheritanceResolution(usesInheritDoc, candidates, focalEvidence.unresolved());
        Map<String, Object> effective = effectiveDocumentation(
                focal, declaredDocumentation, context);
        List<Map<String, Object>> evidence = new ArrayList<>();
        candidates.forEach(candidate -> evidence.add(candidate.asMap()));
        focalEvidence.unresolved().forEach(unresolved -> evidence.add(unresolved.asMap()));
        evidence.sort((left, right) -> Integer.compare(
                intValue(left.get("search_order")), intValue(right.get("search_order"))));

        long documented = candidates.stream()
                .filter(candidate -> candidate.documentation().availability() == Availability.PRESENT)
                .count();
        return new Resolution(
                resolution,
                usesInheritDoc || focalEvidence.hasInheritanceEvidence(),
                List.copyOf(evidence),
                effective,
                evidence.size(),
                (int) documented,
                focalEvidence.truncated());
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
            ResolutionContext context) {
        Map<String, Object> effective = new LinkedHashMap<>();
        List<Map<String, Object>> params = new ArrayList<>();
        List<Map<String, Object>> typeParams = new ArrayList<>();
        List<Map<String, Object>> returns = new ArrayList<>();
        List<Map<String, Object>> throwsTags = new ArrayList<>();
        boolean[] incomplete = {false};

        effective.put("description", effectiveItems(null, null, declared.resolutionDescription(),
                "description", 0, focal, declared, context, incomplete, false).get(0));

        Map<String, String> declaredParams = namedTags(declared.resolutionStructuredTags(), "params", "name");
        for (int index = 0; index < focal.getParameters().size(); index++) {
            String name = focal.getParameters().get(index).getSimpleName();
            params.add(effectiveItems("name", name, declaredParams.get(name), "param", index,
                    focal, declared, context, incomplete, false).get(0));
        }

        Map<String, String> declaredTypeParams = namedTags(
                declared.resolutionStructuredTags(), "params", "name");
        List<CtTypeParameter> focalTypeParams = focal.getFormalCtTypeParameters();
        for (int index = 0; index < focalTypeParams.size(); index++) {
            String name = focalTypeParams.get(index).getSimpleName();
            String local = firstNonNull(declaredTypeParams.get("<" + name + ">"), declaredTypeParams.get(name));
            typeParams.add(effectiveItems("name", name, local, "type_param", index,
                    focal, declared, context, incomplete, false).get(0));
        }

        if (!"void".equals(normalizedTypeName(focal.getType()))) {
            returns.add(effectiveItems(null, null, firstText(declared.resolutionStructuredTags(), "return"),
                    "return", 0, focal, declared, context, incomplete, false).get(0));
        }

        Set<String> representedThrows = new LinkedHashSet<>();
        for (Map<String, String> tag : tagMaps(declared.resolutionStructuredTags(), "throws")) {
            String rawType = tag.getOrDefault("type", "");
            String type = canonicalThrownType(focal, rawType);
            representedThrows.add(type);
            throwsTags.addAll(effectiveItems("type", type, tag.get("text"), "throws",
                    methodTypeParameterIndex(focal, type),
                    focal, declared, context, incomplete, true));
        }
        for (CtTypeReference<?> thrown : focal.getThrownTypes()) {
            String type = normalizedTypeName(thrown);
            if (representedThrows.add(type)) {
                throwsTags.addAll(effectiveItems("type", type, null, "throws",
                        methodTypeParameterIndex(focal, type),
                        focal, declared, context, incomplete, true));
            }
        }

        effective.put("type_params", typeParams);
        effective.put("params", params);
        effective.put("return", returns);
        effective.put("throws", throwsTags);
        effective.put("resolution", incomplete[0] ? "partial" : "complete");
        return effective;
    }

    private static List<Map<String, Object>> effectiveItems(
            String nameKey,
            String name,
            String local,
            String kind,
            int position,
            CtMethod<?> focal,
            Documentation declared,
            ResolutionContext context,
            boolean[] incomplete,
            boolean preserveMultiple) {
        String localText = local == null ? "" : local.trim();
        List<ResolvedText> resolved;
        String inheritanceMode;
        if ("throws".equals(kind) && inheritDocCount(localText) > 1) {
            resolved = List.of(ResolvedText.invalid(
                    JavadocResolutionDiagnostic.THROWS_MULTIPLE_INHERITDOC.id()));
            inheritanceMode = "explicit_inheritdoc";
        } else if (!localText.isBlank() && !containsResolutionSyntax(localText)) {
            resolved = List.of(ResolvedText.declared(localText,
                    Segment.declared(localText, declared.methodUri(), declaringType(focal))));
            inheritanceMode = "declared";
        } else if (containsResolutionSyntax(localText)) {
            resolved = resolveInlineText(kind, position, name == null ? "" : name,
                    focal, localText, declared.methodUri(), declaringType(focal), 0,
                    context, 0, true);
            inheritanceMode = "explicit_inheritdoc";
        } else {
            resolved = resolveInherited(kind, position, name == null ? "" : name,
                    focal, "", context, 0, 0, false);
            inheritanceMode = "implicit_missing_item";
        }
        if (resolved.isEmpty()) {
            resolved = List.of(ResolvedText.missing());
        }
        if (!preserveMultiple && resolved.size() > 1) {
            resolved = List.of(resolved.get(0));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ResolvedText value : resolved) {
            items.add(effectiveItem(nameKey, name, value, inheritanceMode, incomplete));
        }
        return items;
    }

    private static Map<String, Object> effectiveItem(String nameKey,
                                                      String name,
                                                      ResolvedText value,
                                                      String inheritanceMode,
                                                      boolean[] incomplete) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (nameKey != null) {
            item.put(nameKey, name == null ? "" : name);
        }
        item.put("text", value.text());
        boolean declared = value.segments().stream().anyMatch(segment -> segment.hierarchyDistance() == 0);
        boolean inherited = value.segments().stream().anyMatch(segment -> segment.hierarchyDistance() > 0);
        String source = switch (value.resolution()) {
            case INVALID -> "invalid";
            case INDETERMINATE -> "indeterminate";
            case MISSING -> "missing";
            case RESOLVED -> declared && inherited ? "composed" : (inherited ? "inherited" : "declared");
        };
        item.put("source", source);
        item.put("inheritance_mode", inheritanceMode);
        item.put("resolution", value.resolution().jsonValue());
        if (!value.segments().isEmpty()) {
            item.put("segments", value.segments().stream().map(Segment::asMap).toList());
            item.put("source_chain", value.segments().stream()
                    .map(Segment::sourceMethodUri)
                    .filter(uri -> !uri.isBlank())
                    .distinct()
                    .toList());
            value.segments().stream()
                    .filter(segment -> segment.hierarchyDistance() > 0)
                    .findFirst()
                    .ifPresent(segment -> {
                        item.put("inherited_from", segment.declaringType());
                        item.put("inherited_method_uri", segment.sourceMethodUri());
                        item.put("hierarchy_distance", segment.hierarchyDistance());
                    });
        }
        if (!value.diagnostic().isBlank()) {
            item.put("diagnostic_code", value.diagnostic());
        }
        incomplete[0] |= value.resolution() == ItemResolution.INVALID
                || value.resolution() == ItemResolution.INDETERMINATE;
        return item;
    }

    /**
     * Resolves one documentation item by repeatedly applying the JDK 25
     * standard doclet's supertype search. The search chooses an overriding declaration
     * before inspecting whether that declaration contains the requested item;
     * if the item is omitted, resolution restarts from that declaration. This
     * prevents an empty item on one interface branch from incorrectly falling
     * through to a sibling interface.
     */
    private static List<ResolvedText> resolveInherited(
            String kind,
            int position,
            String name,
            CtMethod<?> method,
            String requiredSupertype,
            ResolutionContext context,
            int depth,
            int baseDistance,
            boolean strict) {
        if (method == null || depth >= MAX_VISITED_TYPES) {
            return List.of(ResolvedText.indeterminate(
                    JavadocResolutionDiagnostic.HIERARCHY_RESOLUTION_LIMIT.id()));
        }

        Evidence evidence = context.evidence(method);
        SelectedEvidence selected = selectEvidence(evidence, method, requiredSupertype, context);
        if (selected.resolution() == ItemResolution.INVALID) {
            return List.of(ResolvedText.invalid(selected.diagnostic()));
        }
        if (selected.unresolved() != null || selected.resolution() == ItemResolution.INDETERMINATE) {
            return List.of(ResolvedText.indeterminate(selected.diagnostic().isBlank()
                    ? JavadocResolutionDiagnostic.SOURCE_EVIDENCE_UNAVAILABLE.id()
                    : selected.diagnostic()));
        }
        Candidate candidate = selected.candidate();
        if (candidate == null) {
            if (evidence.truncated()) {
                return List.of(ResolvedText.indeterminate(
                        JavadocResolutionDiagnostic.HIERARCHY_TRUNCATED.id()));
            }
            return List.of(strict
                    ? ResolvedText.invalid(
                    JavadocResolutionDiagnostic.INHERITDOC_CORRESPONDING_ITEM_MISSING.id())
                    : ResolvedText.missing());
        }

        Documentation documentation = candidate.documentation();
        if (documentation.availability().isIndeterminate()) {
            return List.of(ResolvedText.indeterminate(
                    JavadocResolutionDiagnostic.SOURCE_DOCUMENTATION_UNAVAILABLE.id()));
        }
        List<String> texts = documentation.availability() == Availability.PRESENT
                ? candidateTexts(candidate, kind, position, name)
                : List.of();
        if (texts.isEmpty() || texts.stream().allMatch(String::isBlank)) {
            return resolveInherited(kind, position, name, candidate.method(), "", context,
                    depth + 1, baseDistance + candidate.distance(), strict);
        }

        List<ResolvedText> values = new ArrayList<>();
        int sourceDistance = baseDistance + candidate.distance();
        for (String text : texts) {
            if ("throws".equals(kind) && inheritDocCount(text) > 1) {
                values.add(ResolvedText.invalid(
                        JavadocResolutionDiagnostic.THROWS_MULTIPLE_INHERITDOC.id()));
            } else if (containsResolutionSyntax(text)) {
                values.addAll(resolveInlineText(kind, position, name, candidate.method(), text,
                        documentation.methodUri(), candidate.declaringType(), sourceDistance,
                        context, depth + 1, true));
            } else {
                values.add(ResolvedText.inherited(text,
                        Segment.inherited(text, documentation.methodUri(),
                                candidate.declaringType(), sourceDistance)));
            }
        }
        return values;
    }

    private static List<ResolvedText> resolveInlineText(
            String kind,
            int position,
            String name,
            CtMethod<?> method,
            String text,
            String sourceMethodUri,
            String sourceType,
            int sourceDistance,
            ResolutionContext context,
            int depth,
            boolean strict) {
        if ("description".equals(kind) && containsInlineReturn(text)) {
            return resolveDescriptionText(method, text, sourceMethodUri, sourceType,
                    sourceDistance, context, depth, strict);
        }
        List<Composition> compositions = new ArrayList<>();
        compositions.add(new Composition(new StringBuilder(), new ArrayList<>(),
                ItemResolution.RESOLVED, ""));
        Matcher matcher = INHERIT_DOC.matcher(text == null ? "" : text);
        int cursor = 0;
        while (matcher.find()) {
            appendLocalText(compositions, text.substring(cursor, matcher.start()),
                    sourceMethodUri, sourceType, sourceDistance);
            String requested = matcher.group(1) == null ? "" : matcher.group(1).trim();
            List<ResolvedText> inherited = resolveInherited(kind, position, name, method,
                    requested, context, depth + 1, sourceDistance, strict);
            List<Composition> expanded = new ArrayList<>();
            for (Composition composition : compositions) {
                for (ResolvedText inheritedText : inherited) {
                    Composition copy = composition.copy();
                    if (inheritedText.resolution() == ItemResolution.RESOLVED) {
                        copy.text().append(inheritedText.text());
                        copy.segments().addAll(inheritedText.segments());
                    } else {
                        copy.text().append(matcher.group());
                        copy.resolution(inheritedText.resolution());
                        copy.diagnostic(inheritedText.diagnostic());
                    }
                    expanded.add(copy);
                }
            }
            compositions = expanded;
            cursor = matcher.end();
        }
        appendLocalText(compositions, text.substring(cursor), sourceMethodUri, sourceType, sourceDistance);
        return compositions.stream().map(Composition::resolvedText).toList();
    }

    private static List<ResolvedText> resolveDescriptionText(
            CtMethod<?> method,
            String text,
            String sourceMethodUri,
            String sourceType,
            int sourceDistance,
            ResolutionContext context,
            int depth,
            boolean strict) {
        int start = text.indexOf(INLINE_RETURN_START);
        if (start < 0) {
            return resolveInlineText("description", 0, "", method, text,
                    sourceMethodUri, sourceType, sourceDistance, context, depth, strict);
        }
        int end = text.indexOf(INLINE_RETURN_END, start + 1);
        if (end < 0) {
            return List.of(ResolvedText.indeterminate(
                    JavadocResolutionDiagnostic.SOURCE_DOCUMENTATION_UNAVAILABLE.id()));
        }

        List<ResolvedText> prefix = resolveInlineText("description", 0, "", method,
                text.substring(0, start), sourceMethodUri, sourceType, sourceDistance,
                context, depth + 1, strict);
        String returnText = text.substring(start + 1, end);
        List<ResolvedText> resolvedReturn = containsResolutionSyntax(returnText)
                ? resolveInlineText("return", 0, "", method, returnText,
                        sourceMethodUri, sourceType, sourceDistance, context, depth + 1, strict)
                : List.of(localResolvedText(returnText, sourceMethodUri, sourceType, sourceDistance));
        List<ResolvedText> suffix = resolveDescriptionText(method, text.substring(end + 1),
                sourceMethodUri, sourceType, sourceDistance, context, depth + 1, strict);

        List<ResolvedText> result = new ArrayList<>();
        for (ResolvedText before : prefix) {
            for (ResolvedText item : resolvedReturn) {
                for (ResolvedText after : suffix) {
                    result.add(composeDescription(before,
                            renderInlineReturn(item, sourceMethodUri, sourceType, sourceDistance), after));
                }
            }
        }
        return result;
    }

    private static ResolvedText localResolvedText(String text,
                                                  String sourceMethodUri,
                                                  String sourceType,
                                                  int sourceDistance) {
        String normalized = normalizeRenderedText(text);
        if (normalized.isBlank()) {
            return ResolvedText.missing();
        }
        Segment segment = sourceDistance == 0
                ? Segment.declared(normalized, sourceMethodUri, sourceType)
                : Segment.inherited(normalized, sourceMethodUri, sourceType, sourceDistance);
        return new ResolvedText(normalized, List.of(segment), ItemResolution.RESOLVED, "");
    }

    private static ResolvedText renderInlineReturn(ResolvedText item,
                                                   String sourceMethodUri,
                                                   String sourceType,
                                                   int sourceDistance) {
        String itemText = item.text();
        String suffix = itemText.matches(".*[.!?]$") ? "" : ".";
        List<Segment> segments = new ArrayList<>();
        segments.add(sourceDistance == 0
                ? Segment.declared("Returns", sourceMethodUri, sourceType)
                : Segment.inherited("Returns", sourceMethodUri, sourceType, sourceDistance));
        segments.addAll(item.segments());
        if (!suffix.isEmpty()) {
            segments.add(sourceDistance == 0
                    ? Segment.declared(suffix, sourceMethodUri, sourceType)
                    : Segment.inherited(suffix, sourceMethodUri, sourceType, sourceDistance));
        }
        return new ResolvedText(itemText.isBlank() ? "Returns." : "Returns " + itemText + suffix,
                segments, item.resolution(), item.diagnostic());
    }

    private static ResolvedText composeDescription(ResolvedText... values) {
        StringBuilder text = new StringBuilder();
        List<Segment> segments = new ArrayList<>();
        ItemResolution resolution = ItemResolution.RESOLVED;
        String diagnostic = "";
        for (ResolvedText value : values) {
            if (!value.text().isBlank()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(value.text());
            }
            segments.addAll(value.segments());
            if (value.resolution() == ItemResolution.INVALID
                    || resolution != ItemResolution.INVALID
                    && value.resolution() == ItemResolution.INDETERMINATE) {
                resolution = value.resolution();
            }
            if (diagnostic.isBlank() && !value.diagnostic().isBlank()) {
                diagnostic = value.diagnostic();
            }
        }
        return new ResolvedText(text.toString(), segments, resolution, diagnostic);
    }

    private static void appendLocalText(List<Composition> compositions,
                                        String text,
                                        String sourceMethodUri,
                                        String sourceType,
                                        int sourceDistance) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String normalized = normalizeRenderedText(text);
        for (Composition composition : compositions) {
            composition.text().append(text);
            if (!normalized.isBlank()) {
                composition.segments().add(sourceDistance == 0
                        ? Segment.declared(normalized, sourceMethodUri, sourceType)
                        : Segment.inherited(normalized, sourceMethodUri, sourceType, sourceDistance));
            }
        }
    }

    private static SelectedEvidence selectEvidence(Evidence evidence,
                                                    CtMethod<?> method,
                                                    String requiredSupertype,
                                                    ResolutionContext context) {
        String canonicalTarget = "";
        if (!requiredSupertype.isBlank()) {
            ExplicitTarget target = resolveExplicitTarget(method, requiredSupertype, evidence);
            if (target.resolution() != ItemResolution.RESOLVED) {
                return new SelectedEvidence(null, null, target.resolution(), target.diagnostic());
            }
            canonicalTarget = target.canonicalName();
            final String explicitTargetName = canonicalTarget;
            SupertypeEvidence supertype = evidence.supertypes().stream()
                    .filter(value -> normalizeTagType(value.canonicalName()).equals(explicitTargetName))
                    .findFirst().orElse(null);
            if (supertype == null) {
                return new SelectedEvidence(null, null, ItemResolution.INVALID,
                        JavadocResolutionDiagnostic.INHERITDOC_TARGET_NOT_OVERRIDDEN.id());
            }
            Evidence explicit = context.explicitEvidence(method, supertype);
            Candidate explicitCandidate = explicit.candidates().stream().findFirst().orElse(null);
            UnresolvedAncestor explicitUnresolved = explicit.unresolved().stream().findFirst().orElse(null);
            if (explicitCandidate != null
                    && (explicitUnresolved == null
                    || explicitCandidate.searchOrder() < explicitUnresolved.searchOrder())) {
                return new SelectedEvidence(explicitCandidate, null, ItemResolution.RESOLVED, "");
            }
            if (explicitUnresolved != null) {
                return new SelectedEvidence(null, explicitUnresolved, ItemResolution.INDETERMINATE,
                        explicitUnresolved.diagnostic());
            }
            return new SelectedEvidence(null, null, ItemResolution.INVALID,
                    JavadocResolutionDiagnostic.INHERITDOC_TARGET_NOT_OVERRIDDEN.id());
        }
        final String selectedTarget = canonicalTarget;
        Candidate selectedCandidate = evidence.candidates().stream()
                .filter(candidate -> selectedTarget.isBlank()
                        || normalizeTagType(candidate.declaringType()).equals(selectedTarget))
                .min((left, right) -> Integer.compare(left.searchOrder(), right.searchOrder()))
                .orElse(null);
        UnresolvedAncestor selectedUnresolved = evidence.unresolved().stream()
                .filter(candidate -> selectedTarget.isBlank()
                        || normalizeTagType(candidate.declaringType()).equals(selectedTarget))
                .min((left, right) -> Integer.compare(left.searchOrder(), right.searchOrder()))
                .orElse(null);
        if (selectedCandidate == null) {
            return new SelectedEvidence(null, selectedUnresolved,
                    selectedUnresolved == null ? ItemResolution.MISSING : ItemResolution.INDETERMINATE,
                    selectedUnresolved == null ? "" : selectedUnresolved.diagnostic());
        }
        if (selectedUnresolved == null || selectedCandidate.searchOrder() < selectedUnresolved.searchOrder()) {
            return new SelectedEvidence(selectedCandidate, null, ItemResolution.RESOLVED, "");
        }
        return new SelectedEvidence(null, selectedUnresolved, ItemResolution.INDETERMINATE,
                selectedUnresolved.diagnostic());
    }

    private static ExplicitTarget resolveExplicitTarget(CtMethod<?> method,
                                                        String rawTarget,
                                                        Evidence evidence) {
        String target = normalizeTagType(stripModulePrefix(rawTarget));
        Set<String> available = new LinkedHashSet<>();
        evidence.supertypes().stream().map(SupertypeEvidence::canonicalName)
                .map(InheritedJavadocResolver::normalizeTagType).forEach(available::add);
        if (available.contains(target)) {
            return ExplicitTarget.resolved(target);
        }

        String simple = simpleTypeName(target);
        if (target.contains(".")) {
            Set<String> scopedQualified = scopedQualifiedTypeMatches(method, target, available);
            if (scopedQualified.size() == 1) {
                return ExplicitTarget.resolved(scopedQualified.iterator().next());
            }
            return ExplicitTarget.invalid(scopedQualified.size() > 1
                    ? JavadocResolutionDiagnostic.INHERITDOC_TARGET_AMBIGUOUS.id()
                    : JavadocResolutionDiagnostic.INHERITDOC_TARGET_NOT_OVERRIDDEN.id());
        }
        Set<String> simpleMatches = available.stream()
                .filter(candidate -> simpleTypeName(candidate).equals(simple))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (simpleMatches.isEmpty()) {
            return ExplicitTarget.invalid(
                    JavadocResolutionDiagnostic.INHERITDOC_TARGET_NOT_OVERRIDDEN.id());
        }
        Set<String> scoped = scopedTypeMatches(method, simple, simpleMatches);
        if (scoped.size() == 1) {
            return ExplicitTarget.resolved(scoped.iterator().next());
        }
        return ExplicitTarget.invalid(scoped.isEmpty() && simpleMatches.size() == 1
                ? JavadocResolutionDiagnostic.INHERITDOC_TARGET_NOT_OVERRIDDEN.id()
                : JavadocResolutionDiagnostic.INHERITDOC_TARGET_AMBIGUOUS.id());
    }

    private static Set<String> scopedQualifiedTypeMatches(CtMethod<?> method,
                                                          String target,
                                                          Set<String> candidates) {
        CtType<?> owner = method == null ? null : method.getDeclaringType();
        Set<String> lexicalMatches = new LinkedHashSet<>();
        addEnclosingTypeMatches(owner, target, candidates, lexicalMatches);
        if (!lexicalMatches.isEmpty()) {
            return lexicalMatches;
        }

        Set<String> explicitImportMatches = new LinkedHashSet<>();
        Set<String> onDemandMatches = new LinkedHashSet<>();
        addQualifiedImportMatches(method, target, candidates,
                explicitImportMatches, onDemandMatches);
        if (!explicitImportMatches.isEmpty()) {
            return explicitImportMatches;
        }

        String ownerPackage = owner == null || owner.getPackage() == null
                ? "" : owner.getPackage().getQualifiedName();
        String samePackage = ownerPackage.isBlank() ? target : ownerPackage + "." + target;
        if (candidates.contains(samePackage)) {
            return Set.of(samePackage);
        }

        String javaLang = "java.lang." + target;
        if (candidates.contains(javaLang)) {
            onDemandMatches.add(javaLang);
        }
        return onDemandMatches;
    }

    private static void addQualifiedImportMatches(CtMethod<?> method,
                                                  String target,
                                                  Set<String> candidates,
                                                  Set<String> explicitMatches,
                                                  Set<String> onDemandMatches) {
        int separator = target.indexOf('.');
        String leadingType = separator < 0 ? target : target.substring(0, separator);
        String suffix = separator < 0 ? "" : target.substring(separator);
        try {
            if (method != null && method.getPosition().isValidPosition()) {
                method.getPosition().getCompilationUnit().getImports().forEach(importValue -> {
                    String imported = importValue.toString()
                            .replaceFirst("^import\\s+(?:static\\s+)?", "")
                            .replace(";", "").trim();
                    if (imported.endsWith(".*")) {
                        String candidate = normalizeTagType(
                                imported.substring(0, imported.length() - 1) + target);
                        if (candidates.contains(candidate)) {
                            onDemandMatches.add(candidate);
                        }
                    } else if (simpleTypeName(imported).equals(leadingType)) {
                        String candidate = normalizeTagType(imported + suffix);
                        if (candidates.contains(candidate)) {
                            explicitMatches.add(candidate);
                        }
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // Incomplete import metadata cannot justify repairing a qualified spelling.
        }
    }

    private static Set<String> scopedTypeMatches(CtMethod<?> method,
                                                 String simple,
                                                 Set<String> candidates) {
        CtType<?> owner = method == null ? null : method.getDeclaringType();

        Set<String> lexicalMatches = new LinkedHashSet<>();
        addEnclosingTypeMatches(owner, simple, candidates, lexicalMatches);
        if (!lexicalMatches.isEmpty()) {
            return lexicalMatches;
        }

        Set<String> explicitImportMatches = new LinkedHashSet<>();
        Set<String> onDemandMatches = new LinkedHashSet<>();
        addImportMatches(method, simple, candidates, explicitImportMatches, onDemandMatches);
        if (!explicitImportMatches.isEmpty()) {
            return explicitImportMatches;
        }

        String ownerPackage = owner == null || owner.getPackage() == null
                ? "" : owner.getPackage().getQualifiedName();
        String samePackage = ownerPackage.isBlank() ? simple : ownerPackage + "." + simple;
        if (candidates.contains(samePackage)) {
            return Set.of(samePackage);
        }

        String javaLang = "java.lang." + simple;
        if (candidates.contains(javaLang)) {
            onDemandMatches.add(javaLang);
        }
        return onDemandMatches;
    }

    private static void addImportMatches(CtMethod<?> method,
                                         String simple,
                                         Set<String> candidates,
                                         Set<String> explicitMatches,
                                         Set<String> onDemandMatches) {
        try {
            if (method != null && method.getPosition().isValidPosition()) {
                method.getPosition().getCompilationUnit().getImports().forEach(importValue -> {
                    String imported = importValue.toString()
                            .replaceFirst("^import\\s+(?:static\\s+)?", "")
                            .replace(";", "").trim();
                    if (imported.endsWith(".*")) {
                        String candidate = imported.substring(0, imported.length() - 1) + simple;
                        if (candidates.contains(candidate)) {
                            onDemandMatches.add(candidate);
                        }
                    } else if (simpleTypeName(imported).equals(simple) && candidates.contains(imported)) {
                        explicitMatches.add(imported);
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // Incomplete import metadata cannot justify resolving a source spelling.
        }
    }

    private static void addEnclosingTypeMatches(CtType<?> owner,
                                                String target,
                                                Set<String> candidates,
                                                Set<String> matches) {
        CtType<?> enclosing = owner;
        while (enclosing != null) {
            String candidate = normalizeTagType(enclosing.getQualifiedName() + "." + target);
            if (candidates.contains(candidate)) {
                matches.add(candidate);
            }
            enclosing = enclosing.getDeclaringType();
        }
    }

    private static String stripModulePrefix(String type) {
        int separator = type == null ? -1 : type.indexOf('/');
        return separator >= 0 ? type.substring(separator + 1) : type;
    }

    private static List<String> candidateTexts(Candidate candidate,
                                               String kind,
                                               int position,
                                               String requestedName) {
        Documentation documentation = candidate.documentation();
        return switch (kind) {
            case "description" -> documentation.resolutionDescription().isBlank()
                    ? List.of() : List.of(documentation.resolutionDescription());
            case "param" -> singleText(parameterText(
                    candidate.method(), documentation.resolutionStructuredTags(), position, false));
            case "type_param" -> singleText(parameterText(
                    candidate.method(), documentation.resolutionStructuredTags(), position, true));
            case "return" -> singleText(firstText(documentation.resolutionStructuredTags(), "return"));
            case "throws" -> matchingThrowsTexts(candidate.method(),
                    documentation.resolutionStructuredTags(), requestedName, position);
            default -> List.of();
        };
    }

    private static List<String> singleText(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text);
    }

    private static List<String> matchingThrowsTexts(CtMethod<?> method,
                                                    Map<String, Object> tags,
                                                    String requestedName,
                                                    int typeParameterPosition) {
        if (typeParameterPosition >= 0
                && typeParameterPosition < method.getFormalCtTypeParameters().size()) {
            String candidateName = method.getFormalCtTypeParameters()
                    .get(typeParameterPosition).getSimpleName();
            return tagMaps(tags, "throws").stream()
                    .filter(item -> simpleTypeName(item.getOrDefault("type", "")).equals(candidateName))
                    .map(item -> item.getOrDefault("text", ""))
                    .toList();
        }
        String requested = canonicalThrownType(method, requestedName);
        return tagMaps(tags, "throws").stream()
                .filter(item -> canonicalThrownType(method, item.getOrDefault("type", "")).equals(requested))
                .map(item -> item.getOrDefault("text", ""))
                .toList();
    }

    private static int methodTypeParameterIndex(CtMethod<?> method, String typeName) {
        String simple = simpleTypeName(typeName);
        List<CtTypeParameter> parameters = method.getFormalCtTypeParameters();
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).getSimpleName().equals(simple)) {
                return index;
            }
        }
        return -1;
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

    private static boolean containsInheritDoc(String text) {
        return text != null && INHERIT_DOC.matcher(text).find();
    }

    private static boolean containsInlineReturn(String text) {
        return text != null && text.indexOf(INLINE_RETURN_START) >= 0;
    }

    private static boolean containsResolutionSyntax(String text) {
        return containsInheritDoc(text) || containsInlineReturn(text);
    }

    private static int inheritDocCount(String text) {
        Matcher matcher = INHERIT_DOC.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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

    private static String canonicalThrownType(CtMethod<?> method, String rawType) {
        return canonicalThrownType((CtExecutable<?>) method, rawType);
    }

    private static String canonicalThrownType(CtExecutable<?> method, String rawType) {
        String normalized = normalizeTagType(rawType);
        if (method == null || normalized.isBlank()) {
            return normalized;
        }
        List<String> declared = method.getThrownTypes().stream()
                .map(InheritedJavadocResolver::normalizedTypeName)
                .toList();
        if (declared.contains(normalized)) {
            return normalized;
        }
        List<String> simpleMatches = declared.stream()
                .filter(type -> simpleTypeName(type).equals(simpleTypeName(normalized)))
                .distinct()
                .toList();
        return simpleMatches.size() == 1 ? simpleMatches.get(0) : normalized;
    }

    private static String declaringType(CtMethod<?> method) {
        return method == null || method.getDeclaringType() == null
                ? "" : method.getDeclaringType().getQualifiedName();
    }

    private static String normalizeRenderedText(String text) {
        return decodeProtectedText(text).replaceAll("\\s+", " ").trim();
    }

    static String decodeProtectedText(String text) {
        return (text == null ? "" : text)
                .replace(PROTECTED_AT_SIGN, '@');
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
        PARTIAL_RESOLUTION("partial_resolution");

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
                         String resolutionDescription,
                         Map<String, Object> resolutionStructuredTags,
                         String parser,
                         String parseConfidence,
                         String diagnostic) {
        Documentation {
            availability = availability == null ? Availability.PARTIAL_RESOLUTION : availability;
            methodUri = methodUri == null ? "" : methodUri;
            rawJavadoc = rawJavadoc == null ? "" : rawJavadoc;
            description = description == null ? "" : description;
            structuredTags = structuredTags == null ? Map.of() : Map.copyOf(structuredTags);
            resolutionDescription = resolutionDescription == null ? description : resolutionDescription;
            resolutionStructuredTags = resolutionStructuredTags == null
                    ? structuredTags : Map.copyOf(resolutionStructuredTags);
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
        static Resolution notApplicable(CtExecutable<?> executable, Documentation declared) {
            CtType<?> executableOwner = executable == null ? null : executable.getParent(CtType.class);
            String declaringType = executableOwner == null ? "" : executableOwner.getQualifiedName();
            Map<String, Object> description = declaredOnlyItem(null, null,
                    declared.description(), declared.methodUri(), declaringType);
            List<Map<String, Object>> params = new ArrayList<>();
            List<Map<String, Object>> typeParams = new ArrayList<>();
            List<Map<String, Object>> throwsTags = new ArrayList<>();
            if (executable != null) {
                Map<String, String> declaredParams = namedTags(declared.structuredTags(), "params", "name");
                executable.getParameters().forEach(parameter -> params.add(declaredOnlyItem(
                        "name", parameter.getSimpleName(), declaredParams.get(parameter.getSimpleName()),
                        declared.methodUri(), declaringType)));
                List<CtTypeParameter> executableTypeParameters = executable instanceof CtFormalTypeDeclarer declarer
                        ? declarer.getFormalCtTypeParameters() : List.of();
                executableTypeParameters.forEach(parameter -> {
                    String name = parameter.getSimpleName();
                    String text = firstNonNull(declaredParams.get("<" + name + ">"), declaredParams.get(name));
                    typeParams.add(declaredOnlyItem("name", name, text,
                            declared.methodUri(), declaringType));
                });
                Set<String> represented = new LinkedHashSet<>();
                for (Map<String, String> tag : tagMaps(declared.structuredTags(), "throws")) {
                    String type = canonicalThrownType(executable, tag.getOrDefault("type", ""));
                    represented.add(type);
                    throwsTags.add(declaredOnlyItem("type", type, tag.get("text"),
                            declared.methodUri(), declaringType));
                }
                for (CtTypeReference<?> thrown : executable.getThrownTypes()) {
                    String type = normalizedTypeName(thrown);
                    if (represented.add(type)) {
                        throwsTags.add(declaredOnlyItem("type", type, null,
                                declared.methodUri(), declaringType));
                    }
                }
            }
            return new Resolution("not_applicable", false, List.of(),
                    Map.of("description", description,
                            "type_params", typeParams, "params", params, "return", List.of(),
                            "throws", throwsTags, "resolution", "complete"),
                    0, 0, false);
        }

        private static Map<String, Object> declaredOnlyItem(String nameKey,
                                                            String name,
                                                            String text,
                                                            String methodUri,
                                                            String declaringType) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (nameKey != null) {
                item.put(nameKey, name == null ? "" : name);
            }
            String normalized = normalizeRenderedText(text);
            boolean present = !normalized.isBlank();
            item.put("text", normalized);
            item.put("source", present ? "declared" : "missing");
            item.put("inheritance_mode", "declared");
            item.put("resolution", present ? "resolved" : "missing");
            if (present) {
                Segment segment = Segment.declared(normalized, methodUri, declaringType);
                item.put("segments", List.of(segment.asMap()));
                item.put("source_chain", methodUri == null || methodUri.isBlank()
                        ? List.of() : List.of(methodUri));
            }
            return item;
        }
    }

    private record Candidate(CtMethod<?> method,
                             String relationship,
                             int distance,
                             int searchOrder,
                             Documentation documentation) {
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
            value.put("declaring_type_abstract", owner != null && owner.isAbstract());
            value.put("method_abstract", method.isAbstract());
            value.put("method_default", method.isDefaultMethod());
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

    private enum ItemResolution {
        RESOLVED("resolved"),
        MISSING("missing"),
        INDETERMINATE("indeterminate"),
        INVALID("invalid");

        private final String jsonValue;

        ItemResolution(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        String jsonValue() {
            return jsonValue;
        }
    }

    private record Segment(String kind,
                           String text,
                           String sourceMethodUri,
                           String declaringType,
                           int hierarchyDistance) {
        static Segment declared(String text, String methodUri, String declaringType) {
            return new Segment("declared", normalizeRenderedText(text),
                    methodUri == null ? "" : methodUri,
                    declaringType == null ? "" : declaringType, 0);
        }

        static Segment inherited(String text, String methodUri, String declaringType, int distance) {
            return new Segment("inherited", normalizeRenderedText(text),
                    methodUri == null ? "" : methodUri,
                    declaringType == null ? "" : declaringType, Math.max(1, distance));
        }

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("text", text);
            map.put("source_method_uri", sourceMethodUri);
            map.put("declaring_type", declaringType);
            map.put("hierarchy_distance", hierarchyDistance);
            return map;
        }
    }

    private record ResolvedText(String text,
                                List<Segment> segments,
                                ItemResolution resolution,
                                String diagnostic) {
        ResolvedText {
            text = normalizeRenderedText(text);
            segments = segments == null ? List.of() : List.copyOf(segments);
            resolution = resolution == null ? ItemResolution.INDETERMINATE : resolution;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static ResolvedText declared(String text, Segment segment) {
            return new ResolvedText(text, List.of(segment), ItemResolution.RESOLVED, "");
        }

        static ResolvedText inherited(String text, Segment segment) {
            return new ResolvedText(text, List.of(segment), ItemResolution.RESOLVED, "");
        }

        static ResolvedText missing() {
            return new ResolvedText("", List.of(), ItemResolution.MISSING, "");
        }

        static ResolvedText indeterminate(String diagnostic) {
            return new ResolvedText("", List.of(), ItemResolution.INDETERMINATE, diagnostic);
        }

        static ResolvedText invalid(String diagnostic) {
            return new ResolvedText("", List.of(), ItemResolution.INVALID, diagnostic);
        }
    }

    private static final class Composition {
        private final StringBuilder text;
        private final List<Segment> segments;
        private ItemResolution resolution;
        private String diagnostic;

        private Composition(StringBuilder text, List<Segment> segments,
                            ItemResolution resolution, String diagnostic) {
            this.text = text;
            this.segments = segments;
            this.resolution = resolution;
            this.diagnostic = diagnostic;
        }

        StringBuilder text() {
            return text;
        }

        List<Segment> segments() {
            return segments;
        }

        void resolution(ItemResolution resolution) {
            if (this.resolution != ItemResolution.INVALID) {
                this.resolution = resolution;
            }
        }

        void diagnostic(String diagnostic) {
            if (this.diagnostic == null || this.diagnostic.isBlank()) {
                this.diagnostic = diagnostic;
            }
        }

        Composition copy() {
            return new Composition(new StringBuilder(text), new ArrayList<>(segments), resolution, diagnostic);
        }

        ResolvedText resolvedText() {
            return new ResolvedText(text.toString(), segments, resolution, diagnostic);
        }
    }

    private record SelectedEvidence(Candidate candidate,
                                    UnresolvedAncestor unresolved,
                                    ItemResolution resolution,
                                    String diagnostic) {
    }

    private record ExplicitTarget(String canonicalName,
                                  ItemResolution resolution,
                                  String diagnostic) {
        static ExplicitTarget resolved(String canonicalName) {
            return new ExplicitTarget(canonicalName, ItemResolution.RESOLVED, "");
        }

        static ExplicitTarget invalid(String diagnostic) {
            return new ExplicitTarget("", ItemResolution.INVALID, diagnostic);
        }
    }

    private record Evidence(List<Candidate> candidates,
                            List<UnresolvedAncestor> unresolved,
                            List<SupertypeEvidence> supertypes,
                            boolean truncated) {
        boolean hasInheritanceEvidence() {
            return !candidates.isEmpty() || !unresolved.isEmpty();
        }
    }

    private static final class ResolutionContext {
        private final Function<CtMethod<?>, Documentation> documentationExtractor;
        private final Map<CtMethod<?>, Evidence> evidenceByMethod = new IdentityHashMap<>();

        private ResolutionContext(Function<CtMethod<?>, Documentation> documentationExtractor) {
            this.documentationExtractor = documentationExtractor;
        }

        Evidence evidence(CtMethod<?> method) {
            return evidenceByMethod.computeIfAbsent(method, this::discover);
        }

        private Evidence discover(CtMethod<?> method) {
            Traversal traversal = new Traversal(method);
            traversal.run();
            List<Candidate> candidates = traversal.methods().stream()
                    .map(ancestor -> new Candidate(ancestor.method(), ancestor.relationship(), ancestor.distance(),
                            ancestor.searchOrder(), documentationExtractor.apply(ancestor.method())))
                    .toList();
            return new Evidence(candidates, traversal.unresolved(), traversal.supertypes(), traversal.truncated());
        }

        private Evidence explicitEvidence(CtMethod<?> method, SupertypeEvidence supertype) {
            Traversal traversal = new Traversal(method);
            traversal.runFrom(supertype.reference(), supertype.distance());
            List<Candidate> candidates = traversal.methods().stream()
                    .map(ancestor -> new Candidate(ancestor.method(), ancestor.relationship(), ancestor.distance(),
                            ancestor.searchOrder(), documentationExtractor.apply(ancestor.method())))
                    .toList();
            return new Evidence(candidates, traversal.unresolved(), traversal.supertypes(), traversal.truncated());
        }
    }

    private record AncestorMethod(CtMethod<?> method, String relationship, int distance, int searchOrder) {
    }

    private record SupertypeEvidence(String canonicalName,
                                     CtTypeReference<?> reference,
                                     int distance) {
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

    /** Traverses supertypes in the JDK 25 automatic-search order. */
    private static final class Traversal {
        private final CtMethod<?> focal;
        private final List<AncestorMethod> methods = new ArrayList<>();
        private final List<UnresolvedAncestor> unresolved = new ArrayList<>();
        private final List<SupertypeEvidence> supertypes = new ArrayList<>();
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

        void runFrom(CtTypeReference<?> reference, int distance) {
            visit(reference, distance);
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
            supertypes.add(new SupertypeEvidence(name, reference, distance));
            if (visitedTypes.size() > MAX_VISITED_TYPES) {
                truncated = true;
                unresolved.add(new UnresolvedAncestor(name, relationship(reference), "", distance, ++searchOrder,
                        Availability.PARTIAL_RESOLUTION,
                        JavadocResolutionDiagnostic.HIERARCHY_TRAVERSAL_LIMIT.id()));
                return;
            }

            CtType<?> declaration = typeDeclaration(reference);
            if (declaration == null) {
                boolean matched = collectReferenceOnlyMethod(
                        reference, name, relationship(reference), distance, objectPhase);
                if (!matched) {
                    unresolved.add(new UnresolvedAncestor(name, relationship(reference), "", distance, ++searchOrder,
                            Availability.PARTIAL_RESOLUTION,
                            JavadocResolutionDiagnostic.SUPERTYPE_DECLARATION_UNAVAILABLE.id()));
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
            visit(declaration.getSuperclass(), distance + 1);
            for (CtTypeReference<?> superInterface : declaration.getSuperInterfaces()) {
                visit(superInterface, distance + 1);
            }
        }

        private boolean overrides(CtMethod<?> candidate, boolean objectPhase) {
            try {
                if (!isAccessibleOverrideCandidate(candidate)) {
                    return false;
                }
                if (objectPhase && focal.getDeclaringType() != null
                        && focal.getDeclaringType().isInterface()) {
                    return candidate.isPublic() && new TypeAdaptor(focal).isSameSignature(focal, candidate);
                }
                if (focal.isOverriding(candidate)) {
                    return true;
                }
                return false;
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
                            Availability.SOURCE_UNAVAILABLE,
                            JavadocResolutionDiagnostic.SOURCE_DOCUMENTATION_UNAVAILABLE.id()));
                }
            } catch (RuntimeException | StackOverflowError ignored) {
                // The caller records partial type resolution when no method
                // declaration or executable reference can be inspected.
            }
            return matched;
        }

        private boolean overrides(CtExecutableReference<?> candidate, boolean objectPhase) {
            try {
                CtExecutable<?> declaration = candidate.getExecutableDeclaration();
                if (declaration instanceof CtMethod<?> method && !isAccessibleOverrideCandidate(method)) {
                    return false;
                }
                if (objectPhase && focal.getDeclaringType() != null
                        && focal.getDeclaringType().isInterface()) {
                    return declaration instanceof CtMethod<?> method && method.isPublic()
                            && focal.getReference().getSignature().equals(candidate.getSignature());
                }
                if (focal.getReference().isOverriding(candidate)) {
                    return true;
                }
                return false;
            } catch (RuntimeException | StackOverflowError ignored) {
                return false;
            }
        }

        private boolean isAccessibleOverrideCandidate(CtMethod<?> candidate) {
            if (candidate.isPrivate()) {
                return false;
            }
            CtType<?> owner = candidate.getDeclaringType();
            if (owner == null || owner.isInterface() || candidate.isPublic() || candidate.isProtected()) {
                return true;
            }
            CtType<?> focalOwner = focal.getDeclaringType();
            return focalOwner != null
                    && packageName(owner).equals(packageName(focalOwner));
        }

        private static String packageName(CtType<?> type) {
            try {
                return type.getPackage() == null ? "" : type.getPackage().getQualifiedName();
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        List<AncestorMethod> methods() {
            return List.copyOf(methods);
        }

        List<UnresolvedAncestor> unresolved() {
            return List.copyOf(unresolved);
        }

        List<SupertypeEvidence> supertypes() {
            return List.copyOf(supertypes);
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
