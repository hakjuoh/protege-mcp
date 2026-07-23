package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/** Shared exact and alternate-provenance collision analysis for materialization commits. */
public final class MaterializationCollisions {
    private MaterializationCollisions() {
    }

    public static State analyze(OWLOntology target, Set<OWLAxiom> requested) {
        if (target == null || requested == null) {
            throw new IllegalArgumentException("target and requested axioms are required");
        }
        Map<OWLAxiom, List<OWLAxiom>> byLogical = new LinkedHashMap<>();
        for (OWLAxiom axiom : requested) {
            byLogical.computeIfAbsent(axiom.getAxiomWithoutAnnotations(),
                    ignored -> new ArrayList<>()).add(axiom);
        }
        Set<OWLAxiom> exact = new LinkedHashSet<>();
        Set<OWLAxiom> conflictingRequested = new LinkedHashSet<>();
        Set<OWLAxiom> differentForms = new LinkedHashSet<>();
        for (OWLAxiom existing : target.getAxioms(Imports.EXCLUDED)) {
            List<OWLAxiom> candidates = byLogical.get(existing.getAxiomWithoutAnnotations());
            if (candidates == null) continue;
            for (OWLAxiom requestedAxiom : candidates) {
                if (existing.equals(requestedAxiom)) {
                    exact.add(requestedAxiom);
                } else {
                    conflictingRequested.add(requestedAxiom);
                    differentForms.add(existing);
                }
            }
        }
        return new State(exact.size(), conflictingRequested.size(), differentForms);
    }

    public record State(int existing, int logical, Set<OWLAxiom> differentForms) {
        public State {
            if (existing < 0 || logical < 0 || differentForms == null) {
                throw new IllegalArgumentException("invalid materialization collision state");
            }
            differentForms = Set.copyOf(differentForms);
        }

        public boolean exactOnly(int requestedCount) {
            return existing == requestedCount && logical == 0;
        }

        public boolean allExact(int requestedCount) {
            return existing == requestedCount;
        }

        public boolean commitComplete(int requestedCount, String collisionMode) {
            return existing == requestedCount
                    && (!"replace".equals(collisionMode) || logical == 0);
        }
    }
}
