package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded, XXE-safe reader for the OASIS {@code uri} and {@code nextCatalog} subset. */
public final class OfflineCatalog {

    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private OfflineCatalog() {
    }

    /** Parse one catalog without dereferencing any mapping or catalog target. */
    public static Document read(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Path source = path.toAbsolutePath().normalize();
        byte[] bytes;
        try (var input = Files.newInputStream(source)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("catalog exceeds " + MAX_BYTES + " bytes");
        }
        return parse(bytes, source);
    }

    public static Document parse(byte[] bytes, Path source) throws IOException {
        if (bytes == null || source == null) {
            throw new IllegalArgumentException("bytes and source must not be null");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("catalog exceeds " + MAX_BYTES + " bytes");
        }
        org.w3c.dom.Document xml;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException error) throws SAXParseException {
                    throw error;
                }

                @Override
                public void fatalError(SAXParseException error) throws SAXParseException {
                    throw error;
                }
            });
            xml = builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception error) {
            throw new IOException("invalid catalog XML: " + message(error), error);
        }

        Path normalized = source.toAbsolutePath().normalize();
        URI catalogUri = normalized.toUri();
        List<Entry> entries = new ArrayList<>();
        List<URI> nextCatalogs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        org.w3c.dom.NodeList uriNodes = xml.getElementsByTagNameNS("*", "uri");
        for (int index = 0; index < uriNodes.getLength(); index++) {
            Element element = (Element) uriNodes.item(index);
            String name = element.getAttribute("name");
            String reference = element.getAttribute("uri");
            if (name.isBlank() || reference.isBlank()) {
                errors.add("catalog uri entry " + index + " is missing name/uri");
                continue;
            }
            if (!names.add(name)) {
                errors.add("duplicate catalog name: " + name);
            }
            try {
                entries.add(new Entry(name, reference,
                        effectiveXmlBase(element, catalogUri).resolve(reference)));
            } catch (IllegalArgumentException invalid) {
                errors.add("catalog uri entry does not resolve to a valid URI: " + reference);
                entries.add(new Entry(name, reference, null));
            }
        }

        org.w3c.dom.NodeList nextNodes = xml.getElementsByTagNameNS("*", "nextCatalog");
        for (int index = 0; index < nextNodes.getLength(); index++) {
            Element element = (Element) nextNodes.item(index);
            String reference = element.getAttribute("catalog");
            if (reference.isBlank()) {
                errors.add("catalog nextCatalog entry " + index + " is missing catalog");
                continue;
            }
            try {
                nextCatalogs.add(effectiveXmlBase(element, catalogUri).resolve(reference));
            } catch (IllegalArgumentException invalid) {
                errors.add("nextCatalog does not resolve to a valid URI: " + reference);
            }
        }
        return new Document(normalized, entries, nextCatalogs, errors);
    }

    private static URI effectiveXmlBase(Element element, URI catalogUri) {
        List<String> bases = new ArrayList<>();
        for (Node node = element; node instanceof Element; node = node.getParentNode()) {
            String base = ((Element) node).getAttributeNS(XMLConstants.XML_NS_URI, "base");
            if (!base.isBlank()) {
                bases.add(base);
            }
        }
        URI effective = catalogUri;
        for (int index = bases.size() - 1; index >= 0; index--) {
            effective = effective.resolve(bases.get(index));
        }
        return effective;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record Entry(String name, String reference, URI resolved) {
        public Entry {
            if (name == null || name.isBlank() || reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("catalog entry fields must not be blank");
            }
        }
    }

    public record Document(Path path, List<Entry> entries, List<URI> nextCatalogs,
            List<String> errors) {
        public Document {
            path = path.toAbsolutePath().normalize();
            entries = List.copyOf(entries);
            nextCatalogs = List.copyOf(nextCatalogs);
            errors = List.copyOf(errors);
        }
    }
}
