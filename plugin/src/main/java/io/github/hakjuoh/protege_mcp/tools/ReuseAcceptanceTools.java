package io.github.hakjuoh.protege_mcp.tools;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.RemoveAxiom;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ReuseAction;
import io.github.hakjuoh.protege_mcp.external.ReuseMintReceipt;
import io.github.hakjuoh.protege_mcp.external.ReuseOperation;
import io.github.hakjuoh.protege_mcp.external.ReuseProposal;
import io.github.hakjuoh.protege_mcp.external.ReuseProposalStore;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Explicit acceptance boundary for scoped external-term reuse proposals. */
final class ReuseAcceptanceTools {

    private static final long MINT_START_TIMEOUT_MS = 120_000L;

    private ReuseAcceptanceTools() {
    }

    static CallToolResult accept(ToolContext context, McpSyncServerExchange exchange,
            CallToolRequest request) {
        Map<String, Object> args = Tools.args(request);
        if (!Boolean.TRUE.equals(args.get("confirm"))) {
            throw prevented("confirmation_required",
                    "Reuse proposal acceptance requires confirm=true.", false);
        }
        String proposalId = Tools.reqString(args, "proposal_id");
        String expectedFingerprint = Tools.reqString(args, "proposal_fingerprint");
        if (!expectedFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw prevented("proposal_fingerprint_invalid",
                    "proposal_fingerprint must be sha256:<64 lowercase hex>.", false);
        }
        try (ReuseProposalStore.Claim claim = context.reuseProposals().claim(
                ExternalTermTools.scope(context, exchange), proposalId)) {
            ReuseProposal proposal = claim.proposal();
            requireFingerprint(proposal.proposalFingerprint(), expectedFingerprint);
            ReuseMintReceipt receipt = claim.mintReceipt();
            if (receipt != null) {
                return resumeMint(context, exchange, args, proposalId, proposal, receipt, claim);
            }
            MappingTools.ProposalState state = MappingTools.proposalState(context, exchange, args);
            requireState(proposal, state, true);
            requireMappingSetup(proposal, state, args);
            return switch (proposal.action()) {
                case REUSE_IRI -> acceptReuse(proposalId, proposal, claim);
                case ADD_MAPPING -> acceptMapping(
                        context, exchange, args, proposalId, proposal, claim);
                case MINT_LOCAL_WITH_MAPPING -> acceptMint(
                        context, exchange, args, proposalId, proposal, claim);
            };
        } catch (ProviderFailure failure) {
            throw providerFailure(failure);
        }
    }

    private static CallToolResult acceptReuse(String proposalId, ReuseProposal proposal,
            ReuseProposalStore.Claim claim) throws ProviderFailure {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("provider_id", proposal.providerResult().providerId());
        receipt.put("source_ontology", proposal.providerResult().sourceOntology());
        receipt.put("entity_iri", proposal.providerResult().entityIri());
        receipt.put("term_fingerprint", proposal.providerResult().termFingerprint());
        receipt.put("model_revision", RevisionTools.revisionJson(
                proposal.inputIdentity().modelRevision()));
        receipt.put("mapping_revision", proposal.inputIdentity().mappingRevision());
        receipt.put("policy_digest", proposal.inputIdentity().policyDigest());
        receipt.put("target_fingerprint",
                proposal.inputIdentity().targetIdentity().targetFingerprint());
        claim.complete();
        return base(proposalId, proposal, "accepted", false, false)
                .put("receipt", Collections.unmodifiableMap(receipt))
                .result();
    }

    private static CallToolResult acceptMapping(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args, String proposalId,
            ReuseProposal proposal, ReuseProposalStore.Claim claim) throws ProviderFailure {
        Confirmation confirmation = authorizeWrite(context,
                "accept mapping reuse proposal " + proposal.proposalFingerprint());
        context.writeLock().lock();
        try {
            confirmation.recheck(context);
            requireState(proposal, MappingTools.proposalState(context, exchange, args), true);
            Map<String, Object> mapping = MappingTools.acceptProposalMapping(
                    context, exchange, args, proposal, confirmation.mode(),
                    proposal.inputIdentity().modelRevision());
            consumeAfterMutation(claim);
            return base(proposalId, proposal, "accepted",
                    Boolean.TRUE.equals(mapping.get("committed")), confirmation.approved())
                    .put("mapping", mapping).result();
        } finally {
            context.writeLock().unlock();
        }
    }

