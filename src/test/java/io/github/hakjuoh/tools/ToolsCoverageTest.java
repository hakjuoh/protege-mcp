package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import org.protege.editor.owl.model.OWLModelManager;

import io.github.hakjuoh.server.McpAccessException;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Supplementary coverage for {@link Tools} static helpers that {@link ToolsJsonTest} does not touch:
 * argument extraction, the schema builder, IRI parsing, entity/annotation resolution (via
 * {@link FakeModelManager}), rendering, and the entity/axiom/annotation JSON serializers + list
 * pagination. Manchester-syntax paths (resolveClassExpression / resolveDataRange /
 * tryManchesterClassExpression) need the live Protégé checker and are intentionally out of scope.
 */
class ToolsCoverageTest {

    private static final String NS = "http://example.org/x#";

    // ---------------------------------------------------------------- fixtures

    private OWLOntologyManager mgr() {
        return OWLManager.createOWLOntologyManager();
    }

    private OWLOntology ont(OWLOntologyManager m) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/x"));
    }

    private void declare(OWLOntology o, OWLEntity e) {
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(e));
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ================================================================ serialize / guard

    @Test
    void serializeProducesValidPrettyJson() {
        String s = Tools.serialize(map("a", 1, "b", "x"));
        assertTrue(s.contains("\"a\""), "key a present");
        assertTrue(s.contains("\"x\""), "value x present");
        assertTrue(s.contains("\n"), "pretty printer adds newlines");
    }

    @Test
    void serializeNullYieldsJsonNullToken() {
        assertEquals("null", Tools.serialize(null), "null serializes to the JSON null literal");
    }

    @Test
    void serializeUnserializableFallsBackToStringValueOf() {
        // A bean whose getter throws makes Jackson raise a JsonProcessingException wrapping the
        // RuntimeException; serialize() catches it and falls back to String.valueOf(data).
        Throwing bad = new Throwing();
        assertEquals(bad.toString(), Tools.serialize(bad),
                "an unserializable object falls back to its toString");
    }

    /** A bean Jackson cannot serialize: the getter throws, forcing serialize()'s catch/fallback. */
    public static final class Throwing {
        public String getValue() {
            throw new IllegalStateException("cannot read");
        }

        @Override
        public String toString() {
            return "Throwing-fallback";
        }
    }

    @Test
    void serializeHandlesCollectionsAndPrimitives() {
        assertEquals("42", Tools.serialize(42));
        assertTrue(Tools.serialize(Arrays.asList(1, 2, 3)).contains("1"), "list serializes");
    }

    @Test
    void guardReturnsBodyResultWhenNoException() {
        CallToolResult ok = Tools.text("fine");
        assertSame(ok, Tools.guard(() -> ok), "successful body result is passed through unchanged");
    }

    @Test
    void guardConvertsToolArgExceptionToErrorMessage() {
        CallToolResult r = Tools.guard(() -> {
            throw new ToolArgException("bad arg");
        });
        assertTrue(r.isError(), "ToolArgException becomes an error result");
        assertEquals("bad arg", errorText(r));
    }

    @Test
    void guardConvertsMcpAccessExceptionToErrorMessage() {
        CallToolResult r = Tools.guard(() -> {
            throw new McpAccessException("edt busy");
        });
        assertTrue(r.isError());
        assertEquals("edt busy", errorText(r));
    }

    @Test
    void guardWrapsGenericRuntimeExceptionWithSimpleNameAndMessage() {
        CallToolResult r = Tools.guard(() -> {
            throw new IllegalStateException("kaboom");
        });
        assertTrue(r.isError());
        assertEquals("IllegalStateException: kaboom", errorText(r));
    }

    @Test
    void guardWrapsRuntimeExceptionWithoutMessageAsSimpleNameOnly() {
        CallToolResult r = Tools.guard(() -> {
            throw new IllegalStateException();
        });
        assertTrue(r.isError());
        assertEquals("IllegalStateException", errorText(r));
    }

    private String errorText(CallToolResult r) {
        Object sc = r.structuredContent();
        assertTrue(sc instanceof Map, "structured content is a map");
        return String.valueOf(((Map<?, ?>) sc).get("error"));
    }

    // ================================================================ result builders (gaps)

    @Test
    void okWithNullDataYieldsEmptyStructuredMap() {
        CallToolResult r = Tools.ok(null);
        assertFalse(r.isError());
        assertTrue(((Map<?, ?>) r.structuredContent()).isEmpty(), "null data becomes an empty map");
    }

    @Test
    void okAlsoEmitsASerializedTextBlock() {
        CallToolResult r = Tools.ok(map("k", "v"));
        assertFalse(r.content().isEmpty());
        assertTrue(r.content().get(0) instanceof TextContent);
        assertTrue(((TextContent) r.content().get(0)).text().contains("\"v\""),
                "the text block is the serialized body");
    }

    @Test
    void textWithNullBecomesEmptyMessage() {
        CallToolResult r = Tools.text(null);
        assertEquals("", ((Map<?, ?>) r.structuredContent()).get("message"));
    }

    @Test
    void errorWithNullMessageDefaultsToError() {
        CallToolResult r = Tools.error(null);
        assertTrue(r.isError());
        assertEquals("error", errorText(r));
    }

    @Test
    void jsonReturnsIndependentInstances() {
        Tools.Json a = Tools.json();
        Tools.Json b = Tools.json();
        assertNotSame(a, b, "each json() call is a fresh builder");
        a.put("x", 1);
        assertFalse(b.map().containsKey("x"), "builders do not share state");
    }

    @Test
    void jsonPutAcceptsNullValueAndChains() {
        Tools.Json j = Tools.json();
        assertSame(j, j.put("a", null), "put is fluent");
        assertTrue(j.map().containsKey("a"), "put(null) still records the key");
        assertNull(j.map().get("a"));
    }

    @Test
    void jsonPutIfNotNullSkipsNullButKeepsZero() {
        Map<String, Object> m = Tools.json().putIfNotNull("skip", null).putIfNotNull("z", 0).map();
        assertFalse(m.containsKey("skip"), "null value is omitted");
        assertTrue(m.containsKey("z"), "zero is not null, so it is kept");
        assertEquals(0, m.get("z"));
    }

    @Test
    void jsonMapPreservesInsertionOrderAndBacksTheBuilder() {
        Tools.Json j = Tools.json().put("first", 1).put("second", 2);
        List<String> keys = new ArrayList<>(j.map().keySet());
        assertEquals(Arrays.asList("first", "second"), keys, "LinkedHashMap keeps insertion order");
        j.map().put("third", 3);
        assertTrue(j.map().containsKey("third"), "the returned map is the live backing map");
    }

    @Test
    void jsonResultDelegatesToOk() {
        CallToolResult r = Tools.json().put("hello", "world").result();
        assertFalse(r.isError());
        assertEquals("world", ((Map<?, ?>) r.structuredContent()).get("hello"));
    }

    // ================================================================ argument extraction

    @Test
    void argsReturnsEmptyMapWhenArgumentsNull() {
        assertTrue(Tools.args(new CallToolRequest("t", null)).isEmpty(),
                "null arguments becomes an empty map");
    }

    @Test
    void argsReturnsProvidedArgumentsMap() {
        Map<String, Object> a = map("k", "v");
        assertEquals(a, Tools.args(new CallToolRequest("t", a)));
    }

    @Test
    void optStringMissingKeyIsNull() {
        assertNull(Tools.optString(Collections.emptyMap(), "k"));
    }

    @Test
    void optStringNullValueIsNull() {
        assertNull(Tools.optString(map("k", null), "k"));
    }

    @Test
    void optStringEmptyValueIsNull() {
        assertNull(Tools.optString(map("k", ""), "k"), "empty string coerces to null");
    }

    @Test
    void optStringCoercesNonStringViaStringValueOf() {
        assertEquals("hello", Tools.optString(map("k", "hello"), "k"));
        assertEquals("123", Tools.optString(map("k", 123), "k"));
        assertEquals("true", Tools.optString(map("k", true), "k"));
    }

    @Test
    void reqStringReturnsValueOrThrows() {
        assertEquals("hi", Tools.reqString(map("k", "hi"), "k"));
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Tools.reqString(Collections.emptyMap(), "k"));
        assertTrue(ex.getMessage().contains("k"), "message names the missing key");
    }

    @Test
    void reqStringTreatsEmptyValueAsMissing() {
        assertThrows(ToolArgException.class, () -> Tools.reqString(map("k", ""), "k"),
                "empty string is treated as missing (optString returns null)");
    }

    @Test
    void optIntDefaultWhenMissingOrNull() {
        assertEquals(7, Tools.optInt(Collections.emptyMap(), "k", 7));
        assertEquals(7, Tools.optInt(map("k", null), "k", 7));
    }

    @Test
    void optIntReadsNumberTypes() {
        assertEquals(42, Tools.optInt(map("k", 42), "k", 0));
        assertEquals(9, Tools.optInt(map("k", 9L), "k", 0), "Long uses intValue");
        assertEquals(3, Tools.optInt(map("k", 3.9d), "k", 0), "Double truncates via intValue");
    }

    @Test
    void optIntParsesTrimmedStringOrFallsBack() {
        assertEquals(42, Tools.optInt(map("k", "42"), "k", 0));
        assertEquals(99, Tools.optInt(map("k", "  99  "), "k", 0), "whitespace is trimmed");
        assertEquals(-1, Tools.optInt(map("k", "not a number"), "k", -1), "unparseable falls back");
    }

    @Test
    void optIntNonNumberNonStringFallsBack() {
        assertEquals(5, Tools.optInt(map("k", Arrays.asList(1, 2)), "k", 5),
                "a list is neither Number nor parseable, so default");
    }

    @Test
    void optBoolDefaultWhenMissingOrNull() {
        assertTrue(Tools.optBool(Collections.emptyMap(), "k", true));
        assertFalse(Tools.optBool(map("k", null), "k", false));
    }

    @Test
    void optBoolReadsBooleanValues() {
        assertTrue(Tools.optBool(map("k", Boolean.TRUE), "k", false));
        assertFalse(Tools.optBool(map("k", Boolean.FALSE), "k", true));
    }

    @Test
    void optBoolParsesTrimmedStrings() {
        assertTrue(Tools.optBool(map("k", "true"), "k", false));
        assertFalse(Tools.optBool(map("k", "  false  "), "k", true), "whitespace is trimmed");
        assertFalse(Tools.optBool(map("k", "maybe"), "k", true),
                "non-boolean text parses to false via Boolean.parseBoolean");
    }

    @Test
    void stringListMissingIsEmpty() {
        assertTrue(Tools.stringList(Collections.emptyMap(), "k").isEmpty());
        assertTrue(Tools.stringList(map("k", null), "k").isEmpty(), "null value yields empty list");
    }

    @Test
    void stringListConvertsListItemsAndSkipsNulls() {
        List<Object> in = new ArrayList<>();
        in.add("a");
        in.add(123);
        in.add(null);
        in.add(true);
        assertEquals(Arrays.asList("a", "123", "true"), Tools.stringList(map("k", in), "k"),
                "each item is String.valueOf'd and nulls are dropped");
    }

    @Test
    void stringListWrapsNonListSingleValue() {
        assertEquals(Collections.singletonList("solo"), Tools.stringList(map("k", "solo"), "k"));
        assertEquals(Collections.singletonList("5"), Tools.stringList(map("k", 5), "k"));
    }

    @Test
    void objListMissingIsEmpty() {
        assertTrue(Tools.objList(Collections.emptyMap(), "k").isEmpty());
    }

    @Test
    void objListKeepsOnlyMapItems() {
        List<Object> in = Arrays.asList(map("a", 1), "not a map", map("b", 2), 99);
        List<Map<String, Object>> out = Tools.objList(map("k", in), "k");
        assertEquals(2, out.size(), "only Map items survive");
        assertEquals(1, out.get(0).get("a"));
    }

    @Test
    void objListWrapsASingleMap() {
        List<Map<String, Object>> out = Tools.objList(map("k", map("a", 1)), "k");
        assertEquals(1, out.size(), "a bare Map becomes a one-item list");
    }

    @Test
    void objListNonMapNonListIsEmpty() {
        assertTrue(Tools.objList(map("k", "string"), "k").isEmpty());
    }

    // ================================================================ schema builder

    @Test
    void emptySchemaShape() {
        Map<String, Object> s = Tools.emptySchema();
        assertEquals("object", s.get("type"));
        assertEquals(Boolean.FALSE, s.get("additionalProperties"));
        assertFalse(s.containsKey("properties"), "empty schema has no properties key");
        assertFalse(s.containsKey("required"));
    }

    @Test
    void stringPropertyWithAndWithoutDescription() {
        Map<String, Object> withDesc = Tools.stringProperty("a string");
        assertEquals("string", withDesc.get("type"));
        assertEquals("a string", withDesc.get("description"));
        assertFalse(Tools.stringProperty(null).containsKey("description"),
                "null description is omitted");
    }

    @Test
    void boolPropertyWithAndWithoutDescription() {
        Map<String, Object> withDesc = Tools.boolProperty("yes or no");
        assertEquals("boolean", withDesc.get("type"));
        assertEquals("yes or no", withDesc.get("description"));
        assertFalse(Tools.boolProperty(null).containsKey("description"));
    }

    @Test
    void schemaReturnsFreshBuilders() {
        assertNotSame(Tools.schema(), Tools.schema());
    }

    @Test
    void schemaBuilderStrAndStrReqTrackRequired() {
        Map<String, Object> s = Tools.schema().str("opt", "d1").strReq("must", "d2").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        assertTrue(props.containsKey("opt"));
        assertTrue(props.containsKey("must"));
        @SuppressWarnings("unchecked")
        List<String> req = (List<String>) s.get("required");
        assertEquals(Collections.singletonList("must"), req, "only strReq adds to required");
    }

    @Test
    void schemaBuilderStrPropertyHasTypeAndDescription() {
        Map<String, Object> s = Tools.schema().str("name", "the name").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> name = (Map<String, Object>) props.get("name");
        assertEquals("string", name.get("type"));
        assertEquals("the name", name.get("description"));
    }

    @Test
    void schemaBuilderIntegerAndBoolTypes() {
        Map<String, Object> s = Tools.schema().integer("n", "count").bool("flag", "on/off").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        assertEquals("integer", ((Map<?, ?>) props.get("n")).get("type"));
        assertEquals("boolean", ((Map<?, ?>) props.get("flag")).get("type"));
        assertFalse(s.containsKey("required"), "integer/bool are not required");
    }

    @Test
    void schemaBuilderStrArrayShapeAndRequiredVariant() {
        Map<String, Object> s = Tools.schema().strArray("a", "list").strArrayReq("b", "req list").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) props.get("a");
        assertEquals("array", a.get("type"));
        assertEquals("string", ((Map<?, ?>) a.get("items")).get("type"));
        assertEquals("list", a.get("description"));
        @SuppressWarnings("unchecked")
        List<String> req = (List<String>) s.get("required");
        assertEquals(Collections.singletonList("b"), req, "only strArrayReq is required");
    }

    @Test
    void schemaBuilderAnnotationArrayNestedItemSchema() {
        Map<String, Object> s = Tools.schema().annotationArray("annotations", "the anns").build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> arr = (Map<String, Object>) props.get("annotations");
        assertEquals("array", arr.get("type"));
        assertEquals("the anns", arr.get("description"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) arr.get("items");
        assertEquals("object", item.get("type"));
        assertEquals(Boolean.FALSE, item.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
        for (String k : Arrays.asList("property", "value", "value_iri", "lang", "datatype")) {
            assertTrue(itemProps.containsKey(k), "item schema has property " + k);
        }
        assertFalse(s.containsKey("required"), "annotationArray is not required by default");
    }

    @Test
    void schemaBuilderAnnotationArrayOmitsNullDescription() {
        Map<String, Object> s = Tools.schema().annotationArray("annotations", null).build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        assertFalse(((Map<?, ?>) props.get("annotations")).containsKey("description"));
    }

    @Test
    void schemaBuildOmitsRequiredWhenNoneAndKeepsOrder() {
        Map<String, Object> s = Tools.schema().str("first", null).str("second", null).build();
        assertFalse(s.containsKey("required"), "no required list when nothing is required");
        assertEquals("object", s.get("type"));
        assertEquals(Boolean.FALSE, s.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        assertEquals(Arrays.asList("first", "second"), new ArrayList<>(props.keySet()),
                "property order is preserved");
    }

    @Test
    void schemaBuilderIsFluent() {
        Tools.SchemaBuilder b = Tools.schema();
        assertSame(b, b.str("a", null));
        assertSame(b, b.strReq("b", null));
        assertSame(b, b.integer("c", null));
        assertSame(b, b.bool("d", null));
        assertSame(b, b.strArray("e", null));
        assertSame(b, b.strArrayReq("f", null));
        assertSame(b, b.annotationArray("g", null));
    }

    // ================================================================ asIri

    @Test
    void asIriNullIsNull() {
        assertNull(Tools.asIri(null));
    }

    @Test
    void asIriAbsoluteHttpAndUrn() {
        assertEquals(IRI.create("http://example.org/Foo"), Tools.asIri("http://example.org/Foo"));
        assertEquals(IRI.create("urn:uuid:12345"), Tools.asIri("urn:uuid:12345"));
    }

    @Test
    void asIriRelativeOrShortNameIsNull() {
        assertNull(Tools.asIri("relative/path"), "relative reference is not absolute");
        assertNull(Tools.asIri("shortName"), "a bare name is not an IRI");
    }

    @Test
    void asIriMalformedUriIsNull() {
        assertNull(Tools.asIri("http://exa mple.org/Foo"),
                "a URI with an illegal space is caught and yields null");
    }

    // ================================================================ findEntity / findEntities

    @Test
    void findEntityByFullIri() throws Exception {
        OWLOntologyManager m = mgr();
        OWLOntology o = ont(m);
        OWLClass dog = m.getOWLDataFactory().getOWLClass(IRI.create(NS + "Dog"));
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(dog, Tools.findEntity(mm, NS + "Dog"));
    }

    @Test
    void findEntityByShortForm() throws Exception {
        OWLOntologyManager m = mgr();
        OWLOntology o = ont(m);
        OWLClass dog = m.getOWLDataFactory().getOWLClass(IRI.create(NS + "Dog"));
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(dog, Tools.findEntity(mm, "Dog"));
    }

    @Test
    void findEntityUnknownIsNull() {
        assertNull(Tools.findEntity(FakeModelManager.empty(), "Nope"));
    }

    @Test
    void findEntitiesReturnsAllPunnedEntities() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass asClass = df.getOWLClass(IRI.create(NS + "Pun"));
        OWLNamedIndividual asInd = df.getOWLNamedIndividual(IRI.create(NS + "Pun"));
        declare(o, asClass);
        declare(o, asInd);
        OWLModelManager mm = FakeModelManager.over(o);
        Set<OWLEntity> es = Tools.findEntities(mm, NS + "Pun");
        assertEquals(2, es.size(), "both the class and individual with that IRI are returned");
    }

    @Test
    void findEntitiesUnknownIsEmpty() {
        assertTrue(Tools.findEntities(FakeModelManager.empty(), "Nope").isEmpty());
    }

    // ================================================================ resolve* (via FakeModelManager)

    @Test
    void resolveClassExisting() throws Exception {
        OWLOntologyManager m = mgr();
        OWLOntology o = ont(m);
        OWLClass dog = m.getOWLDataFactory().getOWLClass(IRI.create(NS + "Dog"));
        declare(o, dog);
        assertEquals(dog, Tools.resolveClass(FakeModelManager.over(o), "Dog"));
    }

    @Test
    void resolveClassMintsFromFullIri() {
        OWLClass minted = Tools.resolveClass(FakeModelManager.empty(), "http://example.org/New");
        assertEquals(IRI.create("http://example.org/New"), minted.getIRI(),
                "an undeclared full IRI mints a new named class");
    }

    @Test
    void resolveClassUnknownNameThrows() {
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Tools.resolveClass(FakeModelManager.empty(), "Unknown"));
        assertTrue(ex.getMessage().contains("class not found"), "message: " + ex.getMessage());
    }

    @Test
    void resolveClassWrongTypeThrows() throws Exception {
        OWLOntologyManager m = mgr();
        OWLNamedIndividual ind = m.getOWLDataFactory().getOWLNamedIndividual(IRI.create(NS + "Alice"));
        OWLOntology o = ont(m);
        declare(o, ind);
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Tools.resolveClass(FakeModelManager.over(o), "Alice"));
        assertTrue(ex.getMessage().contains("not a class"), "message: " + ex.getMessage());
    }

    @Test
    void resolveIndividualExistingMintAndErrors() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLNamedIndividual alice = df.getOWLNamedIndividual(IRI.create(NS + "Alice"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, alice);
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(alice, Tools.resolveIndividual(mm, "Alice"));
        assertEquals(IRI.create("http://example.org/NewInd"),
                Tools.resolveIndividual(mm, "http://example.org/NewInd").getIRI());
        assertThrows(ToolArgException.class, () -> Tools.resolveIndividual(mm, "Unknown"));
        assertThrows(ToolArgException.class, () -> Tools.resolveIndividual(mm, "Dog"),
                "a class is not an individual");
    }

    @Test
    void resolveObjectPropertyExistingMintAndErrors() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty hasPart = df.getOWLObjectProperty(IRI.create(NS + "hasPart"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, hasPart);
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(hasPart, Tools.resolveObjectProperty(mm, "hasPart"));
        assertEquals(IRI.create("http://example.org/newProp"),
                Tools.resolveObjectProperty(mm, "http://example.org/newProp").getIRI());
        assertThrows(ToolArgException.class, () -> Tools.resolveObjectProperty(mm, "Unknown"));
        assertThrows(ToolArgException.class, () -> Tools.resolveObjectProperty(mm, "Dog"));
    }

    @Test
    void resolveDataPropertyExistingMintAndErrors() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "age"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, age);
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(age, Tools.resolveDataProperty(mm, "age"));
        assertEquals(IRI.create("http://example.org/newDP"),
                Tools.resolveDataProperty(mm, "http://example.org/newDP").getIRI());
        assertThrows(ToolArgException.class, () -> Tools.resolveDataProperty(mm, "Unknown"));
        assertThrows(ToolArgException.class, () -> Tools.resolveDataProperty(mm, "Dog"));
    }

    @Test
    void resolveAnnotationPropertyExistingMintAndErrors() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLAnnotationProperty seeAlso = df.getOWLAnnotationProperty(IRI.create(NS + "seeAlso"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, seeAlso);
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(seeAlso, Tools.resolveAnnotationProperty(mm, "seeAlso"));
        assertEquals(IRI.create("http://example.org/newAP"),
                Tools.resolveAnnotationProperty(mm, "http://example.org/newAP").getIRI());
        assertThrows(ToolArgException.class, () -> Tools.resolveAnnotationProperty(mm, "Unknown"));
        assertThrows(ToolArgException.class, () -> Tools.resolveAnnotationProperty(mm, "Dog"));
    }

    @Test
    void resolveDatatypeExistingMintAndErrors() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLDatatype dt = df.getOWLDatatype(IRI.create(NS + "myType"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, dt);
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(dt, Tools.resolveDatatype(mm, "myType"));
        assertEquals(IRI.create("http://example.org/newDT"),
                Tools.resolveDatatype(mm, "http://example.org/newDT").getIRI());
        assertThrows(ToolArgException.class, () -> Tools.resolveDatatype(mm, "Unknown"));
        assertThrows(ToolArgException.class, () -> Tools.resolveDatatype(mm, "Dog"));
    }

    // ================================================================ iriRef / annotationSubject

    @Test
    void iriRefExistingEntityUsesItsIri() throws Exception {
        OWLOntologyManager m = mgr();
        OWLClass dog = m.getOWLDataFactory().getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, dog);
        assertEquals(dog.getIRI(), Tools.iriRef(FakeModelManager.over(o), "Dog"));
    }

    @Test
    void iriRefAbsoluteIriPassesThrough() {
        assertEquals(IRI.create("http://example.org/Foo"),
                Tools.iriRef(FakeModelManager.empty(), "http://example.org/Foo"));
    }

    @Test
    void iriRefUnknownNonIriThrows() {
        assertThrows(ToolArgException.class, () -> Tools.iriRef(FakeModelManager.empty(), "Unknown"));
    }

    @Test
    void annotationSubjectExistingIriAndError() throws Exception {
        OWLOntologyManager m = mgr();
        OWLClass dog = m.getOWLDataFactory().getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, dog);
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(dog.getIRI(), Tools.annotationSubject(mm, "Dog"));
        assertEquals(IRI.create("http://example.org/Foo"),
                Tools.annotationSubject(mm, "http://example.org/Foo"));
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Tools.annotationSubject(mm, "Random"));
        assertTrue(ex.getMessage().contains("Entity not found"), "message: " + ex.getMessage());
    }

    // ================================================================ annotationProperty

    @Test
    void annotationPropertyBuiltinsAreCaseInsensitive() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        assertEquals(df.getRDFSLabel(), Tools.annotationProperty(mm, null), "null defaults to label");
        assertEquals(df.getRDFSLabel(), Tools.annotationProperty(mm, "rdfs:label"));
        assertEquals(df.getRDFSLabel(), Tools.annotationProperty(mm, "LABEL"), "case-insensitive");
        assertEquals(df.getRDFSComment(), Tools.annotationProperty(mm, "rdfs:comment"));
        assertEquals(df.getRDFSComment(), Tools.annotationProperty(mm, "Comment"));
    }

    @Test
    void annotationPropertyMintsFromFullIri() {
        OWLAnnotationProperty ap = Tools.annotationProperty(FakeModelManager.empty(),
                "http://example.org/customAP");
        assertEquals(IRI.create("http://example.org/customAP"), ap.getIRI());
    }

    // ================================================================ annotationValue

    @Test
    void annotationValueIriValued() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAnnotationValue v = Tools.annotationValue(mm, map("value_iri", "http://example.org/Foo"));
        assertEquals(IRI.create("http://example.org/Foo"), v);
    }

    @Test
    void annotationValuePlainLiteral() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAnnotationValue v = Tools.annotationValue(mm, map("value", "hello"));
        assertTrue(v.asLiteral().isPresent());
        OWLLiteral lit = v.asLiteral().get();
        assertEquals("hello", lit.getLiteral());
        assertFalse(lit.hasLang(), "no language tag");
    }

    @Test
    void annotationValueLangTagged() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLLiteral lit = Tools.annotationValue(mm, map("value", "chien", "lang", "fr")).asLiteral().get();
        assertEquals("chien", lit.getLiteral());
        assertEquals("fr", lit.getLang());
    }

    @Test
    void annotationValueTypedLiteral() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLLiteral lit = Tools.annotationValue(mm,
                map("value", "42", "datatype", "http://www.w3.org/2001/XMLSchema#integer")).asLiteral().get();
        assertEquals("42", lit.getLiteral());
        assertTrue(lit.getDatatype().getIRI().toString().endsWith("integer"));
    }

    @Test
    void annotationValueEmptyStringValueIsTreatedAsMissing() {
        OWLModelManager mm = FakeModelManager.empty();
        // "value" is "" -> optString coerces to null -> reqString("value") throws even with a datatype.
        assertThrows(ToolArgException.class, () -> Tools.annotationValue(mm,
                map("value", "", "datatype", "http://www.w3.org/2001/XMLSchema#string")));
    }

    @Test
    void annotationValueMissingValueAndValueIriThrows() {
        OWLModelManager mm = FakeModelManager.empty();
        assertThrows(ToolArgException.class, () -> Tools.annotationValue(mm, Collections.emptyMap()),
                "neither value nor value_iri present");
    }

    // ================================================================ buildAnnotation / annotationSet

    @Test
    void buildAnnotationDefaultsToLabel() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAnnotation ann = Tools.buildAnnotation(mm, map("value", "Dog"));
        assertEquals(mm.getOWLDataFactory().getRDFSLabel(), ann.getProperty(),
                "no property defaults to rdfs:label");
        assertEquals("Dog", ann.getValue().asLiteral().get().getLiteral());
    }

    @Test
    void buildAnnotationWithCommentAndIriValue() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAnnotation ann = Tools.buildAnnotation(mm,
                map("property", "rdfs:comment", "value_iri", "http://example.org/Doc"));
        assertEquals(mm.getOWLDataFactory().getRDFSComment(), ann.getProperty());
        assertEquals(IRI.create("http://example.org/Doc"), ann.getValue());
    }

    @Test
    void buildAnnotationMissingValueThrows() {
        OWLModelManager mm = FakeModelManager.empty();
        assertThrows(ToolArgException.class, () -> Tools.buildAnnotation(mm, map("property", "rdfs:label")));
    }

    @Test
    void annotationSetMissingKeyIsEmpty() {
        assertTrue(Tools.annotationSet(FakeModelManager.empty(), Collections.emptyMap(), "annotations")
                .isEmpty());
    }

    @Test
    void annotationSetBuildsAndDedupes() {
        OWLModelManager mm = FakeModelManager.empty();
        List<Object> anns = Arrays.asList(
                map("property", "rdfs:label", "value", "Dog"),
                map("property", "rdfs:label", "value", "Dog"),
                map("property", "rdfs:comment", "value", "A dog"));
        Set<OWLAnnotation> set = Tools.annotationSet(mm, map("annotations", anns), "annotations");
        assertEquals(2, set.size(), "duplicate annotation collapses in the LinkedHashSet");
    }

    // ================================================================ rendering (FakeModelManager)

    @Test
    void renderEntityIsShortFormPlusAngleBracketIri() throws Exception {
        OWLOntologyManager m = mgr();
        OWLClass dog = m.getOWLDataFactory().getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, dog);
        assertEquals("Dog  <" + NS + "Dog>", Tools.renderEntity(FakeModelManager.over(o), dog));
    }

    @Test
    void renderAxiomUsesManagerRendering() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLAxiom ax = df.getOWLSubClassOfAxiom(a, b);
        // FakeModelManager.getRendering falls to String.valueOf for a non-entity => the axiom's toString.
        assertEquals(ax.toString(), Tools.renderAxiom(mm, ax));
    }

    @Test
    void renderObjectFallsBackToToStringForNonEntity() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClassExpressionHolder holder = new OWLClassExpressionHolder(df.getOWLObjectIntersectionOf(a, b));
        String r = Tools.renderObject(mm, holder.expr);
        assertEquals(holder.expr.toString(), r, "non-entity expression renders via toString");
    }

    /** Tiny wrapper so the intersection expression's type is obvious at the call site. */
    private static final class OWLClassExpressionHolder {
        final org.semanticweb.owlapi.model.OWLClassExpression expr;

        OWLClassExpressionHolder(org.semanticweb.owlapi.model.OWLClassExpression expr) {
            this.expr = expr;
        }
    }

    // ================================================================ JSON serializers

    @Test
    void entityJsonShape() throws Exception {
        OWLOntologyManager m = mgr();
        OWLClass dog = m.getOWLDataFactory().getOWLClass(IRI.create(NS + "Dog"));
        OWLOntology o = ont(m);
        declare(o, dog);
        Map<String, Object> j = Tools.entityJson(FakeModelManager.over(o), dog);
        assertEquals(NS + "Dog", j.get("iri"));
        assertEquals("Dog", j.get("display"));
        assertEquals("Class", j.get("type"), "type is EntityType name");
    }

    @Test
    void entityJsonIndividualType() throws Exception {
        OWLOntologyManager m = mgr();
        OWLNamedIndividual alice = m.getOWLDataFactory().getOWLNamedIndividual(IRI.create(NS + "Alice"));
        OWLOntology o = ont(m);
        declare(o, alice);
        assertEquals("NamedIndividual", Tools.entityJson(FakeModelManager.over(o), alice).get("type"));
    }

    @Test
    void axiomJsonShape() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom ax = df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "A")),
                df.getOWLClass(IRI.create(NS + "B")));
        Map<String, Object> j = Tools.axiomJson(mm, ax);
        assertEquals("SubClassOf", j.get("axiom_type"));
        assertEquals(ax.toString(), j.get("rendering"), "rendering falls back to toString");
    }

    @Test
    void annotationJsonLiteralWithLang() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAnnotation ann = df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("chien", "fr"));
        Map<String, Object> j = Tools.annotationJson(mm, ann);
        assertEquals(df.getRDFSLabel().getIRI().toString(), j.get("property_iri"));
        assertEquals("chien", j.get("value"));
        assertEquals("fr", j.get("lang"));
        assertFalse(j.containsKey("datatype"), "lang and datatype are mutually exclusive here");
    }

    @Test
    void annotationJsonPlainStringOmitsDatatype() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAnnotation ann = df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("Dog"));
        Map<String, Object> j = Tools.annotationJson(mm, ann);
        assertEquals("Dog", j.get("value"));
        assertFalse(j.containsKey("datatype"), "xsd:string is implicit and suppressed");
        assertFalse(j.containsKey("lang"));
    }

    @Test
    void annotationJsonTypedNonStringLiteralIncludesDatatype() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAnnotation ann = df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral(42));
        Map<String, Object> j = Tools.annotationJson(mm, ann);
        assertEquals("42", j.get("value"));
        assertTrue(String.valueOf(j.get("datatype")).endsWith("integer"),
                "a genuine non-string datatype is surfaced");
    }

    @Test
    void annotationJsonIriValued() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAnnotation ann = df.getOWLAnnotation(df.getRDFSSeeAlso(),
                IRI.create("http://example.org/Doc"));
        Map<String, Object> j = Tools.annotationJson(mm, ann);
        assertEquals("http://example.org/Doc", j.get("value_iri"));
        assertFalse(j.containsKey("value"), "IRI-valued annotations have no literal value key");
    }

    // ================================================================ entityList / axiomList

    @Test
    void entityListSortsCaseInsensitivelyAndTruncates() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLClass cat = df.getOWLClass(IRI.create(NS + "Cat"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        Map<String, Object> j = Tools.entityList(mm, Arrays.asList(dog, cat, animal), 2);
        assertEquals(3, j.get("count"), "count is the full size");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) j.get("items");
        assertEquals(2, items.size());
        assertEquals("Animal", items.get(0).get("display"), "sorted: Animal before Cat");
        assertEquals("Cat", items.get(1).get("display"));
        assertEquals(1, j.get("truncated"), "one entity omitted");
    }

    @Test
    void entityListLimitZeroKeepsCountButEmptyItems() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        Map<String, Object> j = Tools.entityList(mm, Collections.singletonList(dog), 0);
        assertEquals(1, j.get("count"));
        assertTrue(((List<?>) j.get("items")).isEmpty());
        assertEquals(1, j.get("truncated"));
    }

    @Test
    void entityListNegativeLimitClampsToZero() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        Map<String, Object> j = Tools.entityList(mm, Collections.singletonList(dog), -5);
        assertTrue(((List<?>) j.get("items")).isEmpty(), "negative limit clamps to 0");
        assertEquals(1, j.get("truncated"));
    }

    @Test
    void entityListEmptyHasNoTruncatedField() {
        Map<String, Object> j = Tools.entityList(FakeModelManager.empty(),
                Collections.<OWLEntity>emptyList(), 10);
        assertEquals(0, j.get("count"));
        assertTrue(((List<?>) j.get("items")).isEmpty());
        assertFalse(j.containsKey("truncated"), "no overflow, no truncated key");
    }

    @Test
    void entityListNoTruncatedWhenLimitCoversAll() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        Map<String, Object> j = Tools.entityList(mm, Collections.singletonList(dog), 10);
        assertFalse(j.containsKey("truncated"));
    }

    @Test
    void axiomListSortsByRenderingAndTruncates() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        List<OWLAxiom> axioms = new ArrayList<>();
        for (String c : Arrays.asList("Zebra", "Ant", "Mouse", "Bee", "Cat")) {
            axioms.add(df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + c))));
        }
        Map<String, Object> j = Tools.axiomList(mm, axioms, 3);
        assertEquals(5, j.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) j.get("items");
        assertEquals(3, items.size());
        List<String> renderings = new ArrayList<>();
        for (Map<String, Object> it : items) {
            renderings.add(String.valueOf(it.get("rendering")));
        }
        List<String> sortedCopy = new ArrayList<>(renderings);
        Collections.sort(sortedCopy);
        assertEquals(sortedCopy, renderings, "items are sorted by rendering string");
        assertEquals(2, j.get("truncated"));
    }

    @Test
    void axiomListLimitZeroAndEmpty() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom ax = df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "A")));
        Map<String, Object> zero = Tools.axiomList(mm, Collections.singletonList(ax), 0);
        assertEquals(1, zero.get("count"));
        assertTrue(((List<?>) zero.get("items")).isEmpty());
        assertEquals(1, zero.get("truncated"));

        Map<String, Object> empty = Tools.axiomList(mm, Collections.<OWLAxiom>emptyList(), 5);
        assertEquals(0, empty.get("count"));
        assertFalse(empty.containsKey("truncated"));
    }
}
