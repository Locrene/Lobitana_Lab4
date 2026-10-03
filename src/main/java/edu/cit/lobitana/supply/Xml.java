package edu.cit.lobitana.supply;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Minimal XML support for the LegacySupply protocol.
 *
 * The documents exchanged are tiny and fixed, so requests are written as text and responses are read
 * with the JDK DOM parser. No XML binding library, nothing to misconfigure.
 */
final class Xml {

    private Xml() {
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return document;
        } catch (Exception ex) {
            throw new IllegalArgumentException("cannot parse XML response: " + abbreviate(xml), ex);
        }
    }

    /** First element with this tag name anywhere under the parent, or null. */
    static Element element(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    static List<Element> elements(Element parent, String tag) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                found.add(element);
            }
        }
        return found;
    }

    static String text(Element parent, String tag) {
        Element element = element(parent, tag);
        return element == null ? null : element.getTextContent().trim();
    }

    static int intText(Element parent, String tag, int fallback) {
        String value = text(parent, tag);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Escape text going into an element body. */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