    private static CallToolResult acceptMint(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args, String proposalId,
            ReuseProposal proposal, ReuseProposalStore.Claim claim) throws ProviderFailure {
        Confirmation confirmation = authorizeWrite(context, "mint and map reuse proposal "
                + proposal.proposalFingerprint());
        context.writeLock().lock();
        try {
            confirmation.recheck(context);
            requireState(proposal, MappingTools.proposalState(context, exchange, args), true);
            final ReuseMintReceipt receipt;
            try {
                receipt = mint(context, exchange, args, proposal, claim, confirmation);
            } catch (RuntimeException failure) {
                if (newProposalRequired(failure)) consumeAfterMutation(claim);
                throw failure;
            }
            return finishMintMapping(context, exchange, args, proposalId,
                    proposal, receipt, claim, confirmation, receipt.mintedRevision());
        } finally {
            context.writeLock().unlock();
        }
    }

    private static CallToolResult resumeMint(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args, String proposalId,
            ReuseProposal proposal, ReuseMintReceipt receipt,
            ReuseProposalStore.Claim claim) {
        if (proposal.action() != ReuseAction.MINT_LOCAL_WITH_MAPPING) {
            throw prevented("proposal_state_invalid",
                    "Only a mint proposal may carry a mint continuation.", false);
        }
        try {
            requireContinuationArguments(receipt, args);
        } catch (RuntimeException mismatch) {
            return partial(proposalId, proposal, receipt, mismatch, false);
        }
        final Confirmation confirmation;
        try {
            confirmation = authorizeWrite(context, "resume mint mapping proposal "
                    + proposal.proposalFingerprint());
        } catch (RuntimeException refusal) {
            return partial(proposalId, proposal, receipt, refusal, false);
        }
        context.writeLock().lock();
        try {
            try {
                confirmation.recheck(context);
                requireState(proposal,
                        MappingTools.proposalState(context, exchange, args), false);
                ModelRevision verifiedRevision = verifyMintedEntity(
                        context, proposal, receipt, claim);
                return finishMintMapping(context, exchange, args, proposalId,
                        proposal, receipt, claim, confirmation, verifiedRevision);
            } catch (RuntimeException failure) {
                return partial(proposalId, proposal, receipt, failure,
                        confirmation.approved());
            }
        } finally {
            context.writeLock().unlock();
        }
    }

    private static CallToolResult finishMintMapping(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args, String proposalId,
            ReuseProposal proposal, ReuseMintReceipt receipt,
            ReuseProposalStore.Claim claim, Confirmation confirmation,
            ModelRevision expectedModelRevision) {
        try {
            Map<String, Object> mapping = MappingTools.acceptProposalMapping(
                    context, exchange, args, proposal, confirmation.mode(),
                    expectedModelRevision);
            consumeAfterMutation(claim);
            return base(proposalId, proposal, "accepted", true, confirmation.approved())
                    .put("mint_receipt", receipt.toJson())
                    .put("mapping", mapping).result();
        } catch (RuntimeException mappingFailure) {
            return partial(proposalId, proposal, receipt, mappingFailure,
                    confirmation.approved());
        }
    }

