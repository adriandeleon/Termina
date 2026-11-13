package com.termina;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.jspecify.annotations.Nullable;

/**
 * How fully Termina conforms to its Pauta edition (PAUTA-02, Pauta 6.0.1): computed from the
 * exceptions in {@code pauta.json}, never stored. About, the Pauta window and {@code --version}
 * take it from here; the README is held to it by {@code PautaConformanceTest}.
 *
 * <p>Partial when a MUST is pending, Core when a SHOULD is pending, Complete otherwise. A
 * deviation, an app-only or a not-applicable entry leaves the result alone, and so does a MAY:
 * MAYs never count. The {@code checked} list (PAUTA-18) is a record and never enters it.
 *
 * <p>The app template's class of the same name, read through jackson-core's streaming parser
 * like Termina's settings: one small file does not need a data-binding library.
 */
public final class PautaConformance {

    /** The bundled exceptions, next to {@code build-info.properties}. */
    static final String RESOURCE = "pauta.json";

    /** Complete, Core or Partial. Their names live in the catalogs ({@code pauta.conformance.<key>}). */
    public enum Conformance {
        COMPLETE,
        CORE,
        PARTIAL;

        /** The catalog key part and the README word's lower case: {@code complete}, {@code core}, {@code partial}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The English word, as the README badge and the environment block write it. */
        public String english() {
            return name().charAt(0) + key().substring(1);
        }
    }

    /**
     * The four kinds of exception, in the order the Pauta window lists them (PWIN-05).
     * {@code app-only} is a template's alone (PAUTA-16); Termina has none, and its test says so.
     */
    public enum Kind {
        PENDING("pending", "pending"),
        DEVIATION("deviation", "deviation"),
        APP_ONLY("app-only", "appOnly"),
        NOT_APPLICABLE("not-applicable", "notApplicable");

        private final String json;
        private final String key;

        Kind(String json, String key) {
            this.json = json;
            this.key = key;
        }

        /** How {@code pauta.json} spells it. */
        public String json() {
            return json;
        }

        /** The catalog key part: {@code pauta.kind.<key>}. */
        public String key() {
            return key;
        }

        static Kind parse(String value) {
            for (Kind kind : values()) if (kind.json.equals(value)) return kind;
            throw new IllegalArgumentException("Unknown exception kind: " + value);
        }
    }

    /**
     * One rule the app does not meet as written: the rule's ID, and its level and URL copied from
     * the edition's {@code rules.json}; the kind; one plain sentence for the user; for a deviation
     * its decision record and for a pending entry its issue.
     */
    public record Entry(
            String rule,
            String level,
            String url,
            Kind kind,
            String reason,
            @Nullable String link) {

        boolean isMust() {
            return "MUST".equals(level);
        }

        boolean isShould() {
            return "SHOULD".equals(level);
        }
    }

    /**
     * One rule a person checked by hand in the running app and found met (PAUTA-18): the rule, the
     * edition it was checked against, the date and, for a rule checked again with each release,
     * the app's version. A record only: it never changes the conformance.
     */
    public record Checked(
            String rule,
            String edition,
            String date,
            @Nullable String version) {}

    /** The file as read: its two lists, and whether it says it is a template's. */
    record Document(List<Entry> exceptions, List<Checked> checked, boolean template) {}

    private static final JsonFactory JSON = new JsonFactory();

    private static volatile @Nullable Document bundled;

    private PautaConformance() {}

