package org.polarsys.capella.ai.scripting;

import groovy.lang.GroovyClassLoader;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.FieldASTTransformation;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Compiles decoded proposals into transient bytecode, without defining or initializing their
 * classes. This is a language/policy check, not a dry run or proof of native API correctness.
 */
public final class GroovyCompilationValidator {
    private static final int MAX_SOURCE = 100000;
    private static final int MAX_DIAGNOSTICS = 12;
    private static final long DEFAULT_TIMEOUT_MS = 2000;
    private static final String GLOBAL_TRANSFORMS =
            "META-INF/services/org.codehaus.groovy.transform.ASTTransformation";
    private static final AtomicBoolean OCCUPIED = new AtomicBoolean();
    private static final ExecutorService COMPILER =
            Executors.newSingleThreadExecutor(
                    action -> {
                        Thread thread = new Thread(action, "Capella-Groovy-Compile-Only");
                        thread.setDaemon(true);
                        return thread;
                    });

    public enum Status {
        VALID,
        COMPILATION_ERROR,
        SECURITY_VIOLATION,
        INVALID_INPUT,
        CANCELLED,
        TIMED_OUT,
        UNAVAILABLE
    }

    /** Messages contain no original source excerpt, arbitrary exception, stack or file path. */
    public record Diagnostic(int line, int column, String message) {}

    public record Report(
            Status status, List<Diagnostic> diagnostics, boolean truncated, long durationMs) {
        public Report {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean success() {
            return status == Status.VALID;
        }

        public boolean repairable() {
            return status == Status.COMPILATION_ERROR
                    || status == Status.SECURITY_VIOLATION
                    || status == Status.INVALID_INPUT;
        }

        public Map<String, Object> feedback() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", "compile_only");
            result.put("status", status.name());
            result.put("success", success());
            result.put("repairable", repairable());
            result.put("codeExecuted", false);
            result.put("applied", false);
            result.put("saved", false);
            result.put(
                    "diagnostics",
                    diagnostics.stream()
                            .map(
                                    item ->
                                            Map.<String, Object>of(
                                                    "line",
                                                    item.line(),
                                                    "column",
                                                    item.column(),
                                                    "message",
                                                    item.message()))
                            .toList());
            result.put("truncated", truncated);
            result.put(
                    "scope",
                    "Groovy compilation and policy only. Native API behavior and engineering"
                        + " correctness are not executed or verified.");
            return Collections.unmodifiableMap(result);
        }

        public String formatForRepair() {
            StringBuilder text =
                    new StringBuilder("Compile-only validation: ")
                            .append(status)
                            .append(".\n")
                            .append(
                                    "No code was executed; no model changes, Undo command or save"
                                        + " occurred.\n");
            for (Diagnostic item : diagnostics) {
                text.append("Line ")
                        .append(item.line())
                        .append(", column ")
                        .append(item.column())
                        .append(": ")
                        .append(item.message())
                        .append('\n');
            }
            if (truncated) text.append("Additional compiler diagnostics were omitted.\n");
            text.append(
                    "This check does not verify native API behavior or engineering correctness.");
            return text.substring(0, Math.min(text.length(), 8192));
        }
    }

    private GroovyCompilationValidator() {}

