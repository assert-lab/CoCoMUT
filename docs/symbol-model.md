# Symbol and Reference Model

This note documents two product concepts:

- how CoCoX names selected Java program elements;
- how CoCoX resolves Javadoc references such as `@see`, `{@link ...}`, and
  `{@linkplain ...}`.

The current implementation uses canonical method URIs, supports type/package
URI selection, and emits best-effort Javadoc reference metadata.

## Symbol URIs

CoCoX should use one URI grammar for every selectable Java symbol:

```text
relative/source/path#qualified.java.symbol
```

The part before `#` anchors the symbol to a source location. The part after `#`
identifies the Java symbol inside that source location.

## Method URIs

Methods need parameter and return-type identity because Java supports overloads.
CoCoX therefore uses erased parameter types and erased return type:

```text
src/main/java/org/example/Foo.java#org.example.Foo.parse(java.lang.String):int
```

Meaning:

```text
source path:      src/main/java/org/example/Foo.java
declaring type:   org.example.Foo
method:           parse
erased params:    java.lang.String
erased return:    int
```

The erased return type is included because generic overload-like declarations
can otherwise collapse to the same apparent parameter identity during mining.

## Type URIs

CoCoX uses "type" instead of "class" internally because Java has several type
kinds:

```java
class Foo {}
interface Bar {}
enum Color {}
record Point(int x, int y) {}
@interface Nullable {}
```

All of these should be selectable with the same `type_uri` concept:

```text
src/main/java/org/example/Foo.java#org.example.Foo
src/main/java/org/example/Bar.java#org.example.Bar
src/main/java/org/example/Color.java#org.example.Color
```

For user-facing CLI ergonomics, `--class-uri` can be an alias for `--type-uri`,
but the canonical model should stay `type_uri`.

## Package URIs

Packages are also useful selection targets. When a package has
`package-info.java`, use it as the source anchor:

```text
src/main/java/org/example/package-info.java#org.example
```

If a package has no `package-info.java`, use the package directory as the
anchor:

```text
src/main/java/org/example/#org.example
```

This makes package selection reproducible without relying only on a textual
prefix such as `--package org.example`.

## Selection Model

Current CLI selection supports both filters and first-class target URIs:

```text
--project      repository root
--package      package-name filter
--class        type/class-name filter
--method       method-name or method-URI substring filter
--target-uri   method:URI|type:URI|package:URI|project:URI
--method-uri   exact method URI
--type-uri     exact type URI
--class-uri    alias for --type-uri
--package-uri  exact package URI
```

The URI selector form is:

```bash
cocox --project /repo --target-uri method:src/main/java/org/example/Foo.java#org.example.Foo.parse(java.lang.String):int
cocox --project /repo --target-uri type:src/main/java/org/example/Foo.java#org.example.Foo
cocox --project /repo --target-uri package:src/main/java/org/example/package-info.java#org.example
```

Equivalent explicit flags are also supported:

```bash
cocox --project /repo --method-uri  ...
cocox --project /repo --type-uri    ...
cocox --project /repo --package-uri ...
```

Generated JSONL should record the selected target:

```json
{
  "selection": {
    "kind": "type",
    "uri": "src/main/java/org/example/Foo.java#org.example.Foo"
  }
}
```

For a repository-wide run, provenance should state:

```json
{
  "selection": {
    "kind": "project",
    "uri": "/absolute/or/repo-relative/project/root"
  }
}
```

## Javadoc References

CoCoX follows the Oracle/JDK standard doclet syntax for Javadoc references.
This is a Java/Javadoc convention, not an Apache Commons Lang-specific rule.
The important official forms are:

```java
@see "plain text with no generated link"
@see <a href="https://example.org/spec">external label</a>
@see module/package.Type#member optional label
{@link package.Type#member optional label}
{@linkplain package.Type#member optional label}
```

The program-element form can point to several symbol kinds:

```java
@see #parse(String)
@see StringUtils#isBlank(CharSequence)
@see JavaVersion
@see CharUtils#NUL
@see java.util.regex.Pattern#DOTALL
@see java.util.regex.Matcher#replaceAll(String)
```

CoCoX stores these under `javadoc_metadata.javadoc_references`.

Primary references:

- JDK documentation-comment specification for the standard doclet.
- Javadoc tool reference.

These documents are versioned with the JDK. CoCoX implements the stable
traditional Javadoc reference forms used by `@see`, `{@link ...}`, and
`{@linkplain ...}`; it does not currently implement newer Markdown
documentation-comment syntax as a separate parser mode.

Important fields:

