package io.github.hakjuoh.protege_mcp.reasoner;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLArgument;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLDataRangeAtom;
import org.semanticweb.owlapi.model.SWRLDifferentIndividualsAtom;
import org.semanticweb.owlapi.model.SWRLIndividualArgument;
import org.semanticweb.owlapi.model.SWRLLiteralArgument;
import org.semanticweb.owlapi.model.SWRLObjectPropertyAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLSameIndividualAtom;
import org.semanticweb.owlapi.model.SWRLVariable;

/** Bounded, non-executing SWRL validation against an exact reasoner profile. */
public final class RuleValidationService {

    public static final int MAX_PAGE = 10;
    public static final int MAX_SOURCE_ONTOLOGIES = 128;
    public static final int MAX_SOURCE_IDENTIFIER_CHARACTERS = 4_096;
    public static final int MAX_RULE_OCCURRENCES = 2_000;
    public static final int MAX_UNIQUE_RULES = 2_000;
    public static final int MAX_ATOMS_PER_RULE = 512;
    public static final int MAX_TOTAL_ATOMS = 20_000;
    public static final int MAX_TOTAL_ARGUMENTS = 100_000;
    public static final int MAX_RULE_ANNOTATIONS = 256;
    public static final int MAX_CANONICAL_RULE_CHARACTERS = 262_144;
    public static final int MAX_CANONICAL_OBJECT_NODES = 4_096;
    public static final int MAX_CANONICAL_OBJECT_DEPTH = 128;
    public static final int MAX_CANONICAL_UTF8_BYTES = 2_000_000;
    public static final long MAX_CAPTURE_MILLIS = 10_000L;
    public static final int MAX_REPORTED_ATOMS_PER_RULE = 32;
    public static final int MAX_REPORTED_ARGUMENTS_PER_ATOM = 8;
    public static final int MAX_REPORTED_VARIABLES = 8;
    public static final int MAX_REPORTED_SOURCES = 32;
    public static final int MAX_FINDINGS_PER_RULE = 64;
    private static final int MAX_DISPLAY_CHARACTERS = 512;

    private RuleValidationService() {
    }

    /** Fully detached immutable corpus; no OWLAPI object leaves the capture boundary. */
    public static final class CapturedCorpus {
        private final List<CapturedRule> rules;
        private final String corpusFingerprint;
        private final String fingerprintStability;
        private final List<String> fingerprintWarnings;
        private final int sourceOntologyCount;
        private final int ruleOccurrenceCount;
        private final int atomCount;
        private final int argumentCount;
        private final int canonicalUtf8Bytes;

        private CapturedCorpus(List<CapturedRule> rules, String corpusFingerprint,
                String fingerprintStability, List<String> fingerprintWarnings,
                int sourceOntologyCount, int ruleOccurrenceCount, int atomCount,
                int argumentCount, int canonicalUtf8Bytes) {
            this.rules = List.copyOf(rules);
            this.corpusFingerprint = corpusFingerprint;
            this.fingerprintStability = fingerprintStability;
            this.fingerprintWarnings = List.copyOf(fingerprintWarnings);
            this.sourceOntologyCount = sourceOntologyCount;
            this.ruleOccurrenceCount = ruleOccurrenceCount;
            this.atomCount = atomCount;
            this.argumentCount = argumentCount;
            this.canonicalUtf8Bytes = canonicalUtf8Bytes;
        }

        public int totalRules() {
            return rules.size();
        }

        public String corpusFingerprint() {
            return corpusFingerprint;
        }
    }

    /** Fast coherent model snapshot; contained OWLAPI axioms are immutable value objects. */
    public static final class RuleSnapshot {
        private final List<SnapshotSource> sources;
        private final int ruleOccurrenceCount;
        private final String sourceScope;

        private RuleSnapshot(List<SnapshotSource> sources, int ruleOccurrenceCount,
                String sourceScope) {
            this.sources = List.copyOf(sources);
            this.ruleOccurrenceCount = ruleOccurrenceCount;
            this.sourceScope = sourceScope;
        }
    }

    /** A hard preflight or capture budget was exceeded before a partial report was returned. */
    public static final class BudgetExceededException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String budget;
        private final long maximum;
        private final long observed;

        BudgetExceededException(String budget, long maximum, long observed) {
            super("rule validation budget '" + budget + "' exceeded: maximum=" + maximum
                    + ", observed=" + observed);
            this.budget = budget;
            this.maximum = maximum;
            this.observed = observed;
        }

        public String budget() {
            return budget;
        }

        public long maximum() {
            return maximum;
        }

