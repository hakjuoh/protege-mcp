package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixed in-package registry of competency-question storage conventions and the selection rules for
 * mutating calls. Not a {@code ServiceLoader}: classpath discovery is fragile under Protégé's OSGi/TCCL
 * classloading (the same reason SPARQL rejects SERVICE), so the providers are listed here explicitly.
 *
 * <p>Write selection precedence (plan §3.2): an explicit {@code convention} always wins (it may create a
 * second store alongside an existing one); else the single detected convention is followed; else multiple
 * detected requires an explicit choice; else the default {@link Cq#CONV_ROBOT} is created — falling back to
 * {@link Cq#CONV_ANNOTATIONS} when the ontology has no saved document to place a file next to.
 */
final class CqStores {

    private CqStores() {
    }

    private static final List<CqStore> ALL = List.of(
            new SidecarManifestStore(),
            new RobotSparqlDirStore(),
            new OntologyAnnotationsStore());

    static List<CqStore> all() {
        return ALL;
    }

    static CqStore byId(String conventionId) {
        for (CqStore s : ALL) {
            if (s.conventionId().equalsIgnoreCase(conventionId)) {
                return s;
            }
        }
        throw new ToolArgException("Unknown convention '" + conventionId + "'. Use one of: " + ids(ALL) + ".");
    }

    /** The conventions whose CQs are present in {@code ctx}, in registry order. */
    static List<CqStore> detected(CqContext ctx) {
        List<CqStore> out = new ArrayList<>();
        for (CqStore s : ALL) {
            if (s.detect(ctx)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Pick the store an {@code add}/{@code remove} should operate on (see the precedence above). */
    static CqStore selectForWrite(CqContext ctx, String requestedConvention) {
        if (requestedConvention != null) {
            CqStore s = byId(requestedConvention);
            if (!s.isWritable(ctx)) {
                throw new ToolArgException(cannotWrite(s));
            }
            return s;
        }
        List<CqStore> detected = detected(ctx);
        if (detected.size() == 1) {
            return detected.get(0);
        }
        if (detected.size() > 1) {
            throw new ToolArgException("Multiple CQ conventions are present (" + ids(detected)
                    + "). Pass convention= to choose which one to write to.");
        }
        // None detected: default to the ROBOT dir, or the ontology-annotations fallback if unsaved.
        CqStore robot = byId(Cq.CONV_ROBOT);
        return robot.isWritable(ctx) ? robot : byId(Cq.CONV_ANNOTATIONS);
    }

    private static String cannotWrite(CqStore s) {
        return "convention=" + s.conventionId() + " needs the active ontology saved to a local file "
                + "(there is no folder to write in). Save it with save_ontology, or use "
                + "convention=" + Cq.CONV_ANNOTATIONS + " to store CQs inside the ontology.";
    }

    private static String ids(List<CqStore> stores) {
        List<String> out = new ArrayList<>();
        for (CqStore s : stores) {
            out.add(s.conventionId());
        }
        return String.join(", ", out);
    }
}
