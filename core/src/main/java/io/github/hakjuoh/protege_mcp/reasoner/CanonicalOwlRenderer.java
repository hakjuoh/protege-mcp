package io.github.hakjuoh.protege_mcp.reasoner;

import java.io.Writer;
import java.util.ArrayDeque;
import java.util.List;

import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.HasAnnotations;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.semanticweb.owlapi.util.OWLObjectVisitorExAdapter;
import org.semanticweb.owlapi.util.OWLObjectWalker;

/** Prefix-free Functional Syntax rendering with a hard allocation bound. */
final class CanonicalOwlRenderer {

    private CanonicalOwlRenderer() {
    }

    static String render(OWLOntology context, OWLObject object, int maximumCharacters,
            int maximumNodes, int maximumDepth, Runnable deadlineCheck) {
        if (context == null || object == null || maximumCharacters < 1 || maximumNodes < 1
                || maximumDepth < 1 || deadlineCheck == null) {
            throw new IllegalArgumentException("context, object, and positive bounds are required");
        }
        guardAnnotationDepth(object, maximumNodes, maximumDepth, deadlineCheck);
        guardStructure(object, maximumNodes, maximumDepth, deadlineCheck);
        BoundedWriter writer = new BoundedWriter(maximumCharacters, deadlineCheck);
        FunctionalSyntaxDocumentFormat format = new FunctionalSyntaxDocumentFormat();
        format.clear();
        FunctionalSyntaxObjectRenderer renderer =
                new FunctionalSyntaxObjectRenderer(context, format, writer);
        DefaultPrefixManager prefixes = new DefaultPrefixManager();
        prefixes.clear();
        renderer.setPrefixManager(prefixes);
        renderer.setAddMissingDeclarations(false);
        try {
            object.accept(renderer);
        } catch (StackOverflowError tooDeep) {
            throw new RenderLimitException("canonical_object_depth", maximumDepth,
                    (long) maximumDepth + 1);
        }
        return writer.value().replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static void guardAnnotationDepth(OWLObject object, int maximumNodes,
            int maximumDepth, Runnable deadlineCheck) {
        if (!(object instanceof HasAnnotations annotated)) return;
        ArrayDeque<AnnotationDepth> pending = new ArrayDeque<>();
        for (OWLAnnotation annotation : annotated.getAnnotations()) {
            pending.addLast(new AnnotationDepth(annotation, 1));
        }
        int nodes = 0;
        while (!pending.isEmpty()) {
            deadlineCheck.run();
            AnnotationDepth next = pending.removeLast();
            if (++nodes > maximumNodes) {
                throw new RenderLimitException("canonical_object_nodes", maximumNodes, nodes);
            }
            if (next.depth > maximumDepth) {
                throw new RenderLimitException("canonical_object_depth", maximumDepth,
                        next.depth);
            }
            for (OWLAnnotation child : next.annotation.getAnnotations()) {
                pending.addLast(new AnnotationDepth(child, next.depth + 1));
            }
        }
    }

    private static void guardStructure(OWLObject object, int maximumNodes, int maximumDepth,
            Runnable deadlineCheck) {
        // Repeated nodes must still reach the guard. StructureWalker recursively descends
        // shared subexpressions even when duplicate callbacks are suppressed, which would let
        // a compact DAG cause exponential unmetered work before rendering starts.
        OWLObjectWalker<OWLObject> walker = new OWLObjectWalker<>(List.of(object), true);
        class Guard extends OWLObjectVisitorExAdapter<Void> {
            private int nodes;

            Guard() {
                super(null);
            }

            @Override
            protected Void doDefault(OWLObject value) {
                deadlineCheck.run();
                if (++nodes > maximumNodes) {
                    throw new RenderLimitException("canonical_object_nodes", maximumNodes, nodes);
                }
                int depth = Math.max(walker.getClassExpressionPath().size(),
                        walker.getDataRangePath().size());
                if (depth > maximumDepth) {
                    throw new RenderLimitException("canonical_object_depth", maximumDepth, depth);
                }
                return null;
            }
        }
        try {
            walker.walkStructure(new Guard());
        } catch (StackOverflowError tooDeep) {
            throw new RenderLimitException("canonical_object_depth", maximumDepth,
                    (long) maximumDepth + 1);
        }
    }

    static final class RenderLimitException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String budget;
        private final long maximum;
        private final long observed;

        RenderLimitException(int maximumCharacters) {
            this("canonical_object_characters", maximumCharacters,
                    (long) maximumCharacters + 1);
        }

        RenderLimitException(String budget, long maximum, long observed) {
            super("canonical OWL rendering exceeds " + budget + "=" + maximum);
            this.budget = budget;
            this.maximum = maximum;
            this.observed = observed;
        }

        String budget() {
            return budget;
        }

        long maximum() {
            return maximum;
        }

        long observed() {
            return observed;
        }
    }

    private record AnnotationDepth(OWLAnnotation annotation, int depth) { }

    private static final class BoundedWriter extends Writer {
        private final int maximumCharacters;
        private final Runnable deadlineCheck;
        private final StringBuilder out = new StringBuilder();

        BoundedWriter(int maximumCharacters, Runnable deadlineCheck) {
            this.maximumCharacters = maximumCharacters;
            this.deadlineCheck = deadlineCheck;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            append(buffer, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) {
            ensure(length);
            out.append(value, offset, offset + length);
        }

        @Override
        public void write(int character) {
            ensure(1);
            out.append((char) character);
        }

        @Override
        public Writer append(CharSequence value) {
            String safe = String.valueOf(value);
            write(safe, 0, safe.length());
            return this;
        }

        @Override
        public Writer append(CharSequence value, int start, int end) {
            String safe = String.valueOf(value);
            write(safe, start, end - start);
            return this;
        }

        @Override
        public Writer append(char character) {
            write(character);
            return this;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String value() {
            return out.toString();
        }

        private void append(char[] buffer, int offset, int length) {
            ensure(length);
            out.append(buffer, offset, length);
        }

        private void ensure(int added) {
            deadlineCheck.run();
            if (added < 0 || out.length() > maximumCharacters - added) {
                throw new RenderLimitException(maximumCharacters);
            }
        }
    }
}
