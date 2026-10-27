package com.termina.theme;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * An XML property list — what {@code .itermcolors} files are, and the outside of a {@code .terminal}
 * profile — read into maps, lists and values.
 *
 * <p>The file comes from wherever the user downloaded it, so the parser is locked down: the DOCTYPE
 * every plist carries is allowed, but no DTD or entity is ever fetched or expanded. An XML file that
 * tried to read {@code /etc/passwd} into a colour gets a parse error instead.
 */
final class PlistXml {

    private PlistXml() {}

    /** The top-level value: usually a {@code Map<String, Object>}. */
    static Object parse(byte[] xml) throws ThemeImportException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Element root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            if (!root.getTagName().equals("plist"))
                throw new ThemeImportException(ThemeImportException.Reason.NOT_FORMAT, "property list");
            Element first = firstElement(root);
            if (first == null) throw new ThemeImportException(ThemeImportException.Reason.EMPTY);
            return value(first);
        } catch (ThemeImportException e) {
            throw e;
        } catch (Exception e) {
            throw new ThemeImportException(e, ThemeImportException.Reason.DAMAGED, "property list");
        }
    }

    private static Object value(Element element) throws ThemeImportException {
        String text = element.getTextContent().strip();
        return switch (element.getTagName()) {
            case "dict" -> dict(element);
            case "array" -> array(element);
            case "string" -> element.getTextContent();
            case "real" -> Double.parseDouble(text);
            case "integer" -> Long.parseLong(text);
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "data" -> Base64.getMimeDecoder().decode(text);
            case "date" -> text;
            default -> throw new ThemeImportException(ThemeImportException.Reason.DAMAGED, "property list");
        };
    }

    private static Map<String, Object> dict(Element dict) throws ThemeImportException {
        Map<String, Object> out = new LinkedHashMap<>();
        String key = null;
        for (Node node = dict.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) continue;
            if (element.getTagName().equals("key")) {
                key = element.getTextContent();
            } else if (key != null) {
                out.put(key, value(element));
                key = null;
            }
        }
        return out;
    }

    private static List<Object> array(Element array) throws ThemeImportException {
        List<Object> out = new ArrayList<>();
        for (Node node = array.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) out.add(value(element));
        }
        return out;
    }

    private static @Nullable Element firstElement(Element parent) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) return element;
        }
        return null;
    }
}