    private static ReuseMintReceipt mint(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> args, ReuseProposal proposal, ReuseProposalStore.Claim claim,
            Confirmation confirmation) {
        if (!(proposal.operation() instanceof ReuseOperation.MintLocalWithMapping operation)) {
            throw prevented("proposal_state_invalid",
                    "Mint proposal operation is unavailable.", false);
        }
        String configuredPolicy = Tools.optString(args, "policy_path");
        return context.access().computeMutation(mm -> {
            try (ReuseProposalStore.MintLease mintLease = beginMint(claim)) {
                confirmation.recheck(context);
                ReuseProposal current = mintLease.proposal();
                if (!current.proposalFingerprint().equals(proposal.proposalFingerprint())) {
                    throw prevented("proposal_state_invalid",
                            "Reuse proposal changed before mint execution.", false);
                }
                ProjectPolicy policy = ChangeSetTools.effectivePolicy(mm, configuredPolicy);
                if (!policy.loaded() || !policy.valid() || policy.version() != 2
                        || !proposal.inputIdentity().policyDigest().equals(policy.digest())) {
                    throw prevented("proposal_input_changed",
                            "Project policy changed after the reuse proposal was issued.", true);
                }
                MappingTools.requireProposalTarget(
                        context, exchange, args, proposal, policy);
                ModelRevision base = proposal.inputIdentity().modelRevision();
                ModelRevision live = context.revisions().current(mm,
                        RevisionTools.digestImportLock(policy), policy.digest()).revision();
                if (!base.equals(live)) {
                    throw conflict(base, live);
                }
                OWLOntology active = mm.getActiveOntology();
                IRI iri = IRI.create(operation.localEntityIri());
                if (active.getImportsClosure().stream()
                        .anyMatch(ontology -> ontology.containsEntityInSignature(iri))) {
                    throw prevented("mint_entity_exists",
                            "The proposed local entity IRI already exists in the imports closure.",
                            false);
                }
                OWLEntity entity = entity(mm.getOWLDataFactory(), operation.entityType(), iri);
                MintOntologyBaseline ontologyBaseline = MintOntologyBaseline.capture(mm, active);
                List<OWLOntologyChange> changes = mintChanges(
                        mm.getOWLDataFactory(), active, entity, operation.labels());
                List<OWLAxiom> introduced = changes.stream()
                        .filter(AddAxiom.class::isInstance).map(AddAxiom.class::cast)
                        .map(AddAxiom::getAxiom)
                        .filter(axiom -> !active.containsAxiom(axiom)).toList();
                try {
                    mm.applyChanges(changes);
                } catch (RuntimeException outcomeUnknown) {
                    // Verify below. A listener may throw after every requested axiom was applied.
                }
                try {
                    requireMintAxioms(active, entity, operation.labels());
                    if (!ontologyBaseline.matchesExpected(mm, active, introduced)) {
                        throw new ToolArgException("mint_commit_unexpected_delta",
                                "Ontology mint produced changes outside the intended entity axioms.",
                                Map.of("outcome_unknown", true,
                                        "mutation_outcome_unknown", true), false);
                    }
                    ModelRevision minted = context.revisions().current(mm,
                            RevisionTools.digestImportLock(policy), policy.digest()).revision();
                    ReuseMintReceipt receipt = ReuseMintReceipt.create(
                            proposal.proposalFingerprint(), operation.localEntityIri(), base, minted,
                            Tools.optString(args, "mapping_set_id"), Tools.optString(args, "license"),
                            configuredPolicy);
                    try {
                        mintLease.recordMint(receipt);
                    } catch (ProviderFailure unavailable) {
                        throw providerFailure(unavailable);
                    }
                    return receipt;
                } catch (RuntimeException postApplyFailure) {
                    if (rollbackMintChanges(
                            context, mm, active, introduced, policy, live)) {
                        throw new ToolArgException("mint_commit_reverted",
                                "The ontology mint did not produce a durable receipt and was "
                                        + "reverted; create a fresh reuse proposal before retrying.",
                                Map.of("effects_prevented", true,
                                        "new_proposal_required", true), false);
                    }
                    throw new ToolArgException("mint_commit_incomplete",
                            "The ontology mint did not produce a durable receipt and its complete "
                                    + "rollback could not be verified. Review ontology changes, "
                                    + "remove the recorded entity axioms and any listener side "
                                    + "effects, then create a fresh reuse proposal.",
                            Map.of("outcome_unknown", true,
                                    "mutation_outcome_unknown", true,
                                    "manual_cleanup_required", true,
                                    "new_proposal_required", true,
                                    "entity_iri", operation.localEntityIri()), false);
                }
            }
        }, MINT_START_TIMEOUT_MS);
    }

