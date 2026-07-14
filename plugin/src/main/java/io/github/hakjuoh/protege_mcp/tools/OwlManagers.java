package io.github.hakjuoh.protege_mcp.tools;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Creates fresh OWL API ontology managers with the thread context classloader (TCCL) temporarily set
 * to the OWL API bundle's classloader.
 *
 * <p>Under Protégé's OSGi runtime, {@link OWLManager#createOWLOntologyManager()} builds a manager by
 * service-loading its ontology factories, parsers and storers through the OWL API's internal injector,
 * which reads the TCCL. When a tool creates a manager the TCCL is protege-mcp's own bundle classloader,
 * which cannot see {@code owlapi}'s {@code META-INF/services}, so the injector fails to resolve some
 * bindings (notably {@code Supplier<OWLOntologyLoaderConfiguration>}) and logs a stream of
 * {@code ERROR No instantiation found for java.util.function.Supplier<...OWLOntologyLoaderConfiguration>}
 * lines. It is non-fatal — a fallback manager is still returned and the tools work — but the ERROR noise
 * on every module/diff/reasoner/SPARQL/governance call obscures real problems in the log. Setting the
 * TCCL to the {@code owlapi} bundle classloader ({@code OWLManager.class.getClassLoader()}) for the
 * duration of the call lets the service loader find those bindings, silencing the errors. The previous
 * TCCL is always restored.
 */
final class OwlManagers {

    private OwlManagers() {
    }

    /** A fresh {@link OWLOntologyManager}, created with the OWL API bundle classloader as TCCL. */
    static OWLOntologyManager create() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(OWLManager.class.getClassLoader());
            return OWLManager.createOWLOntologyManager();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** A fresh concurrent {@link OWLOntologyManager}, created with the OWL API bundle classloader as TCCL. */
    static OWLOntologyManager createConcurrent() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(OWLManager.class.getClassLoader());
            return OWLManager.createConcurrentOWLOntologyManager();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
