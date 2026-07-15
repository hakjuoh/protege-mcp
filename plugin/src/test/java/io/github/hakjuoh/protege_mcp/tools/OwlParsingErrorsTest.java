package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.dlsyntax.parser.DLSyntaxOWLParser;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParser;
import org.semanticweb.owlapi.io.OWLParser;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.io.UnparsableOntologyException;
import org.semanticweb.owlapi.krss2.parser.KRSS2OWLParser;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.rdf.turtle.parser.TurtleOntologyParser;
import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;

class OwlParsingErrorsTest {

    /**
     * Pins that samples render in OWLAPI's parser try order (the aggregate's LinkedHashMap
     * insertion order, format-matched parser first), not alphabetically by parser class name.
     * Turtle's FQN ({@code org.semanticweb.owlapi.rdf.turtle...}) sorts alphabetically LAST of
     * the four parsers below, so a class-name sort would discard the one actionable diagnostic
     * from the three rendered samples and lead with line-1 tokenizer noise instead.
     */
    @Test
    void samplesFollowParserTryOrderNotClassNameOrder() {
        // Mirror OWLOntologyFactoryImpl: exceptions collected in a LinkedHashMap in try order,
        // with the format-matched parser (Turtle for a .ttl document) tried first.
        Map<OWLParser, OWLParserException> tried = new LinkedHashMap<>();
        tried.put(new TurtleOntologyParser(),
                new OWLParserException("Unexpected character '}'", 500, 12));
        tried.put(new DLSyntaxOWLParser(),
                new OWLParserException("Encountered \"<\" at line 1, column 1"));
        tried.put(new OWLFunctionalSyntaxOWLParser(),
                new OWLParserException("Encountered \"@prefix\" at line 1, column 1"));
        tried.put(new KRSS2OWLParser(),
                new OWLParserException("Lexical error at line 1, column 1"));
        UnparsableOntologyException failure = new UnparsableOntologyException(
                IRI.create("http://example.org/broken.ttl"), tried,
                new OWLOntologyLoaderConfiguration());

        String message = OwlParsingErrors.conciseMessage(failure);

        assertTrue(message.contains("'http://example.org/broken.ttl'"), message);
        assertTrue(message.contains("none of 4 registered parsers accepted it"), message);
        String samplesMarker = "Sample errors: ";
        int samples = message.indexOf(samplesMarker);
        assertTrue(samples >= 0, message);
        // The first sample is the first-tried parser's actionable diagnostic.
        assertTrue(message.startsWith(
                "TurtleOntologyParser: Unexpected character '}' (Line 500 column 12)",
                samples + samplesMarker.length()), message);
        // The remaining samples keep the try order and stay bounded to three.
        assertTrue(message.indexOf("DLSyntaxOWLParser:")
                < message.indexOf("OWLFunctionalSyntaxOWLParser:"), message);
        assertFalse(message.contains("KRSS2OWLParser"), message);
    }
}