```text
kind                 type_reference|member_reference|field_reference|external_url|text_reference
resolution           resolved_method|resolved_type|resolved_field|
                     resolved_inherited_method|resolved_inherited_field|
                     overload_ambiguous|external_symbol|external|text|unresolved
reference_target_kind method|field|type|url|text|method_or_field|unknown
reference_domain    project|external_jdk|external_library|external_web|text|unresolved
reference_scope     same_type|same_package|same_module|external|text|unknown
method_uri           present for resolved project methods
field_uri            present for resolved project fields
type_uri             present for resolved project types
referenced_method    compact source/Javadoc context for resolved project methods
field_javadoc        full field Javadoc for resolved project fields
class_javadoc        full class/type Javadoc for resolved project types
external_class       present for external symbols
external_member      present for external member symbols
candidate_method_uris present when overload resolution is ambiguous
```

The reference taxonomy fields are derived from the detailed resolution result.
They are intended for aggregate analysis of documentation links: for example,
whether `@see` points to the same type, another type in the same package, a
different project package, an external JDK/library symbol, a web URL, or a text
reference. They do not replace canonical CoCoX URIs for resolved project
symbols.

For project-local method references, `referenced_method` intentionally embeds a
compact method context rather than only an excerpt. It includes the referenced
method URI, signature, source code without leading Javadoc, method Javadoc,
parameters, return type, thrown exceptions, annotations, source set, and source
line. CoCoX does not recursively embed that referenced method's callers/callees
inside the Javadoc reference object, to avoid unbounded nested output.

For project-local class/type and field references, CoCoX stores the full
available Javadoc text as `class_javadoc` or `field_javadoc`. External
JDK/library symbols remain symbol-only.

## Same-Class vs Typed-Class Member References

Same-class references omit the class:

```java
@see #isBlank(CharSequence)
```

CoCoX resolves this against the declaring type of the documented method.

Typed-class references name the class/type:

```java
@see StringUtils#isBlank(CharSequence)
@see java.util.regex.Matcher#replaceAll(String)
```

CoCoX first tries project-local type lookup. If the type is not in the parsed
project, the reference becomes an external symbol unless it can be resolved
through imports, language-defined `java.lang.*`, wildcard imports, or cautious
JDK symbol probing. External documentation text is not fetched.

## Import-Aware Resolution

The resolver inspects imports in the declaring source file.

Example source:

```java
import java.util.Arrays;

/**
 * @see Arrays#sort(byte[])
 */
public byte[] sort(byte[] values) { ... }
```

The Javadoc target says `Arrays#sort(byte[])`, but the source import tells us
that `Arrays` means `java.util.Arrays`.

Behavior:

```text
raw target:          Arrays#sort(byte[])
resolved external:   java.util.Arrays#sort(byte[])
resolution:          external_symbol
confidence:          explicit_import
```

Wildcard imports should be handled more carefully:

```java
import java.util.*;
```

In that case, probing `java.util.Arrays` is reasonable only if classpath/JDK
symbols are available. The output records lower confidence than an explicit
single-type import:

```text
external_resolution: wildcard_import_symbol
```

CoCoX also has a small `common_jdk_probe` fallback for common JDK packages.
That fallback is a pragmatic symbol-classification heuristic, not an Apache
Commons Lang rule and not a Javadoc syntax rule.

## Implicit `java.lang.*`

Java implicitly imports the public top-level types of `java.lang` into every
compilation unit. CoCoX therefore checks `java.lang` before heuristic JDK
probing when a Javadoc reference uses a simple type name such as:

```java
@see Long#MIN_VALUE
@see Throwable#addSuppressed(Throwable)
@see SecurityManager
```

as:

```text
java.lang.Long#MIN_VALUE
java.lang.Throwable#addSuppressed(Throwable)
java.lang.SecurityManager
```

The resolution is still symbol-checked against the active JDK/runtime. If a
`java.lang` type is unavailable, CoCoX does not treat it as resolved.


## External Method vs External Field

A target with parentheses is method-like:

```java
@see java.util.regex.Matcher#replaceAll(String)
```

A target without parentheses may be a field or a method with omitted params:

```java
@see java.util.regex.Pattern#DOTALL
@see Class#getConstructor
```

For project-local symbols, CoCoX can inspect the source model and distinguish
fields from methods. For external symbols, CoCoX currently records symbol-only
metadata. The next step is to classify external members more precisely when JDK
or dependency symbols are available:

```json
{
  "kind": "field_reference",
  "resolution": "external_symbol",
  "external_class": "java.util.regex.Pattern",
  "external_member": "DOTALL"
}
```

instead of treating all external `Class#member` refs as generic member refs.

## External Reference Limitation

CoCoX intentionally stops external references at symbol identity. For example,
`@see java.util.regex.Matcher#replaceAll(String)` may be classified as an
external method reference, but CoCoX does not try to load JDK/dependency source
jars or generated Javadoc pages to attach the referenced documentation text.

This is a product boundary, not a parser failure. External documentation
retrieval is build-environment sensitive, often depends on missing source
artifacts, and can make mined datasets harder to interpret. If this is added
later, it should be an explicit optional mode with separate provenance.
