package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

class ActiveOntologyEventsTest {

    @Test
    void explorerSelectionReliesOnProtegeToEmitOneChangeEvent() throws Exception {
        OWLOntology first = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/first"));
        OWLOntology second = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/second"));
        AtomicReference<OWLOntology> active = new AtomicReference<>(first);
        AtomicInteger selections = new AtomicInteger();
        AtomicInteger manualEvents = new AtomicInteger();
        OWLModelManager manager = (OWLModelManager) Proxy.newProxyInstance(
                OWLModelManager.class.getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getActiveOntology" -> active.get();
                    case "setActiveOntology" -> {
                        active.set((OWLOntology) arguments[0]);
                        selections.incrementAndGet();
                        yield null;
                    }
                    case "fireEvent" -> {
                        manualEvents.incrementAndGet();
                        yield null;
                    }
                    default -> null;
                });

        assertTrue(ActiveOntologyEvents.activate(manager, second));
        assertEquals(second, active.get());
        assertEquals(1, selections.get());
        assertEquals(0, manualEvents.get(),
                "setActiveOntology already emits the host event; the explorer must not duplicate it");
        assertFalse(ActiveOntologyEvents.activate(manager, second));
        assertEquals(1, selections.get());
    }

    @Test
    void observerAnnouncesEachRealSelectionOnce() throws Exception {
        OWLOntology first = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/first"));
        OWLOntology second = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/second"));
        ActiveOntologyEvents events = new ActiveOntologyEvents(first);

        assertFalse(events.observe(first));
        assertTrue(events.observe(second));
        assertFalse(events.observe(second));
        assertTrue(events.observe(first));
        assertFalse(events.observe(null));
    }
}