    /** The bundled {@code pauta.json}, read and parsed once. */
    private static Document file() {
        Document document = bundled;
        if (document == null) {
            synchronized (PautaConformance.class) {
                document = bundled;
                if (document == null) {
                    try (InputStream in = PautaConformance.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new IllegalStateException(RESOURCE + " is not bundled");
                        document = parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    bundled = document;
                }
            }
        }
        return document;
    }

    /**
     * The app's own exceptions, from the bundled {@code pauta.json}. Read once; {@link #warm} does
     * it at start-up, off the FX thread, so About and the Pauta window read no file (PERF-01).
     */
    public static List<Entry> exceptions() {
        return file().exceptions();
    }

    /** Reads the bundled file now, on the caller's thread: a worker's, at start-up. */
    public static void warm() {
        file();
    }

    /** The by-hand checks the app records (PAUTA-18); empty when it records none. */
    public static List<Checked> checked() {
        return file().checked();
    }

    /** Whether this is a template repository (PAUTA-16); Termina's file has no such field. */
    public static boolean isTemplate() {
        return file().template();
    }

    /** The app's own conformance. */
    public static Conformance conformance() {
        return of(exceptions());
    }

    /** Reads a {@code pauta.json}: its {@code exceptions} and {@code checked} arrays and {@code template}. */
    static Document parse(String text) {
        List<Entry> entries = new ArrayList<>();
        List<Checked> checked = new ArrayList<>();
        boolean template = false;
        try (JsonParser parser = JSON.createParser(text)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("not a JSON object");
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("exceptions".equals(field) && value == JsonToken.START_ARRAY) {
                    for (Map<String, String> object : objects(parser)) {
                        entries.add(new Entry(
                                object.getOrDefault("rule", ""),
                                object.getOrDefault("level", ""),
                                object.getOrDefault("url", ""),
                                Kind.parse(object.getOrDefault("kind", "")),
                                object.getOrDefault("reason", ""),
                                object.get("link")));
                    }
                } else if ("checked".equals(field) && value == JsonToken.START_ARRAY) {
                    for (Map<String, String> object : objects(parser)) {
                        checked.add(new Checked(
                                object.getOrDefault("rule", ""),
                                object.getOrDefault("edition", ""),
                                object.getOrDefault("date", ""),
                                object.get("version")));
                    }
                } else if ("template".equals(field)) {
                    template = value == JsonToken.VALUE_TRUE;
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("not a pauta.json: " + e.getMessage(), e);
        }
        return new Document(List.copyOf(entries), List.copyOf(checked), template);
    }

    /** The flat objects of the array the parser has just entered, each as field to text; nulls left out. */
    private static List<Map<String, String>> objects(JsonParser parser) throws IOException {
        List<Map<String, String>> out = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            Map<String, String> object = new LinkedHashMap<>();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken token = parser.nextToken();
                if (token == JsonToken.VALUE_NULL) continue;
                if (token.isStructStart()) {
                    parser.skipChildren();
                    continue;
                }
                object.put(name, parser.getText());
            }
            out.add(object);
        }
        return out;
    }

    /** Partial when any MUST is pending, Core when any SHOULD is pending, Complete otherwise. */
    public static Conformance of(List<Entry> exceptions) {
        boolean should = false;
        for (Entry entry : exceptions) {
            if (entry.kind() != Kind.PENDING) continue;
            if (entry.isMust()) return Conformance.PARTIAL;
            if (entry.isShould()) should = true;
        }
        return should ? Conformance.CORE : Conformance.COMPLETE;
    }

    /** How many pending exceptions have this level: the number Core's and Partial's sentences give. */
    public static long pending(List<Entry> exceptions, String level) {
        return exceptions.stream()
                .filter(e -> e.kind() == Kind.PENDING && e.level().equals(level))
                .count();
    }

    /** How many exceptions of each kind, in list order; a kind with none is left out. */
    public static Map<Kind, Integer> counts(List<Entry> exceptions) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Entry entry : exceptions) counts.merge(entry.kind(), 1, Integer::sum);
        return counts;
    }

    /**
     * Pending first, then deviations, then app-only, then not applicable; within a kind MUSTs
     * before SHOULDs, then by ID (PWIN-05).
     */
    public static List<Entry> sorted(List<Entry> exceptions) {
        return exceptions.stream()
                .sorted(Comparator.comparing(Entry::kind)
                        .thenComparingInt(PautaConformance::levelRank)
                        .thenComparing(Entry::rule))
                .toList();
    }

    private static int levelRank(Entry entry) {
        return switch (entry.level()) {
            case "MUST" -> 0;
            case "SHOULD" -> 1;
            default -> 2;
        };
    }

    /**
     * The environment block's line (ABOUT-09): {@code Pauta conformance: Core (2 exceptions)}.
     * Plain English on purpose, like the rest of the block: it goes into bug reports.
     */
    public static String environmentLine(List<Entry> exceptions) {
        int n = exceptions.size();
        String count = n == 0 ? "no exceptions" : n == 1 ? "1 exception" : n + " exceptions";
        return "Pauta conformance: " + of(exceptions).english() + " (" + count + ")";
    }

    /** A pending entry's issue number, {@code #212}, or {@code null} when the link is not an issue. */
    public static @Nullable String issueNumber(String link) {
        int at = link.indexOf("/issues/");
        return at < 0 ? null : "#" + link.substring(at + "/issues/".length());
    }

    /** A deviation's decision record number, {@code 0007}, or {@code null} when the link is not one. */
    public static @Nullable String decisionNumber(String link) {
        int at = link.indexOf("/docs/decisions/");
        if (at < 0) return null;
        String file = link.substring(at + "/docs/decisions/".length());
        int dash = file.indexOf('-');
        return dash > 0 ? file.substring(0, dash) : null;
    }
}
