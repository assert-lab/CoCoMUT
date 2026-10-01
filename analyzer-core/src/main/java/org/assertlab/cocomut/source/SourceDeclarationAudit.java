package org.assertlab.cocomut.source;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks syntax and source declaration coverage before trusting Spoon's model. */
final class SourceDeclarationAudit {
    record Declaration(String name, int nameOffset, boolean constructor) {}
    record SourceFile(List<Declaration> declarations, String syntaxFailure) {
        SourceFile { declarations = List.copyOf(declarations); }
    }

    static SourceFile read(Path file, int compliance) throws IOException {
        Map<String, String> options = new HashMap<>();
        String level = compliance == 8 ? "1.8" : Integer.toString(compliance);
        options.put(CompilerOptions.OPTION_Source, level);
        options.put(CompilerOptions.OPTION_Compliance, level);
        options.put(CompilerOptions.OPTION_TargetPlatform, level);
        Parser parser = new Parser(new ProblemReporter(
                DefaultErrorHandlingPolicies.proceedWithAllProblems(), new CompilerOptions(options),
                new DefaultProblemFactory(java.util.Locale.ROOT)), true);
        CompilationUnit input = new CompilationUnit(Files.readString(file).toCharArray(),
                file.toString(), "UTF-8");
        CompilationResult result = new CompilationResult(input, 0, 1, 100);
        CompilationUnitDeclaration unit = parser.parse(input, result);
        List<Declaration> declarations = new ArrayList<>();
        unit.traverse(new ASTVisitor() {
            @Override public boolean visit(MethodDeclaration method, ClassScope scope) {
                declarations.add(new Declaration(new String(method.selector), method.sourceStart, false));
                return true;
            }
            @Override public boolean visit(ConstructorDeclaration method, ClassScope scope) {
                if (!method.isDefaultConstructor()) {
                    declarations.add(new Declaration(new String(method.selector), method.sourceStart, true));
                }
                return true;
            }
        }, unit.scope);
        // No bindings are resolved: unavailable dependencies cannot invalidate
        // the independent syntax/declaration inventory. ECJ is already Spoon's
        // parser dependency; its compiler API avoids Eclipse plugin runtime state.
        String failure = result.hasErrors()
                ? "line " + result.getErrors()[0].getSourceLineNumber() + ": " + result.getErrors()[0].getMessage()
                : "";
        return new SourceFile(declarations, failure);
    }

    static Map<Path, List<CtExecutable<?>>> executables(List<CtModel> models) {
        Map<Path, List<CtExecutable<?>>> result = new HashMap<>();
        for (CtModel model : models) {
            for (CtExecutable<?> method : model.getElements(new TypeFilter<>(CtExecutable.class))) {
                if (!(method instanceof CtMethod<?> || method instanceof CtConstructor<?>)) continue;
                var position = method.getPosition();
                if (position == null || !position.isValidPosition()) continue;
                Path file = position.getFile().toPath().toAbsolutePath().normalize();
                result.computeIfAbsent(file, ignored -> new ArrayList<>()).add(method);
            }
        }
        return result;
    }

    static int missing(SourceFile source, List<CtExecutable<?>> methods) {
        int missing = 0;
        for (Declaration declaration : source.declarations()) {
            boolean present = methods.stream().anyMatch(method -> {
                boolean constructor = method instanceof CtConstructor<?>;
                var position = method.getPosition();
                // Spoon numbers local type names (for example 1Local). The
                // exact declaration-name offset identifies a local constructor
                // without guessing a mapping between source and model names.
                return declaration.constructor() == constructor
                        && (constructor || declaration.name().equals(method.getSimpleName()))
                        && position instanceof spoon.reflect.cu.position.DeclarationSourcePosition declarationPosition
                        && declarationPosition.getNameStart() <= declaration.nameOffset()
                        && declarationPosition.getNameEnd() >= declaration.nameOffset();
            });
            if (!present) missing++;
        }
        return missing;
    }
}
