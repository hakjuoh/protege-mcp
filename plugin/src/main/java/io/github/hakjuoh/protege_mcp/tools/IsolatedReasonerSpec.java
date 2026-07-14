package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.protege.editor.owl.model.inference.NoOpReasonerInfo;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.NullReasonerProgressMonitor;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * Point-in-time recipe for constructing private reasoners with the selected Protégé plugin's settings.
 *
 * <p>Using only {@link ProtegeOWLReasonerInfo#getReasonerFactory()} silently falls back to the OWLAPI
 * factory defaults. Protégé itself also supplies the plugin's configuration and chooses its recommended
 * buffering mode. This class captures all three values together on the model thread, then permits reasoner
 * construction over a private ontology without consulting mutable Protégé state again.
 *
 * <p>The exact configuration object is retained rather than normalized to {@code SimpleConfiguration}:
 * reasoner plugins may return a private subtype carrying settings beyond the four fields exposed by the
 * OWLAPI interface. The only deliberate substitution is a silent progress monitor, because an isolated
 * background computation must not call Protégé's live classification UI.
 */
final class IsolatedReasonerSpec {

    private final String reasonerId;
    private final String reasonerName;
    private final OWLReasonerFactory delegate;
    private final OWLReasonerConfiguration configuration;
    private final BufferingMode selectedBuffering;
    private final OWLReasonerFactory configuredFactory;

    private IsolatedReasonerSpec(String reasonerId, String reasonerName,
            OWLReasonerFactory delegate, OWLReasonerConfiguration configuration,
            BufferingMode selectedBuffering) {
        this.reasonerId = reasonerId;
        this.reasonerName = reasonerName;
        this.delegate = delegate;
        this.configuration = configuration;
        this.selectedBuffering = selectedBuffering;
        this.configuredFactory = new ConfigurationPreservingFactory();
    }

    /** Capture the selected plugin recipe. Must run inside the Protégé model-thread boundary. */
    static IsolatedReasonerSpec capture(OWLReasonerManager manager) {
        Objects.requireNonNull(manager, "manager");
        ProtegeOWLReasonerInfo info = manager.getCurrentReasonerFactory();
        if (info == null || NoOpReasonerInfo.NULL_REASONER_ID.equals(info.getReasonerId())) {
            return null;
        }
        return capture(info);
    }

    /** Package-private seam for configuration-parity tests. */
    static IsolatedReasonerSpec capture(ProtegeOWLReasonerInfo info) {
        if (info == null) {
            throw new ToolArgException("The selected reasoner plugin did not provide reasoner metadata.");
        }
        OWLReasonerFactory factory;
        OWLReasonerConfiguration config;
        BufferingMode buffering;
        try {
            factory = info.getReasonerFactory();
            config = info.getConfiguration(new NullReasonerProgressMonitor());
            buffering = info.getRecommendedBuffering();
        } catch (RuntimeException e) {
            throw new ToolArgException("Could not capture the selected reasoner configuration: "
                    + message(e));
        }
        if (factory == null) {
            throw new ToolArgException("The selected reasoner plugin returned no OWL reasoner factory.");
        }
        if (config == null) {
            throw new ToolArgException("The selected reasoner plugin returned no reasoner configuration.");
        }
        if (buffering == null) {
            throw new ToolArgException("The selected reasoner plugin returned no buffering mode.");
        }
        String id = info.getReasonerId();
        String name = info.getReasonerName();
        if (name == null || name.isBlank()) {
            name = factory.getReasonerName();
        }
        return new IsolatedReasonerSpec(id, name, factory, config, buffering);
    }

    /** Create the same buffering/non-buffering kind that Protégé would create for classification. */
    OWLReasoner create(OWLOntology ontology) {
        return selectedBuffering == BufferingMode.BUFFERING
                ? configuredFactory.createReasoner(ontology)
                : configuredFactory.createNonBufferingReasoner(ontology);
    }

    /**
     * Factory adapter for libraries such as the OWLAPI explanation generator that insist on choosing
     * the buffering mode themselves. Its no-configuration overloads still inject the captured plugin
     * configuration; explicit-configuration overloads remain honest delegates.
     */
    OWLReasonerFactory configuredFactory() {
        return configuredFactory;
    }

    String reasonerId() {
        return reasonerId;
    }

    String reasonerName() {
        return reasonerName;
    }

    BufferingMode selectedBuffering() {
        return selectedBuffering;
    }

    OWLReasonerConfiguration configuration() {
        return configuration;
    }

    /** Stable, non-secret description suitable for QC/explanation result metadata. */
    Map<String, Object> metadata(BufferingMode actualBuffering) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reasoner_id", reasonerId);
        out.put("reasoner_name", reasonerName);
        out.put("factory_class", delegate.getClass().getName());
        out.put("configuration_class", configuration.getClass().getName());
        out.put("selected_buffering_mode", selectedBuffering.toString());
        out.put("actual_buffering_mode", actualBuffering == null ? null : actualBuffering.toString());
        out.put("fresh_entity_policy", value(configuration.getFreshEntityPolicy()));
        out.put("individual_node_set_policy", value(configuration.getIndividualNodeSetPolicy()));
        out.put("configuration_timeout_ms", configuration.getTimeOut());
        out.put("configuration_object_preserved", true);
        out.put("configuration_parity", null);
        out.put("buffering_parity", actualBuffering == null
                || actualBuffering == selectedBuffering);
        return out;
    }

    /** Add the policy values the created reasoner reports back through OWLAPI. */
    Map<String, Object> metadata(OWLReasoner reasoner) {
        Map<String, Object> out = metadata(reasoner.getBufferingMode());
        FreshEntityPolicy actualFresh = reasoner.getFreshEntityPolicy();
        IndividualNodeSetPolicy actualIndividuals = reasoner.getIndividualNodeSetPolicy();
        boolean parity = actualFresh == configuration.getFreshEntityPolicy()
                && actualIndividuals == configuration.getIndividualNodeSetPolicy();
        out.put("actual_fresh_entity_policy", value(actualFresh));
        out.put("actual_individual_node_set_policy", value(actualIndividuals));
        out.put("configuration_parity", parity);
        List<String> caveats = new ArrayList<>();
        if (actualFresh != configuration.getFreshEntityPolicy()) {
            caveats.add("The reasoner reports fresh_entity_policy=" + actualFresh
                    + " after receiving " + configuration.getFreshEntityPolicy() + ".");
        }
        if (actualIndividuals != configuration.getIndividualNodeSetPolicy()) {
            caveats.add("The reasoner reports individual_node_set_policy=" + actualIndividuals
                    + " after receiving " + configuration.getIndividualNodeSetPolicy() + ".");
        }
        out.put("caveats", caveats);
        return out;
    }

    private OWLReasoner validate(OWLReasoner reasoner, BufferingMode requested) {
        if (reasoner == null) {
            throw new ToolArgException("The selected reasoner factory returned no reasoner instance.");
        }
        try {
            BufferingMode actualBuffering = reasoner.getBufferingMode();
            if (actualBuffering != requested) {
                try {
                    reasoner.dispose();
                } catch (RuntimeException ignored) {
                    // The parity error is the actionable failure; a broken dispose must not replace it.
                }
                throw new ToolArgException("The selected reasoner did not honor the requested isolated "
                        + "buffering mode (requested=" + requested + ", actual="
                        + actualBuffering + ").");
            }
            return reasoner;
        } catch (ToolArgException e) {
            throw e;
        } catch (RuntimeException e) {
            try {
                reasoner.dispose();
            } catch (RuntimeException ignored) {
                // Keep the configuration inspection failure.
            }
            throw new ToolArgException("Could not verify the isolated reasoner configuration: "
                    + message(e));
        }
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private final class ConfigurationPreservingFactory implements OWLReasonerFactory {
        @Override
        public String getReasonerName() {
            return delegate.getReasonerName();
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            return createReasoner(ontology, configuration);
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            return createNonBufferingReasoner(ontology, configuration);
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology,
                OWLReasonerConfiguration requestedConfiguration) {
            OWLReasonerConfiguration requested = Objects.requireNonNull(requestedConfiguration,
                    "requestedConfiguration");
            return validate(delegate.createReasoner(ontology, requested), BufferingMode.BUFFERING);
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology,
                OWLReasonerConfiguration requestedConfiguration) {
            OWLReasonerConfiguration requested = Objects.requireNonNull(requestedConfiguration,
                    "requestedConfiguration");
            return validate(delegate.createNonBufferingReasoner(ontology, requested),
                    BufferingMode.NON_BUFFERING);
        }
    }
}
