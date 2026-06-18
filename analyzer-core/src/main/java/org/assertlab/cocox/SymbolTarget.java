package org.assertlab.cocox;

import java.util.Locale;
import java.util.Objects;

/**
 * First-class extraction target.
 *
 * <p>CoCoX uses the same URI grammar for methods, types, and packages:
 * {@code relative/source/path#qualified.symbol}. Methods append erased
 * parameter and return identity after the symbol name. The explicit kind keeps
 * package/type/method targets unambiguous even though they share the same
 * {@code path#symbol} shape.
 */
public record SymbolTarget(Kind kind, String uri) {
    public enum Kind {
        PROJECT,
        METHOD,
        TYPE,
        PACKAGE
    }

    public SymbolTarget {
        kind = Objects.requireNonNull(kind, "kind cannot be null");
        uri = uri == null ? "" : uri.trim();
        if (kind != Kind.PROJECT && uri.isBlank()) {
            throw new IllegalArgumentException("Target URI cannot be blank for " + kind);
        }
    }

    public static SymbolTarget project(String uri) {
        return new SymbolTarget(Kind.PROJECT, uri);
    }

    public static SymbolTarget method(String uri) {
        return new SymbolTarget(Kind.METHOD, uri);
    }

    public static SymbolTarget type(String uri) {
        return new SymbolTarget(Kind.TYPE, uri);
    }

    public static SymbolTarget packageTarget(String uri) {
        return new SymbolTarget(Kind.PACKAGE, uri);
    }

    public static SymbolTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Target URI cannot be blank");
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Target URI must be prefixed with method:, type:, package:, or project:");
        }
        String prefix = raw.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String uri = raw.substring(colon + 1).trim();
        return switch (prefix) {
            case "project" -> project(uri);
            case "method", "method_uri" -> method(uri);
            case "type", "class", "type_uri", "class_uri" -> type(uri);
            case "package", "package_uri" -> packageTarget(uri);
            default -> throw new IllegalArgumentException("Unsupported target kind: " + prefix);
        };
    }

    public String prefixedUri() {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + uri;
    }
}