    private static ReuseProposalStore.MintLease beginMint(ReuseProposalStore.Claim claim) {
        try {
            return claim.beginMint();
        } catch (ProviderFailure failure) {
            throw providerFailure(failure);
        }
    }

    private static ModelRevision verifyMintedEntity(ToolContext context, ReuseProposal proposal,
            ReuseMintReceipt receipt, ReuseProposalStore.Claim claim) {
        if (!(proposal.operation() instanceof ReuseOperation.MintLocalWithMapping operation)) {
            throw prevented("proposal_state_invalid", "Mint continuation is invalid.", false);
        }
        return context.access().compute(mm -> {
            requireActiveProposal(claim);
            ProjectPolicy policy = ChangeSetTools.effectivePolicy(
                    mm, receipt.configuredPolicyPath());
            if (!policy.loaded() || !policy.valid() || policy.version() != 2
                    || !proposal.inputIdentity().policyDigest().equals(policy.digest())) {
                throw prevented("proposal_input_changed",
                        "Project policy changed before mint continuation verification.", true);
            }
            OWLEntity entity = entity(mm.getOWLDataFactory(), operation.entityType(),
                    IRI.create(receipt.entityIri()));
            requireMintAxioms(mm.getActiveOntology(), entity, operation.labels());
            return context.revisions().current(mm, RevisionTools.digestImportLock(policy),
                    policy.digest()).revision();
        });
    }

    private static ReuseProposal requireActiveProposal(ReuseProposalStore.Claim claim) {
        try {
            return claim.proposal();
        } catch (ProviderFailure failure) {
            throw providerFailure(failure);
        }
    }

    private static OWLEntity entity(OWLDataFactory dataFactory,
            ReuseOperation.MintedEntityType type, IRI iri) {
        return switch (type) {
            case CLASS -> dataFactory.getOWLClass(iri);
            case OBJECT_PROPERTY -> dataFactory.getOWLObjectProperty(iri);
            case DATA_PROPERTY -> dataFactory.getOWLDataProperty(iri);
            case ANNOTATION_PROPERTY -> dataFactory.getOWLAnnotationProperty(iri);
            case NAMED_INDIVIDUAL -> dataFactory.getOWLNamedIndividual(iri);
            case DATATYPE -> dataFactory.getOWLDatatype(iri);
        };
    }