        public long observed() {
            return observed;
        }
    }

    /** The caller attempted to continue a page from a different corpus/configuration snapshot. */
    public static final class SnapshotMismatchException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        SnapshotMismatchException() {
            super("snapshot_fingerprint does not match the current selected sources, rule "
                    + "corpus, or reasoner capability profile");
        }
    }

    /** A continuation page must name the exact snapshot returned by the preceding page. */
    public static final class SnapshotRequiredException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        SnapshotRequiredException() {
            super("snapshot_fingerprint is required when offset is greater than zero");
        }
    }

    /** Copy ontology identifiers and immutable rule axioms under small count bounds. */
    public static RuleSnapshot snapshot(Collection<OWLOntology> ontologies) {
        return snapshot(ontologies, "explicit_sources");
    }

    /** Snapshot the active ontology or its imports closure and bind that choice into pagination. */
    public static RuleSnapshot snapshot(Collection<OWLOntology> ontologies,
            boolean includeImports) {
        return snapshot(ontologies, includeImports ? "imports_closure" : "active_ontology");
    }

    /** Traverse a live imports closure without first materializing an unbounded closure set. */
    public static Set<OWLOntology> boundedImportsClosure(OWLOntology active) {
        if (active == null) throw new IllegalArgumentException("active ontology is required");
        Set<OWLOntology> closure = new LinkedHashSet<>();
        ArrayDeque<OWLOntology> pending = new ArrayDeque<>();
        closure.add(active);
        pending.add(active);
        while (!pending.isEmpty()) {
            OWLOntology ontology = pending.removeFirst();
            for (OWLOntology imported : ontology.getDirectImports()) {
                if (closure.add(imported)) {
                    requireWithin("source_ontologies", closure.size(), MAX_SOURCE_ONTOLOGIES);
                    pending.addLast(imported);
                }
            }
        }
        return Set.copyOf(closure);
    }

    private static RuleSnapshot snapshot(Collection<OWLOntology> ontologies,
            String sourceScope) {
        if (ontologies == null) throw new IllegalArgumentException("ontologies are required");
        List<SnapshotSource> sources = new ArrayList<>();
        long occurrences = 0;
        for (OWLOntology ontology : ontologies) {
            if (ontology == null) continue;
            requireWithin("source_ontologies", (long) sources.size() + 1,
                    MAX_SOURCE_ONTOLOGIES);
            int ontologyOccurrences = ontology.getAxiomCount(AxiomType.SWRL_RULE);
            requireWithin("rule_occurrences", occurrences + ontologyOccurrences,
                    MAX_RULE_OCCURRENCES);
            List<SWRLRule> rules = List.copyOf(ontology.getAxioms(AxiomType.SWRL_RULE));
            occurrences += rules.size();
            requireWithin("rule_occurrences", occurrences, MAX_RULE_OCCURRENCES);
            SourceCoordinate coordinate = sourceCoordinate(ontology);
            sources.add(new SnapshotSource(coordinate.value, coordinate.stable, rules));
        }
        sources.sort(Comparator.comparing(source -> source.coordinate));
        return new RuleSnapshot(sources, Math.toIntExact(occurrences), sourceScope);
    }

    /** Snapshot and fully detach every rule under global time/size/count bounds. */
    public static CapturedCorpus capture(Collection<OWLOntology> ontologies) {
        return capture(snapshot(ontologies));
    }

    /** Canonicalize an immutable snapshot off the Protege model thread. */
    public static CapturedCorpus capture(RuleSnapshot snapshot) {
        return capture(snapshot, CaptureLimits.production(), System::nanoTime);
    }

    static CapturedCorpus capture(RuleSnapshot snapshot, CaptureLimits limits,
            LongSupplier nanoTime) {
        if (snapshot == null) throw new IllegalArgumentException("rule snapshot is required");
        CaptureBudget budget = new CaptureBudget(limits, nanoTime);
        final OWLOntology renderContext;
        try {
            renderContext = OWLManager.createOWLOntologyManager().createOntology();
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException unavailable) {
            throw new IllegalStateException("cannot create detached canonical rendering context",
                    unavailable);
        }

        Map<SWRLRule, SourceGroup> grouped = new LinkedHashMap<>();
        boolean stable = true;
        List<String> selectedSources = new ArrayList<>();
        for (SnapshotSource source : snapshot.sources) {
            budget.checkTime();
            budget.addCanonicalString(source.coordinate);
            selectedSources.add(source.coordinate);
            stable &= source.stable;
            for (SWRLRule rule : source.rules) {
                SourceGroup group = grouped.computeIfAbsent(rule,
                        ignored -> new SourceGroup());
                group.sources.add(source.coordinate);
                requireWithin("unique_rules", grouped.size(), limits.uniqueRules);
            }
        }

        List<CapturedRule> captured = new ArrayList<>();
        for (Map.Entry<SWRLRule, SourceGroup> entry : grouped.entrySet()) {
            budget.checkTime();
            SWRLRule rule = entry.getKey();
            requireWithin("atoms_per_rule", (long) rule.getBody().size() + rule.getHead().size(),
                    limits.atomsPerRule);
            boolean ruleStable = rule.getAnonymousIndividuals().isEmpty();
            stable &= ruleStable;
            List<CapturedAtom> body = captureAtoms(
                    rule.getBody(), "body", renderContext, budget);
            List<CapturedAtom> head = captureAtoms(
                    rule.getHead(), "head", renderContext, budget);
            List<String> annotations = captureAnnotations(rule.getAnnotations(), renderContext,
                    budget);
            String canonical = canonicalRule(body, head, annotations, limits);
            budget.addCanonicalString(canonical);
            List<CapturedAtom> all = new ArrayList<>(body);
            all.addAll(head);
            Set<String> variables = variables(all);
            Set<String> bodyBound = new TreeSet<>();
            body.stream().filter(atom -> !"built_in".equals(atom.type))
                    .forEach(atom -> bodyBound.addAll(atom.variables));
            Set<String> missing = new TreeSet<>(variables);
            missing.removeAll(bodyBound);
            List<String> coordinates = List.copyOf(entry.getValue().sources);
            captured.add(new CapturedRule(fingerprint(canonical), coordinates,
                    coordinates.size(), ruleStable ? "cross_restart" : "session_only",
                    body, head, variables.size(), List.copyOf(missing)));
        }
        captured.sort(Comparator.comparing(rule -> rule.ruleId));
        FingerprintBuilder corpus = new FingerprintBuilder();
        corpus.add("rule-corpus-v2");
        corpus.add(snapshot.sourceScope);
        selectedSources.forEach(corpus::add);
        corpus.add(Integer.toString(snapshot.ruleOccurrenceCount));
        for (CapturedRule rule : captured) {
            corpus.add(rule.ruleId);
            rule.sourceOntologies.forEach(corpus::add);
        }
        String corpusFingerprint = corpus.finish();
        List<String> warnings = stable ? List.of() : List.of(
                "Anonymous ontology or individual identifiers make this a same-session token only.");
        return new CapturedCorpus(captured, corpusFingerprint,
                stable ? "cross_restart" : "session_only", warnings, snapshot.sources.size(),
                snapshot.ruleOccurrenceCount, budget.atoms, budget.arguments,
                budget.canonicalBytes);
    }

    public static Map<String, Object> validate(CapturedCorpus corpus,
            ReasonerCapabilityReport capabilities, int offset, int limit) {
        return validate(corpus, capabilities, offset, limit, null);
    }

    /** Validate the bounded corpus and optionally reject pagination against a changed snapshot. */
    public static Map<String, Object> validate(CapturedCorpus corpus,
            ReasonerCapabilityReport capabilities, int offset, int limit,
            String expectedSnapshotFingerprint) {
        if (corpus == null || capabilities == null) {
            throw new IllegalArgumentException("captured corpus and capabilities are required");
        }
        if (offset < 0 || offset > MAX_UNIQUE_RULES) {
            throw new IllegalArgumentException("offset must be between 0 and " + MAX_UNIQUE_RULES);
        }
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
        }
        if (offset > 0 && expectedSnapshotFingerprint == null) {
            throw new SnapshotRequiredException();
        }
        FingerprintBuilder snapshot = new FingerprintBuilder();
        snapshot.add("rule-validation-snapshot-v2");
        snapshot.add(corpus.corpusFingerprint);
        snapshot.add(capabilities.capabilityDigest());
        String snapshotFingerprint = snapshot.finish();
        if (expectedSnapshotFingerprint != null
                && !expectedSnapshotFingerprint.equals(snapshotFingerprint)) {
            throw new SnapshotMismatchException();
        }

        int start = Math.min(offset, corpus.rules.size());
        int end = Math.min(start + limit, corpus.rules.size());
        List<Map<String, Object>> page = new ArrayList<>();
        int supported = 0;
        int unsupported = 0;
        int unknown = 0;
        int untested = 0;
        boolean coverageComplete = true;
        List<Map<String, Object>> incompatibleSummaries = new ArrayList<>();
        for (int index = 0; index < corpus.rules.size(); index++) {
            ValidatedRule validated = validateRule(corpus.rules.get(index), capabilities);
            coverageComplete &= validated.coverageComplete;
            switch (validated.status) {
                case SUPPORTED -> supported++;
                case UNSUPPORTED -> unsupported++;
                case UNKNOWN -> unknown++;
                case UNTESTED -> untested++;
            }
            if (validated.status != CapabilityStatus.SUPPORTED) {
                incompatibleSummaries.add(validated.summaryMap());
            }
            if (index >= start && index < end) page.add(validated.toMap());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vocabulary_version", ReasonerCapabilityReport.VOCABULARY_VERSION);
        out.put("profile_status", capabilities.profileStatus());
        out.put("reasoner_identity", capabilities.identity().toMap());
        out.put("snapshot_fingerprint", snapshotFingerprint);
        out.put("fingerprint_stability", corpus.fingerprintStability);
        out.put("fingerprint_warnings", corpus.fingerprintWarnings);
        out.put("executed_rules", false);
        out.put("parsed_every_atom", true);
        out.put("compatible", unsupported == 0 && unknown == 0 && untested == 0);
        out.put("coverage_complete", coverageComplete);
        out.put("total_rules", corpus.rules.size());
        out.put("supported_rules", supported);
        out.put("unsupported_rules", unsupported);
        out.put("unknown_rules", unknown);
        out.put("untested_rules", untested);
        out.put("incompatible_rule_count", incompatibleSummaries.size());
        out.put("incompatible_rule_summaries", incompatibleSummaries);
        out.put("source_ontology_count", corpus.sourceOntologyCount);
        out.put("rule_occurrence_count", corpus.ruleOccurrenceCount);
        out.put("parsed_atom_count", corpus.atomCount);
        out.put("parsed_argument_count", corpus.argumentCount);
        out.put("canonical_utf8_bytes", corpus.canonicalUtf8Bytes);
        out.put("capture_limits", captureLimits());
        out.put("offset", start);
        out.put("returned", page.size());
        if (end < corpus.rules.size()) out.put("next_offset", end);
        out.put("rules", page);
        return out;
    }

    private static ValidatedRule validateRule(CapturedRule captured,
            ReasonerCapabilityReport capabilities) {
        List<CapturedAtom> all = new ArrayList<>(captured.body);
        all.addAll(captured.head);
        List<CapabilityStatus> statuses = new ArrayList<>();
        statuses.add(capabilities.ruleStatus("swrl_rules"));
        statuses.add(capabilities.ruleStatus("dl_safe_rules"));
        List<ValidatedAtom> atoms = new ArrayList<>();
        for (CapturedAtom atom : all) {
            CapabilityStatus status = "built_in".equals(atom.type)
                    ? capabilities.builtinStatus(atom.predicateIdentity)
                    : capabilities.atomStatus(atom.type);
            statuses.add(status);
            atoms.add(new ValidatedAtom(atom, status));
        }
        boolean bodyVariableSafe = captured.missingBodyVariables.isEmpty();
        if (!bodyVariableSafe) statuses.add(CapabilityStatus.UNSUPPORTED);
        CapabilityStatus overall = CapabilityStatus.aggregate(statuses);
        boolean coverageComplete = statuses.stream().noneMatch(status ->
                status == CapabilityStatus.UNKNOWN || status == CapabilityStatus.UNTESTED);

        List<Map<String, Object>> findings = new ArrayList<>();
        int findingCount = 0;
        CapabilityStatus ruleCapability = capabilities.ruleStatus("swrl_rules");
        CapabilityStatus dlSafetyCapability = capabilities.ruleStatus("dl_safe_rules");
        if (ruleCapability != CapabilityStatus.SUPPORTED) {
            findingCount++;
            addFinding(findings, capabilityFinding("rule_capability", ruleCapability,
                    "The selected profile's SWRL rule execution capability is "));
        }
        if (dlSafetyCapability != CapabilityStatus.SUPPORTED) {
            findingCount++;
            addFinding(findings, capabilityFinding("dl_safety", dlSafetyCapability,
                    "The selected profile's DL-safe rule capability is "));
        }
        if (!bodyVariableSafe) {
            findingCount++;
            addFinding(findings, finding("error", "body_variable_unbound",
                    "Every rule variable must occur in a non-built-in body atom; missing_count="
                            + captured.missingBodyVariables.size()));
        }
        for (ValidatedAtom atom : atoms) {
            if (atom.status != CapabilityStatus.SUPPORTED) {
                findingCount++;
                addFinding(findings, finding(
                        atom.status == CapabilityStatus.UNSUPPORTED ? "error" : "warning",
                        atom.status == CapabilityStatus.UNSUPPORTED
                                ? "atom_unsupported" : "atom_coverage_incomplete",
                        atom.value.position + " atom " + atom.value.type + " ("
                                + atom.value.predicateDisplay + ") is "
                                + atom.status.value() + "."));
            }
        }
        List<Map<String, Object>> atomMaps = atoms.stream()
                .limit(MAX_REPORTED_ATOMS_PER_RULE).map(ValidatedAtom::toMap).toList();
        List<String> incompatiblePredicates = atoms.stream()
                .filter(atom -> atom.status != CapabilityStatus.SUPPORTED)
                .map(atom -> summaryCompact(atom.value.predicateIdentity))
                .distinct().limit(2).toList();
        return new ValidatedRule(captured, overall, coverageComplete,
                capabilities.ruleStatus("dl_safe_rules"), bodyVariableSafe,
                all.size() > atomMaps.size(), findingCount,
                findingCount > findings.size(), atomMaps, findings,
                incompatiblePredicates);
    }

    private static List<CapturedAtom> captureAtoms(Collection<SWRLAtom> source,
            String position, OWLOntology context, CaptureBudget budget) {
        List<RenderedAtom> rendered = new ArrayList<>();
        for (SWRLAtom atom : source) {
            budget.checkTime();
            rendered.add(new RenderedAtom(atom,
                    boundedRender(context, atom, budget)));
        }
        rendered.sort(Comparator.comparing(item -> item.canonical));
        List<CapturedAtom> out = new ArrayList<>();
        for (int index = 0; index < rendered.size(); index++) {
            SWRLAtom atom = rendered.get(index).atom;
            budget.addAtom();
            String type = atomType(atom);
            String predicateIdentity = predicate(atom, context, budget);
            List<String> arguments = new ArrayList<>();
            Set<String> variables = new TreeSet<>();
            int argumentCount = 0;
            for (SWRLArgument argument : atom.getAllArguments()) {
                budget.addArgument();
                if (argumentCount < MAX_REPORTED_ARGUMENTS_PER_ATOM) {
                    arguments.add(argumentDisplay(argument));
                }
                argumentCount++;
                if (argument instanceof SWRLVariable variable) {
                    variables.add(variable.getIRI().toString());
                }
            }
            out.add(new CapturedAtom(position, index, type, predicateIdentity,
                    compact(predicateIdentity), List.copyOf(arguments), argumentCount,
                    List.copyOf(variables), rendered.get(index).canonical));
        }
        return List.copyOf(out);
    }

    private static List<String> captureAnnotations(Collection<OWLAnnotation> source,
            OWLOntology context, CaptureBudget budget) {
        requireWithin("rule_annotations", source.size(), MAX_RULE_ANNOTATIONS);
        List<String> out = new ArrayList<>();
        for (OWLAnnotation annotation : source) {
            out.add(boundedRender(context, annotation, budget));
        }
        out.sort(String::compareTo);
        return List.copyOf(out);
    }

    private static String canonicalRule(List<CapturedAtom> body, List<CapturedAtom> head,
            List<String> annotations, CaptureLimits limits) {
        List<String> bodyTokens = body.stream().map(CapturedAtom::canonical).toList();
        List<String> headTokens = head.stream().map(CapturedAtom::canonical).toList();
        long characters = tokenCharacters(bodyTokens) + tokenCharacters(headTokens)
                + tokenCharacters(annotations) + 32L;
        requireWithin("canonical_object_characters", characters,
                limits.canonicalObjectCharacters);
        return "body=" + tokens(bodyTokens) + "|head=" + tokens(headTokens)
                + "|annotations=" + tokens(annotations);
    }

    private static long tokenCharacters(List<String> values) {
        long count = 0;
        for (String value : values) {
            count += Integer.toString(value.length()).length() + 1L + value.length();
        }
        return count;
    }

    private static String atomType(SWRLAtom atom) {
        if (atom instanceof SWRLClassAtom) return "class";
        if (atom instanceof SWRLObjectPropertyAtom) return "object_property";
        if (atom instanceof SWRLDataPropertyAtom) return "data_property";
        if (atom instanceof SWRLDataRangeAtom) return "data_range";
        if (atom instanceof SWRLSameIndividualAtom) return "same_individual";
        if (atom instanceof SWRLDifferentIndividualsAtom) return "different_individuals";
        if (atom instanceof SWRLBuiltInAtom) return "built_in";
        return "unknown";
    }

    private static String predicate(SWRLAtom atom, OWLOntology context, CaptureBudget budget) {
        if (atom instanceof SWRLBuiltInAtom builtIn) return builtIn.getPredicate().toString();
        if (atom instanceof SWRLClassAtom value) {
            return boundedRender(context, value.getPredicate(), budget);
        }
        if (atom instanceof SWRLObjectPropertyAtom value) {
            return boundedRender(context, value.getPredicate(), budget);
        }
        if (atom instanceof SWRLDataPropertyAtom value) {
            return boundedRender(context, value.getPredicate(), budget);
        }
        if (atom instanceof SWRLDataRangeAtom value) {
            return boundedRender(context, value.getPredicate(), budget);
        }
        return atom.getClass().getName();
    }

    private static String argumentDisplay(SWRLArgument argument) {
        if (argument instanceof SWRLVariable variable) {
            return compact("variable:" + variable.getIRI());
        }
        if (argument instanceof SWRLIndividualArgument individual) {
            if (individual.getIndividual().isNamed()) {
                return compact("individual:"
                        + individual.getIndividual().asOWLNamedIndividual().getIRI());
            }
            OWLAnonymousIndividual anonymous = individual.getIndividual().asOWLAnonymousIndividual();
            return compact("anonymous:" + anonymous.getID().getID());
        }
        if (argument instanceof SWRLLiteralArgument literal) {
            OWLLiteral value = literal.getLiteral();
            String lexicalDigest = fingerprint(value.getLiteral());
            return compact("literal:" + lexicalDigest + ":length=" + value.getLiteral().length()
                    + ":lang=" + value.getLang() + ":datatype="
                    + value.getDatatype().getIRI());
        }
        return compact("unknown:" + argument.getClass().getName());
    }

    private static Set<String> variables(List<CapturedAtom> atoms) {
        Set<String> variables = new TreeSet<>();
        atoms.forEach(atom -> variables.addAll(atom.variables));
        return variables;
    }

    private static SourceCoordinate sourceCoordinate(OWLOntology ontology) {
        IRI ontologyIri = ontology.getOntologyID().getOntologyIRI().orNull();
        IRI versionIri = ontology.getOntologyID().getVersionIRI().orNull();
        if (ontologyIri == null) {
            String anonymous = ontology.getOntologyID().toString();
            requireWithin("source_identifier_characters", anonymous.length(),
                    MAX_SOURCE_IDENTIFIER_CHARACTERS);
            return new SourceCoordinate(compact("anonymous:" + anonymous), false);
        }
        String ontologyValue = ontologyIri.toString();
        String versionValue = versionIri == null ? "none" : versionIri.toString();
        requireWithin("source_identifier_characters", ontologyValue.length(),
                MAX_SOURCE_IDENTIFIER_CHARACTERS);
        requireWithin("source_identifier_characters", versionValue.length(),
                MAX_SOURCE_IDENTIFIER_CHARACTERS);
        return new SourceCoordinate(compact("ontology=" + ontologyValue + "|version="
                + versionValue), true);
    }

    private static Map<String, Object> captureLimits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("source_ontologies", MAX_SOURCE_ONTOLOGIES);
        limits.put("source_identifier_characters", MAX_SOURCE_IDENTIFIER_CHARACTERS);
        limits.put("rule_occurrences", MAX_RULE_OCCURRENCES);
        limits.put("unique_rules", MAX_UNIQUE_RULES);
        limits.put("atoms_per_rule", MAX_ATOMS_PER_RULE);
        limits.put("total_atoms", MAX_TOTAL_ATOMS);
        limits.put("total_arguments", MAX_TOTAL_ARGUMENTS);
        limits.put("rule_annotations", MAX_RULE_ANNOTATIONS);
        limits.put("canonical_object_characters", MAX_CANONICAL_RULE_CHARACTERS);
        limits.put("canonical_object_nodes", MAX_CANONICAL_OBJECT_NODES);
        limits.put("canonical_object_depth", MAX_CANONICAL_OBJECT_DEPTH);
        limits.put("canonical_utf8_bytes", MAX_CANONICAL_UTF8_BYTES);
        limits.put("capture_millis", MAX_CAPTURE_MILLIS);
        return Map.copyOf(limits);
    }

    private static String boundedRender(OWLOntology context,
            org.semanticweb.owlapi.model.OWLObject object, CaptureBudget budget) {
        try {
            String rendered = CanonicalOwlRenderer.render(context, object,
                    budget.limits.canonicalObjectCharacters,
                    budget.limits.canonicalObjectNodes,
                    budget.limits.canonicalObjectDepth, budget::checkTime);
            budget.addCanonicalString(rendered);
            return rendered;
        } catch (CanonicalOwlRenderer.RenderLimitException exceeded) {
            throw new BudgetExceededException(exceeded.budget(), exceeded.maximum(),
                    exceeded.observed());
        }
    }

    private static Map<String, Object> finding(String severity, String code, String message) {
        return Map.of("severity", severity, "code", code, "message", compact(message));
    }

    private static Map<String, Object> capabilityFinding(String prefix,
            CapabilityStatus status, String message) {
        boolean unsupported = status == CapabilityStatus.UNSUPPORTED;
        return finding(unsupported ? "error" : "warning",
                prefix + (unsupported ? "_unsupported" : "_coverage_incomplete"),
                message + status.value() + ".");
    }

    private static void addFinding(List<Map<String, Object>> findings,
            Map<String, Object> finding) {
        if (findings.size() < MAX_FINDINGS_PER_RULE) findings.add(finding);
    }

    private static String compact(String value) {
        if (value.length() <= MAX_DISPLAY_CHARACTERS) return value;
        return "sha256:" + fingerprint(value).substring("sha256:".length())
                + ":characters=" + value.length();
    }

    private static String summaryCompact(String value) {
        if (value.length() <= 128) return value;
        return fingerprint(value) + ":characters=" + value.length();
    }

    private static String tokens(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) out.append(value.length()).append(':').append(value);
        return out.toString();
    }

    private static String fingerprint(String value) {
        return new FingerprintBuilder().add(value).finish();
    }

    private static int utf8Length(String value) {
        return Math.toIntExact(utf8LengthLong(value));
    }

    private static long utf8LengthLong(String value) {
        long bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2
                    : codePoint <= 0xffff ? 3 : 4;
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static void updateUtf8(MessageDigest digest, String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint <= 0x7f) {
                digest.update((byte) codePoint);
            } else if (codePoint <= 0x7ff) {
                digest.update((byte) (0xc0 | codePoint >>> 6));
                digest.update((byte) (0x80 | codePoint & 0x3f));
            } else if (codePoint <= 0xffff) {
                digest.update((byte) (0xe0 | codePoint >>> 12));
                digest.update((byte) (0x80 | codePoint >>> 6 & 0x3f));
                digest.update((byte) (0x80 | codePoint & 0x3f));
            } else {
                digest.update((byte) (0xf0 | codePoint >>> 18));
                digest.update((byte) (0x80 | codePoint >>> 12 & 0x3f));
                digest.update((byte) (0x80 | codePoint >>> 6 & 0x3f));
                digest.update((byte) (0x80 | codePoint & 0x3f));
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static final class FingerprintBuilder {
        private final MessageDigest digest;

        FingerprintBuilder() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        }

        FingerprintBuilder add(String value) {
            long bytes = utf8LengthLong(value);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes).array());
            updateUtf8(digest, value);
            return this;
        }

        String finish() {
        StringBuilder out = new StringBuilder("sha256:");
        for (byte item : digest.digest()) out.append(String.format("%02x", item & 0xff));
        return out.toString();
        }
    }

    private static void requireWithin(String budget, long observed, long maximum) {
        if (observed > maximum) throw new BudgetExceededException(budget, maximum, observed);
    }

    static record CaptureLimits(int uniqueRules, int atomsPerRule, int totalAtoms,
            int totalArguments, int canonicalObjectCharacters, int canonicalObjectNodes,
            int canonicalObjectDepth, int canonicalUtf8Bytes, long captureMillis) {

        CaptureLimits {
            if (uniqueRules < 1 || atomsPerRule < 1 || totalAtoms < 1
                    || totalArguments < 1 || canonicalObjectCharacters < 1
                    || canonicalObjectNodes < 1 || canonicalObjectDepth < 1
                    || canonicalUtf8Bytes < 1 || captureMillis < 0) {
                throw new IllegalArgumentException("all capture limits must be positive");
            }
        }

        static CaptureLimits production() {
            return new CaptureLimits(MAX_UNIQUE_RULES, MAX_ATOMS_PER_RULE, MAX_TOTAL_ATOMS,
                    MAX_TOTAL_ARGUMENTS, MAX_CANONICAL_RULE_CHARACTERS,
                    MAX_CANONICAL_OBJECT_NODES, MAX_CANONICAL_OBJECT_DEPTH,
                    MAX_CANONICAL_UTF8_BYTES, MAX_CAPTURE_MILLIS);
        }
    }

    private static final class CaptureBudget {
        private final CaptureLimits limits;
        private final LongSupplier nanoTime;
        private final long startedNanos;
        private final long deadlineNanos;
        private int atoms;
        private int arguments;
        private int canonicalBytes;

        CaptureBudget(CaptureLimits limits, LongSupplier nanoTime) {
            this.limits = limits;
            this.nanoTime = nanoTime;
            this.startedNanos = nanoTime.getAsLong();
            long duration = TimeUnit.MILLISECONDS.toNanos(limits.captureMillis);
            this.deadlineNanos = startedNanos > Long.MAX_VALUE - duration
                    ? Long.MAX_VALUE : startedNanos + duration;
        }

        void addAtom() {
            requireWithin("total_atoms", ++atoms, limits.totalAtoms);
            checkTime();
        }

        void addArgument() {
            requireWithin("total_arguments", ++arguments, limits.totalArguments);
            checkTime();
        }

        void addCanonicalString(String value) {
            int added = utf8Length(value);
            long observed = (long) canonicalBytes + added;
            requireWithin("canonical_utf8_bytes", observed, limits.canonicalUtf8Bytes);
            canonicalBytes = Math.toIntExact(observed);
            checkTime();
        }

        void checkTime() {
            long now = nanoTime.getAsLong();
            if (now > deadlineNanos) {
                long elapsedNanos = Math.max(0L, now - startedNanos);
                long observedMillis = Math.max(limits.captureMillis + 1,
                        TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                throw new BudgetExceededException("capture_millis", limits.captureMillis,
                        observedMillis);
            }
        }
    }

    private static final class SourceGroup {
        private final List<String> sources = new ArrayList<>();
    }

    private record SnapshotSource(String coordinate, boolean stable, List<SWRLRule> rules) {
        SnapshotSource {
            rules = List.copyOf(rules);
        }
    }

    private record SourceCoordinate(String value, boolean stable) { }

    private record RenderedAtom(SWRLAtom atom, String canonical) { }

    private record CapturedAtom(String position, int index, String type,
            String predicateIdentity, String predicateDisplay, List<String> arguments,
            int argumentCount, List<String> variables, String canonical) { }

    private record CapturedRule(String ruleId, List<String> sourceOntologies,
            int sourceOntologyCount, String fingerprintStability, List<CapturedAtom> body,
            List<CapturedAtom> head, int variableCount, List<String> missingBodyVariables) { }

    private record ValidatedAtom(CapturedAtom value, CapabilityStatus status) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("position", value.position);
            out.put("index", value.index);
            out.put("type", value.type);
            out.put("predicate", value.predicateDisplay);
            out.put("status", status.value());
            out.put("argument_count", value.argumentCount);
            out.put("arguments_truncated", value.argumentCount > value.arguments.size());
            out.put("arguments", value.arguments);
            out.put("variable_count", value.variables.size());
            out.put("variables_truncated", value.variables.size() > MAX_REPORTED_VARIABLES);
            out.put("variables", value.variables.stream().limit(MAX_REPORTED_VARIABLES)
                    .map(RuleValidationService::compact).toList());
            return out;
        }
    }

    private record ValidatedRule(CapturedRule captured, CapabilityStatus status,
            boolean coverageComplete, CapabilityStatus dlSafetyStatus,
            boolean bodyVariableSafe, boolean atomsTruncated, int findingCount,
            boolean findingsTruncated, List<Map<String, Object>> atoms,
            List<Map<String, Object>> findings, List<String> incompatiblePredicates) {
        Map<String, Object> summaryMap() {
            return Map.of(
                    "rule_id", captured.ruleId,
                    "status", status.value(),
                    "source_ontology_count", captured.sourceOntologyCount,
                    "finding_count", findingCount,
                    "finding_codes", findings.stream().map(finding ->
                            String.valueOf(finding.get("code"))).distinct().sorted().toList(),
                    "incompatible_predicates", incompatiblePredicates);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rule_id", captured.ruleId);
            out.put("fingerprint_stability", captured.fingerprintStability);
            out.put("source_ontology_count", captured.sourceOntologyCount);
            out.put("sources_truncated",
                    captured.sourceOntologyCount > MAX_REPORTED_SOURCES);
            out.put("source_ontologies", captured.sourceOntologies.stream()
                    .limit(MAX_REPORTED_SOURCES).toList());
            out.put("status", status.value());
            out.put("dl_safety_status", dlSafetyStatus.value());
            out.put("dl_safety_basis", "reasoner_profile_engine_semantics");
            out.put("dl_safety_note", "OWLAPI rules do not identify a non-DL predicate partition; "
                    + "this is engine capability evidence, not a syntactic DL-safety proof.");
            out.put("body_variable_safe", bodyVariableSafe);
            out.put("body_variable_criterion",
                    "every variable occurs in at least one non-built-in body atom");
            out.put("missing_body_variable_count", captured.missingBodyVariables.size());
            out.put("missing_body_variables_truncated",
                    captured.missingBodyVariables.size() > MAX_REPORTED_VARIABLES);
            out.put("missing_body_variables", captured.missingBodyVariables.stream()
                    .limit(MAX_REPORTED_VARIABLES).map(RuleValidationService::compact).toList());
            out.put("variable_count", captured.variableCount);
            out.put("atom_count", captured.body.size() + captured.head.size());
            out.put("atoms_truncated", atomsTruncated);
            out.put("finding_count", findingCount);
            out.put("findings_truncated", findingsTruncated);
            out.put("atoms", atoms);
            out.put("findings", findings);
            return out;
        }
    }
}