    public static Report validate(String source, BooleanSupplier cancelled) {
        return validate(source, cancelled, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Bounds the caller's wait. Groovy/JVM compilation cannot always be forcibly interrupted; a
     * busy compiler retains its single slot until it finishes. It never runs proposal code.
     */
    public static Report validate(String source, BooleanSupplier cancelled, long timeoutMs) {
        long started = System.nanoTime();
        if (timeoutMs < 1 || timeoutMs > 10000)
            throw new IllegalArgumentException("Compile timeout must be 1..10000 milliseconds");
        Control control =
                new Control(cancelled == null ? () -> false : cancelled, started, timeoutMs);
        try {
            control.check();
        } catch (Cancelled failure) {
            return single(
                    Status.CANCELLED,
                    "Compilation was cancelled; do not automatically retry.",
                    started);
        } catch (Deadline failure) {
            return single(
                    Status.TIMED_OUT,
                    "Compilation exceeded its bounded wait; syntax was not determined.",
                    started);
        }
        if (source == null || source.isBlank())
            return single(
                    Status.INVALID_INPUT, "Proposal source must be nonempty Groovy.", started);
        if (source.length() > MAX_SOURCE)
            return single(
                    Status.INVALID_INPUT,
                    "Proposal exceeds the 100000-character source limit.",
                    started);
        if (!OCCUPIED.compareAndSet(false, true))
            return single(
                    Status.UNAVAILABLE,
                    "A previous compiler is still finishing; validation is unavailable.",
                    started);
        AtomicBoolean released = new AtomicBoolean();
        Runnable release =
                () -> {
                    if (released.compareAndSet(false, true)) OCCUPIED.set(false);
                };
        FutureTask<Report> task =
                new FutureTask<>(
                        () -> {
                            try {
                                return compile(source, control, started);
                            } finally {
                                release.run();
                            }
                        });
        // Release only when the actual compiler exits, including cancellation before it started.
        COMPILER.execute(
                () -> {
                    try {
                        task.run();
                    } finally {
                        release.run();
                    }
                });
        try {
            while (true) {
                control.check();
                try {
                    return task.get(
                            Math.min(control.remaining(), TimeUnit.MILLISECONDS.toNanos(50)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException waiting) {
                    /* Check cancellation/deadline without blocking SWT. */
                }
            }
        } catch (Cancelled | CancellationException failure) {
            task.cancel(true);
            return single(
                    Status.CANCELLED,
                    "Compilation was cancelled; do not automatically retry.",
                    started);
        } catch (Deadline failure) {
            task.cancel(true);
            return single(
                    Status.TIMED_OUT,
                    "Compilation exceeded its bounded wait; syntax was not determined.",
                    started);
        } catch (InterruptedException failure) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return single(
                    Status.CANCELLED,
                    "Compilation was interrupted; do not automatically retry.",
                    started);
        } catch (ExecutionException failure) {
            return single(
                    Status.UNAVAILABLE,
                    "The compiler could not complete this check. No proposal code was executed.",
                    started);
        }
    }

    private static Report compile(String source, Control control, long started) {
        try {
            control.check();
            if (ApiCatalog.validateCode(source) != null) {
                return single(
                        Status.COMPILATION_ERROR,
                        "Unsupported Capella API method or code pattern; inspect the shipped API"
                            + " catalog and correct the proposal.",
                        started);
            }
            CompilerConfiguration configuration = GroovyExecutor.createSecureConfig();
            configuration.setTargetDirectory((java.io.File) null);
            configuration.setRecompileGroovySource(false);
            try (CompileLoader loader = new CompileLoader(configuration)) {
                CompilationUnit unit = compilationUnit(configuration, loader);
                unit.addSource("Proposal.groovy", source);
                // No source annotation transform is allowed to run before this independent guard.
                unit.compile(Phases.CONVERSION);
                control.check();
                List<SourceField> fields = new ArrayList<>();
                for (ModuleNode module : unit.getAST().getModules())
                    guardAnnotations(module, control, fields);
                // Invoke only this known inert transform directly. Groovy Eclipse otherwise
                // defines and executes an expression class to discover even @Field metadata.
                // Removing the source marker prevents that discovery path, while retaining
                // ordinary field AST semantics without evaluating its initializer.
                for (SourceField field : fields) {
                    control.check();
                    field.node().getAnnotations().remove(field.annotation());
                    // The target parser may defer this association until its annotation visitor.
                    // Establish only the class already visited in this source module.
                    if (field.node().getDeclaringClass() == null)
                        field.node().setDeclaringClass(field.owner());
                    new FieldASTTransformation()
                            .visit(
                                    new ASTNode[] {
                                        new AnnotationNode(
                                                ClassHelper.make(groovy.transform.Field.class)),
                                        field.node()
                                    },
                                    field.source());
                }
                for (int phase :
                        List.of(
                                Phases.SEMANTIC_ANALYSIS,
                                Phases.CANONICALIZATION,
                                Phases.INSTRUCTION_SELECTION,
                                Phases.CLASS_GENERATION)) {
                    control.check();
                    unit.compile(phase);
                }
                control.check();
                return new Report(Status.VALID, List.of(), false, elapsed(started));
            }
        } catch (SourceAnnotation failure) {
            return new Report(
                    Status.SECURITY_VIOLATION,
                    List.of(
                            new Diagnostic(
                                    failure.line,
                                    failure.column,
                                    "Compile-time annotations are disabled; only"
                                        + " groovy.transform.Field without arguments is allowed.")),
                    false,
                    elapsed(started));
        } catch (MultipleCompilationErrorsException failure) {
            return diagnostics(failure, started);
        } catch (Cancelled failure) {
            return single(
                    Status.CANCELLED,
                    "Compilation was cancelled; do not automatically retry.",
                    started);
        } catch (Deadline failure) {
            return single(
                    Status.TIMED_OUT,
                    "Compilation exceeded its bounded wait; syntax was not determined.",
                    started);
        } catch (SecurityException failure) {
            return single(
                    Status.SECURITY_VIOLATION,
                    "The Groovy safety policy rejected an operation. Use the shipped model APIs.",
                    started);
        } catch (IOException | CompilationFailedException failure) {
            return single(
                    Status.UNAVAILABLE,
                    "The compiler could not complete this check. No proposal code was executed.",
                    started);
        }
    }

    private static CompilationUnit compilationUnit(
            CompilerConfiguration configuration, GroovyClassLoader loader) {
        // Groovy Eclipse has no upstream four-argument constructor. Its compatible constructor
        // defaults the transform loader to the compilation loader; verify that identity before
        // supplying source so neither external source nor transform discovery can escape filtering.
        CompilationUnit unit = new CompilationUnit(configuration, null, loader);
        if (unit.getClassLoader() != loader || unit.getTransformLoader() != loader) {
            throw new SecurityException(
                    "Compilation must use the filtered source and transform loader");
        }
        return unit;
    }

    private record SourceField(
            AnnotatedNode node, AnnotationNode annotation, SourceUnit source, ClassNode owner) {}

    private static void guardAnnotations(
            ModuleNode module, Control control, List<SourceField> fields) {
        Set<AnnotationNode> fieldMarkers = Collections.newSetFromMap(new IdentityHashMap<>());
        var visitor =
                new ClassCodeVisitorSupport() {
                    private ClassNode owner;

                    @Override
                    protected SourceUnit getSourceUnit() {
                        return module.getContext();
                    }

                    @Override
                    public void visitClass(ClassNode type) {
                        ClassNode previous = owner;
                        owner = type;
                        try {
                            super.visitClass(type);
                        } finally {
                            owner = previous;
                        }
                    }

                    @Override
                    public void visitAnnotations(AnnotatedNode node) {
                        control.check();
                        for (AnnotationNode annotation : node.getAnnotations()) {
                            String name = annotation.getClassNode().getName();
                            // Groovy Eclipse marks its generated script run() with this inert JDK
                            // annotation at no source location. Source-written annotations still
                            // fail.
                            if (node instanceof MethodNode method
                                    && method.isScriptBody()
                                    && "run".equals(method.getName())
                                    && "java.lang.Override".equals(name)
                                    && annotation.getLineNumber() == -1
                                    && annotation.getColumnNumber() == -1
                                    && annotation.getMembers().isEmpty()) continue;
                            ImportNode imported = module.getImport(name);
                            boolean field =
                                    "groovy.transform.Field".equals(name)
                                            || imported != null
                                                    && "groovy.transform.Field"
                                                            .equals(imported.getType().getName());
                            if (!field || !annotation.getMembers().isEmpty())
                                throw new SourceAnnotation(annotation);
                            if (fieldMarkers.add(annotation))
                                fields.add(
                                        new SourceField(
                                                node, annotation, module.getContext(), owner));
                        }
                    }

                    @Override
                    public void visitDeclarationExpression(DeclarationExpression expression) {
                        visitAnnotations(expression);
                        super.visitDeclarationExpression(expression);
                    }

                    private void visitParameter(Parameter parameter) {
                        visitAnnotations(parameter);
                        if (parameter.hasInitialExpression())
                            parameter.getInitialExpression().visit(this);
                    }

                    @Override
                    protected void visitConstructorOrMethod(
                            MethodNode method, boolean constructor) {
                        for (Parameter parameter : method.getParameters())
                            visitParameter(parameter);
                        super.visitConstructorOrMethod(method, constructor);
                    }

                    @Override
                    public void visitClosureExpression(ClosureExpression expression) {
                        if (expression.getParameters() != null) {
                            for (Parameter parameter : expression.getParameters())
                                visitParameter(parameter);
                        }
                        super.visitClosureExpression(expression);
                    }

                    @Override
                    public void visitForLoop(ForStatement statement) {
                        visitParameter(statement.getVariable());
                        super.visitForLoop(statement);
                    }

                    @Override
                    public void visitCatchStatement(CatchStatement statement) {
                        visitParameter(statement.getVariable());
                        super.visitCatchStatement(statement);
                    }
                };
        if (module.getPackage() != null) visitor.visitAnnotations(module.getPackage());
        for (ImportNode imported : module.getImports()) visitor.visitAnnotations(imported);
        for (ImportNode imported : module.getStarImports()) visitor.visitAnnotations(imported);
        for (ImportNode imported : module.getStaticImports().values())
            visitor.visitAnnotations(imported);
        for (ImportNode imported : module.getStaticStarImports().values())
            visitor.visitAnnotations(imported);
        Set<ClassNode> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ClassNode> queue = new ArrayList<>(module.getClasses());
        for (int index = 0; index < queue.size(); index++) {
            control.check();
            ClassNode type = queue.get(index);
            if (!checked.add(type)) continue;
            visitor.visitClass(type);
            type.getInnerClasses().forEachRemaining(queue::add);
        }
    }

    private static Report diagnostics(MultipleCompilationErrorsException failure, long started) {
        List<Diagnostic> result = new ArrayList<>();
        Status status = Status.COMPILATION_ERROR;
        List<?> errors = failure.getErrorCollector().getErrors();
        if (errors == null || errors.isEmpty())
            return single(status, "Groovy compilation failed; check the proposal syntax.", started);
        for (Object error : errors) {
            if (result.size() == MAX_DIAGNOSTICS) break;
            if (error instanceof SyntaxErrorMessage syntax) {
                SyntaxException cause = syntax.getCause();
                result.add(
                        new Diagnostic(
                                Math.max(0, cause.getStartLine()),
                                Math.max(0, cause.getStartColumn()),
                                syntaxGuidance(cause.getOriginalMessage())));
            } else if (error instanceof ExceptionMessage message
                    && message.getCause() instanceof SecurityException) {
                status = Status.SECURITY_VIOLATION;
                result.add(
                        new Diagnostic(
                                0,
                                0,
                                "The Groovy safety policy rejected an operation. Use the shipped"
                                    + " model APIs."));
            } else {
                result.add(
                        new Diagnostic(
                                0,
                                0,
                                "Groovy compilation failed; check imports, declarations and"
                                    + " syntax."));
            }
        }
        return new Report(status, result, errors.size() > MAX_DIAGNOSTICS, elapsed(started));
    }

    /**
     * Classifies compiler text, never forwards it or source data into provider-facing diagnostics.
     */
    private static String syntaxGuidance(String original) {
        String message = original == null ? "" : original.toLowerCase(java.util.Locale.ROOT);
        if (message.contains("unable to resolve class"))
            return "An imported or referenced type cannot be resolved; use available native classes"
                       + " and correct imports.";
        if (message.contains("scope already contains a variable")
                || message.contains("duplicate variable"))
            return "A variable is declared twice in this scope; rename it or reuse the existing"
                       + " declaration.";
        if (message.contains("repetitive method name") || message.contains("duplicate method"))
            return "A method has a duplicate signature; remove or change the repeated declaration.";
        if (message.contains("abstract method"))
            return "An abstract method or class declaration is inconsistent; check modifiers and"
                       + " method bodies.";
        if (message.contains("tried to assign a value to the class"))
            return "The assignment targets a type instead of a variable; choose an ordinary"
                       + " variable name.";
        return "Unexpected or incomplete Groovy syntax; check delimiters, quoting and declarations"
                   + " at this location.";
    }

    private static Report single(Status status, String message, long started) {
        return new Report(status, List.of(new Diagnostic(0, 0, message)), false, elapsed(started));
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static final class CompileLoader extends GroovyClassLoader {
        CompileLoader(CompilerConfiguration configuration) {
            super(GroovyExecutor.class.getClassLoader(), configuration, false);
            setResourceLoader(name -> null);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return GLOBAL_TRANSFORMS.equals(name)
                    ? Collections.emptyEnumeration()
                    : super.getResources(name);
        }

        @Override
        public URL getResource(String name) {
            return GLOBAL_TRANSFORMS.equals(name) ? null : super.getResource(name);
        }

        @Override
        public Class<?> loadClass(
                String name,
                boolean lookupScriptFiles,
                boolean preferClassOverScript,
                boolean resolve)
                throws ClassNotFoundException, CompilationFailedException {
            // Dependency resolution may load compiled native types, never another Groovy source or
            // its transforms.
            return super.loadClass(name, false, true, resolve);
        }

        @Override
        public Class<?> defineClass(String name, byte[] bytes) {
            throw new SecurityException(
                    "Proposal classes must remain undefined during compile-only validation");
        }

        @Override
        public Class<?> defineClass(ClassNode type, String file, String codeBase) {
            throw new SecurityException(
                    "Proposal classes must remain undefined during compile-only validation");
        }
    }

    private record Control(BooleanSupplier cancelled, long started, long timeoutMs) {
        long remaining() {
            return Math.max(
                    1, TimeUnit.MILLISECONDS.toNanos(timeoutMs) - (System.nanoTime() - started));
        }

        void check() {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw new Cancelled();
            if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMs))
                throw new Deadline();
        }
    }

    private static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Deadline extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class SourceAnnotation extends SecurityException {
        private static final long serialVersionUID = 1L;
        final int line, column;

        SourceAnnotation(AnnotationNode node) {
            line = Math.max(0, node.getLineNumber());
            column = Math.max(0, node.getColumnNumber());
        }
    }
}