    private static List<OWLOntologyChange> mintChanges(OWLDataFactory dataFactory,
            OWLOntology ontology, OWLEntity entity,
            List<io.github.hakjuoh.protege_mcp.external.ProviderResult.LocalizedText> labels) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ontology, dataFactory.getOWLDeclarationAxiom(entity)));
        for (var label : labels) {
            changes.add(new AddAxiom(ontology, dataFactory.getOWLAnnotationAssertionAxiom(
                    dataFactory.getRDFSLabel(), entity.getIRI(),
                    dataFactory.getOWLLiteral(label.value(), label.language()))));
        }
        return List.copyOf(changes);
    }

    private static void requireMintAxioms(OWLOntology ontology, OWLEntity entity,
            List<io.github.hakjuoh.protege_mcp.external.ProviderResult.LocalizedText> labels) {
        OWLDataFactory dataFactory = ontology.getOWLOntologyManager().getOWLDataFactory();
        if (!ontology.containsAxiom(dataFactory.getOWLDeclarationAxiom(entity))) {
            throw new ToolArgException("mint_commit_incomplete",
                    "Minted entity declaration is not present after the ontology commit.",
                    Map.of("outcome_unknown", true,
                            "mutation_outcome_unknown", true,
                            "retry_requires_state_check", true), false);
        }
        for (var label : labels) {
            OWLAnnotationAssertionAxiom axiom = dataFactory.getOWLAnnotationAssertionAxiom(
                    dataFactory.getRDFSLabel(), entity.getIRI(),
                    dataFactory.getOWLLiteral(label.value(), label.language()));
            if (!ontology.containsAxiom(axiom)) {
                throw new ToolArgException("mint_commit_incomplete",
                        "A minted entity label is not present after the ontology commit.",
                        Map.of("outcome_unknown", true,
                                "mutation_outcome_unknown", true,
                                "retry_requires_state_check", true), false);
            }
        }
    }

    private static boolean rollbackMintChanges(ToolContext context,
            org.protege.editor.owl.model.OWLModelManager manager,
            OWLOntology ontology, List<OWLAxiom> introduced, ProjectPolicy policy,
            ModelRevision baseline) {
        try {
            List<OWLOntologyChange> removals = introduced.stream()
                    .filter(ontology::containsAxiom)
                    .map(axiom -> (OWLOntologyChange) new RemoveAxiom(ontology, axiom)).toList();
            if (!removals.isEmpty()) manager.applyChanges(removals);
            if (introduced.stream().anyMatch(ontology::containsAxiom)) return false;
            ModelRevision restored = context.revisions().current(manager,
                    RevisionTools.digestImportLock(policy), policy.digest()).revision();
            return baseline.semanticFingerprint().equals(restored.semanticFingerprint())
                    && baseline.documentFingerprint().equals(restored.documentFingerprint());
        } catch (RuntimeException cleanupOrVerificationFailure) {
            return false;
        }
    }

    private record MintOntologyBaseline(OWLOntologyID ontologyId, Set<OWLAxiom> axioms,
            Set<OWLAnnotation> annotations, Set<OWLImportsDeclaration> imports,
            IRI documentIri, String formatKey, Map<String, String> prefixes) {

        static MintOntologyBaseline capture(
                org.protege.editor.owl.model.OWLModelManager manager,
                OWLOntology ontology) {
            var ontologyManager = manager.getOWLOntologyManager();
            OWLDocumentFormat format = ontologyManager.getOntologyFormat(ontology);
            Map<String, String> prefixes = format != null && format.isPrefixOWLOntologyFormat()
                    ? Map.copyOf(format.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap())
                    : Map.of();
            return new MintOntologyBaseline(ontology.getOntologyID(),
                    Set.copyOf(ontology.getAxioms()), Set.copyOf(ontology.getAnnotations()),
                    Set.copyOf(ontology.getImportsDeclarations()),
                    ontologyManager.getOntologyDocumentIRI(ontology),
                    format == null ? "" : format.getKey(), prefixes);
        }

        boolean matchesExpected(org.protege.editor.owl.model.OWLModelManager manager,
                OWLOntology ontology, List<OWLAxiom> introduced) {
            if (manager.getActiveOntology() != ontology) return false;
            Set<OWLAxiom> expected = new HashSet<>(axioms);
            expected.addAll(introduced);
            var ontologyManager = manager.getOWLOntologyManager();
            OWLDocumentFormat format = ontologyManager.getOntologyFormat(ontology);
            Map<String, String> currentPrefixes = format != null
                    && format.isPrefixOWLOntologyFormat()
                    ? Map.copyOf(format.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap())
                    : Map.of();
            return expected.equals(ontology.getAxioms())
                    && ontologyId.equals(ontology.getOntologyID())
                    && annotations.equals(ontology.getAnnotations())
                    && imports.equals(ontology.getImportsDeclarations())
                    && Objects.equals(documentIri,
                            ontologyManager.getOntologyDocumentIRI(ontology))
                    && formatKey.equals(format == null ? "" : format.getKey())
                    && prefixes.equals(currentPrefixes);
        }
    }

    private static Confirmation authorizeWrite(ToolContext context, String summary) {
        if (context.controller() == null) {
            throw prevented("write_gate_unavailable",
                    "Live write authorization is unavailable.", true);
        }
        if (context.controller().isReadOnly()) {
            throw prevented("read_only", "Server is in read-only mode; writes are disabled.", false);
        }
        boolean mode = context.controller().isConfirmWrites();
        if (mode) {
            WriteConfirmer confirmer = context.confirmer();
            if (confirmer == null || !confirmer.confirm(summary)) {
                throw prevented("write_declined", "Write declined by the user.", false);
            }
        }
        Confirmation confirmation = new Confirmation(mode, mode);
        confirmation.recheck(context);
        return confirmation;
    }

    private static void requireState(ReuseProposal proposal,
            MappingTools.ProposalState state, boolean requireModelRevision) {
        boolean changed = !proposal.inputIdentity().policyDigest().equals(state.policy().digest())
                || !proposal.inputIdentity().mappingRevision().equals(state.mappingRevision())
                || !proposal.inputIdentity().targetIdentity().equals(state.targetIdentity())
                || requireModelRevision && !proposal.inputIdentity().modelRevision()
                        .equals(state.modelRevision());
        if (changed) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("effects_prevented", true);
            details.put("current_model_revision", RevisionTools.revisionJson(state.modelRevision()));
            details.put("current_mapping_revision", state.mappingRevision());
            details.put("current_policy_digest", state.policy().digest());
            details.put("current_target_fingerprint",
                    state.targetIdentity().targetFingerprint());
            details.put("current_mapping_exists", state.mappingExists());
            throw new ToolArgException("proposal_input_changed",
                    "Model, mapping, or policy state changed after the reuse proposal was issued.",
                    details, true);
        }
    }

    private static void requireMappingSetup(ReuseProposal proposal,
            MappingTools.ProposalState state, Map<String, Object> args) {
        if (proposal.action() == ReuseAction.REUSE_IRI || state.mappingExists()) return;
        String mappingSet = Tools.optString(args, "mapping_set_id");
        String license = Tools.optString(args, "license");
        if (!absoluteIri(mappingSet) || !absoluteIri(license)) {
            throw prevented("mapping_store_setup_required",
                    "An absent mapping store requires absolute mapping_set_id and license values "
                            + "before acceptance can begin.", false);
        }
    }

    private static void requireContinuationArguments(ReuseMintReceipt receipt,
            Map<String, Object> args) {
        if (!Objects.equals(receipt.configuredPolicyPath(),
                    Tools.optString(args, "policy_path"))
                || !Objects.equals(receipt.mappingSetId(),
                    Tools.optString(args, "mapping_set_id"))
                || !Objects.equals(receipt.mappingSetLicense(),
                    Tools.optString(args, "license"))) {
            throw prevented("continuation_input_changed",
                    "Mint continuation policy and mapping-store setup arguments must match the "
                            + "recorded ontology receipt.", false);
        }
    }

    private static boolean absoluteIri(String value) {
        if (value == null || value.length() > 4_096) return false;
        try {
            return URI.create(value).isAbsolute();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static ToolArgException conflict(ModelRevision expected, ModelRevision current) {
        return new ToolArgException("proposal_input_changed",
                "Ontology state changed after the reuse proposal was issued.",
                Map.of("effects_prevented", true,
                        "expected_model_revision", RevisionTools.revisionJson(expected),
                        "current_model_revision", RevisionTools.revisionJson(current)), true);
    }

    private static void requireFingerprint(String actual, String expected) {
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            throw prevented("proposal_fingerprint_mismatch",
                    "proposal_fingerprint does not match the scoped reuse proposal.", false);
        }
    }

    private static Tools.Json base(String proposalId, ReuseProposal proposal,
            String status, boolean committed, boolean interactiveConfirmation) {
        return Tools.json().put("proposal_id", proposalId)
                .put("proposal_fingerprint", proposal.proposalFingerprint())
                .put("status", status).put("action", proposal.action().wire())
                .put("committed", committed)
                .put("interactive_confirmation", interactiveConfirmation);
    }

    private static CallToolResult partial(String proposalId, ReuseProposal proposal,
            ReuseMintReceipt receipt, RuntimeException failure,
            boolean interactiveConfirmation) {
        return base(proposalId, proposal, "partial", true, interactiveConfirmation)
                .put("mint_receipt", receipt.toJson())
                .put("continuation", continuation(proposalId, proposal, receipt))
                .put("mapping_error", failureJson(failure)).result();
    }

    private static Map<String, Object> continuation(String proposalId,
            ReuseProposal proposal, ReuseMintReceipt receipt) {
        Map<String, Object> retryArguments = new LinkedHashMap<>();
        retryArguments.put("proposal_id", proposalId);
        retryArguments.put("proposal_fingerprint", proposal.proposalFingerprint());
        retryArguments.put("confirm", true);
        putIfNotNull(retryArguments, "policy_path", receipt.configuredPolicyPath());
        putIfNotNull(retryArguments, "mapping_set_id", receipt.mappingSetId());
        putIfNotNull(retryArguments, "license", receipt.mappingSetLicense());

        ReuseOperation.MintLocalWithMapping operation =
                (ReuseOperation.MintLocalWithMapping) proposal.operation();
        Map<String, Object> manualArguments = new LinkedHashMap<>();
        manualArguments.put("expected_mapping_revision",
                proposal.inputIdentity().mappingRevision());
        manualArguments.put("mapping", operation.mappingCells());
        manualArguments.put("confirm", true);
        putIfNotNull(manualArguments, "policy_path", receipt.configuredPolicyPath());
        putIfNotNull(manualArguments, "mapping_set_id", receipt.mappingSetId());
        putIfNotNull(manualArguments, "license", receipt.mappingSetLicense());

        Map<String, Object> retry = Map.of("tool", "accept_reuse_proposal",
                "arguments", Collections.unmodifiableMap(retryArguments));
        Map<String, Object> manual = Map.of("tool", "add_mapping",
                "arguments", Collections.unmodifiableMap(manualArguments));
        return Map.of("retry", retry, "manual_recovery", manual);
    }

    private static void putIfNotNull(Map<String, Object> target, String name, String value) {
        if (value != null) target.put(name, value);
    }

    private static Map<String, Object> failureJson(RuntimeException failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (failure instanceof ToolArgException typed) {
            result.put("code", typed.code());
            result.put("message", bounded(typed.getMessage()));
            result.put("retryable", typed.retryable());
            result.put("details", boundedDetails(typed.details()));
        } else {
            result.put("code", "mapping_step_failed");
            result.put("message", "The ontology mint committed, but the mapping step failed.");
            result.put("retryable", false);
            result.put("details", Map.of("retry_requires_state_check", true));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> boundedDetails(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values == null) return Map.of();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.matches("[a-z][a-z0-9_]{0,127}")) continue;
            Object value = entry.getValue();
            if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                result.put(key, value);
            } else if (value != null) {
                result.put(key, bounded(String.valueOf(value)));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static String bounded(String value) {
        String safe = value == null || value.isBlank() ? "Mapping step failed." : value;
        return safe.length() <= 2_048 ? safe : safe.substring(0, 2_048);
    }

    private static void consumeAfterMutation(ReuseProposalStore.Claim claim) {
        try {
            claim.complete();
        } catch (ProviderFailure unavailableAfterCommit) {
            // The mutation is already durable. Expiry or shutdown also removes the proposal,
            // so failing the completed request would invite an unsafe duplicate retry.
        }
    }

    private static boolean newProposalRequired(RuntimeException failure) {
        return failure instanceof ToolArgException typed
                && Boolean.TRUE.equals(typed.details().get("new_proposal_required"));
    }

    private static ToolArgException prevented(String code, String message, boolean retryable) {
        return new ToolArgException(code, message, Map.of("effects_prevented", true), retryable);
    }

    private static ToolArgException providerFailure(ProviderFailure failure) {
        return new ToolArgException(failure.code(), failure.getMessage(), failure.details(),
                failure.retryable());
    }

    private record Confirmation(boolean mode, boolean approved) {
        void recheck(ToolContext context) {
            if (context.controller() == null || context.controller().isReadOnly()) {
                throw prevented("read_only", "Server changed to read-only mode.", true);
            }
            if (mode != context.controller().isConfirmWrites()) {
                throw prevented("confirmation_state_changed",
                        "The live write-confirmation preference changed.", true);
            }
        }
    }
}
