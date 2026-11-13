package com.termina;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.termina.PautaConformance.Checked;
import com.termina.PautaConformance.Conformance;
import com.termina.PautaConformance.Document;
import com.termina.PautaConformance.Entry;
import com.termina.PautaConformance.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pauta's Conformance page (Pauta 6.0.1): the helper computes Complete, Core or Partial
 * (PAUTA-02); {@code pauta.json} agrees with the edition's {@code rules.json}, copied to
 * {@code src/test/resources/pauta-rules.json} (PAUTA-06, PAUTA-07, PAUTA-09, PAUTA-10), its
 * {@code checked} list included (PAUTA-18); nothing a template would leave is in it (PAUTA-16);
 * and the README's badge and line name the edition and the conformance the helper returns
 * (PAUTA-15). The app template's test of the same name.
 */
class PautaConformanceTest {

    private static Entry entry(String rule, String level, Kind kind) {
        String link = kind == Kind.NOT_APPLICABLE || kind == Kind.APP_ONLY ? null : "https://github.com/x/y/issues/1";
        return new Entry(rule, level, "https://pauta-project.dev/6.0.1/x/#" + rule.toLowerCase(), kind, "R.", link);
    }

    // ---- the helper (PAUTA-02) ----

    @Test
    void noExceptionsIsComplete() {
        assertEquals(Conformance.COMPLETE, PautaConformance.of(List.of()));
    }

    @Test
    void aPendingMustIsPartialAndAPendingShouldIsCore() {
        assertEquals(Conformance.PARTIAL, PautaConformance.of(List.of(entry("A-01", "MUST", Kind.PENDING))));
        assertEquals(Conformance.CORE, PautaConformance.of(List.of(entry("A-02", "SHOULD", Kind.PENDING))));
        assertEquals(
                Conformance.PARTIAL,
                PautaConformance.of(
                        List.of(entry("A-02", "SHOULD", Kind.PENDING), entry("A-01", "MUST", Kind.PENDING))));
    }

    @Test
    void deviationsAppOnlyAndNotApplicableEntriesAndMaysLeaveTheConformanceAlone() {
        List<Entry> none = List.of(
                entry("A-01", "MUST", Kind.NOT_APPLICABLE),
                entry("A-02", "SHOULD", Kind.NOT_APPLICABLE),
                entry("A-03", "SHOULD", Kind.DEVIATION),
                entry("A-04", "MAY", Kind.PENDING),
                entry("A-07", "MUST", Kind.APP_ONLY),
                entry("A-08", "SHOULD", Kind.APP_ONLY));
        assertEquals(Conformance.COMPLETE, PautaConformance.of(none));
        List<Entry> core = new ArrayList<>(none);
        core.add(entry("A-05", "SHOULD", Kind.PENDING));
        assertEquals(Conformance.CORE, PautaConformance.of(core));
    }

    @Test
    void theNamesAreTheWordsTheReadmeAndTheEnvironmentWrite() {
        assertEquals("Complete", Conformance.COMPLETE.english());
        assertEquals("Core", Conformance.CORE.english());
        assertEquals("partial", Conformance.PARTIAL.key());
        assertEquals("notApplicable", Kind.NOT_APPLICABLE.key());
        assertEquals("not-applicable", Kind.NOT_APPLICABLE.json());
        assertEquals("appOnly", Kind.APP_ONLY.key());
        assertEquals("app-only", Kind.APP_ONLY.json());
        assertThrows(IllegalArgumentException.class, () -> Kind.parse("waived"));
    }

    @Test
    void pendingFirstThenDeviationsThenAppOnlyThenNotApplicableMustsBeforeShouldsThenById() {
        List<Entry> sorted = PautaConformance.sorted(List.of(
                entry("Z-01", "MUST", Kind.NOT_APPLICABLE),
                entry("Y-07", "SHOULD", Kind.APP_ONLY),
                entry("Y-08", "MUST", Kind.APP_ONLY),
                entry("B-02", "SHOULD", Kind.DEVIATION),
                entry("C-03", "SHOULD", Kind.PENDING),
                entry("D-04", "MUST", Kind.PENDING),
                entry("A-05", "MUST", Kind.PENDING),
                entry("A-06", "MAY", Kind.PENDING)));
        assertEquals(
                List.of("A-05", "D-04", "C-03", "A-06", "B-02", "Y-08", "Y-07", "Z-01"),
                sorted.stream().map(Entry::rule).toList());
    }

    @Test
    void countsAndTheEnvironmentLine() {
        List<Entry> list = List.of(
                entry("A-01", "MUST", Kind.PENDING),
                entry("A-02", "SHOULD", Kind.PENDING),
                entry("A-03", "SHOULD", Kind.DEVIATION));
        assertEquals(Map.of(Kind.PENDING, 2, Kind.DEVIATION, 1), PautaConformance.counts(list));
        assertEquals(1, PautaConformance.pending(list, "MUST"));
        assertEquals(1, PautaConformance.pending(list, "SHOULD"));
        assertEquals("Pauta conformance: Partial (3 exceptions)", PautaConformance.environmentLine(list));
        assertEquals(
                "Pauta conformance: Core (1 exception)",
                PautaConformance.environmentLine(List.of(entry("A-02", "SHOULD", Kind.PENDING))));
        assertEquals("Pauta conformance: Complete (no exceptions)", PautaConformance.environmentLine(List.of()));
    }

    @Test
    void issueAndDecisionNumbers() {
        assertEquals("#212", PautaConformance.issueNumber("https://github.com/a/b/issues/212"));
        assertNull(PautaConformance.issueNumber("https://github.com/a/b/blob/master/docs/decisions/0007-x.md"));
        assertEquals(
                "0007", PautaConformance.decisionNumber("https://github.com/a/b/blob/master/docs/decisions/0007-x.md"));
        assertNull(PautaConformance.decisionNumber("https://github.com/a/b/issues/212"));
        assertNull(PautaConformance.decisionNumber("https://github.com/a/b/blob/master/docs/decisions/README.md"));
    }

    @Test
    void parsesEveryField() {
        List<Entry> parsed = PautaConformance.parse("""
                {"exceptions": [
                  {"rule": "A-01", "level": "MUST", "url": "u", "kind": "pending", "reason": "R.", "link": "l"},
                  {"rule": "A-02", "level": "SHOULD", "url": "v", "kind": "not-applicable", "reason": "S."}
                ]}""").exceptions();
        assertEquals(new Entry("A-01", "MUST", "u", Kind.PENDING, "R.", "l"), parsed.get(0));
        assertNull(parsed.get(1).link());
        assertEquals(Kind.NOT_APPLICABLE, parsed.get(1).kind());
        assertTrue(PautaConformance.parse("{}").exceptions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PautaConformance.parse("[]"));
        assertThrows(
                IllegalArgumentException.class, () -> PautaConformance.parse("{\"exceptions\": [{\"kind\": \"x\"}]}"));
    }

    @Test
    void readsTheTemplateFlagAndTheCheckedList() {
        Document document = PautaConformance.parse("""
                {"template": true, "exceptions": [], "other": {"skipped": [1, 2]}, "checked": [
                  {"rule": "SET-02", "edition": "6.0.1", "date": "2026-11-10"},
                  {"rule": "ACC-07", "edition": "6.0.1", "date": "2026-11-10", "version": "1.2.0"}
                ]}""");
        assertTrue(document.template());
        assertFalse(PautaConformance.parse("{\"template\": false}").template());
        assertFalse(PautaConformance.parse("{}").template());
        assertEquals(
                List.of(
                        new Checked("SET-02", "6.0.1", "2026-11-10", null),
                        new Checked("ACC-07", "6.0.1", "2026-11-10", "1.2.0")),
                document.checked());
        assertTrue(PautaConformance.parse("{}").checked().isEmpty());
    }

    @Test
    void editionsCompareByNumberNotByText() {
        assertTrue(compare("4.10.0", "4.9.0") > 0);
        assertTrue(compare("5.0.0", "6.0.0") < 0);
        assertEquals(0, compare("6.0.1", "6.0.1"));
    }

    // ---- pauta.json against the edition (PAUTA-09, PAUTA-10) ----

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8);
    }

    private static String pomEdition() throws IOException {
        Matcher m =
                Pattern.compile("<pauta\\.version>([^<]+)</pauta\\.version>").matcher(read("pom.xml"));
        assertTrue(m.find(), "pom.xml defines pauta.version");
        return m.group(1).strip();
    }

    /** One rule of the edition, as far as this test reads it. */
    private record Rule(String id, String level, String url, String changed, String checkKind) {}

    /** The copy of the edition's rules: its {@code edition} and every rule, by ID. */
    private record Edition(String edition, Map<String, Rule> rules) {}

    private static Edition edition() throws IOException {
        String edition = "";
        Map<String, Rule> rules = new HashMap<>();
        try (JsonParser parser = new JsonFactory()
                .createParser(Path.of("src/test/resources/pauta-rules.json").toFile())) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("edition".equals(field)) {
                    edition = parser.getText();
                } else if ("rules".equals(field) && value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        Map<String, String> rule = new HashMap<>();
                        while (parser.nextToken() == JsonToken.FIELD_NAME) {
                            String name = parser.currentName();
                            JsonToken token = parser.nextToken();
                            if ("check".equals(name) && token == JsonToken.START_OBJECT) {
                                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                                    String inner = parser.currentName();
                                    JsonToken innerToken = parser.nextToken();
                                    if ("kind".equals(inner)) rule.put("check.kind", parser.getText());
                                    else if (innerToken.isStructStart()) parser.skipChildren();
                                }
                            } else if (token.isStructStart()) {
                                parser.skipChildren();
                            } else if (token != JsonToken.VALUE_NULL) {
                                rule.put(name, parser.getText());
                            }
                        }
                        String id = rule.getOrDefault("id", "");
                        rules.put(
                                id,
                                new Rule(
                                        id,
                                        rule.getOrDefault("level", ""),
                                        rule.getOrDefault("url", ""),
                                        rule.getOrDefault("changed", ""),
                                        rule.getOrDefault("check.kind", "")));
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        return new Edition(edition, rules);
    }

    private static Document file() throws IOException {
        return PautaConformance.parse(read("src/main/resources/com/termina/pauta.json"));
    }

    @Test
    void theRulesCopyIsTheEditionThePomNames() throws IOException {
        assertEquals(pomEdition(), edition().edition(), "replace pauta-rules.json with this edition's");
        assertTrue(edition().rules().size() > 100);
        assertEquals(pomEdition(), AppInfo.PAUTA_EDITION);
    }

    @Test
    void theBundledFileIsTheOneTheAppReads() throws IOException {
        assertEquals(file().exceptions(), PautaConformance.exceptions());
        assertEquals(file().checked(), PautaConformance.checked());
        assertFalse(PautaConformance.isTemplate(), "Termina is no template");
        assertEquals(PautaConformance.of(file().exceptions()), PautaConformance.conformance());
    }

    @Test
    void everyEntryNamesARuleOfTheEditionWithItsLevelAndAddress() throws IOException {
        Map<String, Rule> rules = edition().rules();
        Set<String> seen = new HashSet<>();
        for (Entry entry : file().exceptions()) {
            String id = entry.rule();
            assertFalse(id.isBlank(), "an entry has no rule");
            assertFalse(entry.level().isBlank(), id + " has no level");
            assertFalse(entry.url().isBlank(), id + " has no url");
            assertFalse(entry.reason().isBlank(), id + " has no reason");
            Rule rule = rules.get(id);
            assertNotNull(rule, id + " is not a rule of Pauta " + pomEdition());
            assertTrue(seen.add(id), id + " appears twice");
            assertEquals(rule.level(), entry.level(), id + "'s level");
            assertEquals(rule.url(), entry.url(), id + "'s url");
            // MAYs never count, so none needs an entry.
            assertTrue(entry.isMust() || entry.isShould(), id + " is neither a MUST nor a SHOULD");
        }
    }

    @Test
    void eachKindHasWhatItNeeds() throws IOException {
        String repo = Pattern.quote(AppInfo.GITHUB_REPO);
        Pattern issue = Pattern.compile("https://github\\.com/" + repo + "/issues/\\d+");
        Pattern decision = Pattern.compile("https://github\\.com/" + repo + "/blob/master/(docs/decisions/[^/]+\\.md)");
        for (Entry e : file().exceptions()) {
            String link = e.link();
            switch (e.kind()) {
                case NOT_APPLICABLE -> assertNull(link, e.rule() + ": a not-applicable entry has no link");
                // PAUTA-16: nothing but a template may record one; Termina was not made from one.
                case APP_ONLY -> throw new AssertionError(e.rule() + ": app-only is a template's kind");
                case PENDING -> {
                    assertNotNull(link, e.rule() + ": a pending entry links its issue (PAUTA-07)");
                    assertTrue(issue.matcher(link).matches(), e.rule() + ": not an issue in this repository: " + link);
                }
                case DEVIATION -> {
                    assertEquals("SHOULD", e.level(), e.rule() + ": only a SHOULD can be a deviation (PAUTA-06)");
                    assertNotNull(link, e.rule() + ": a deviation links its decision record");
                    Matcher m = decision.matcher(link);
                    assertTrue(m.matches(), e.rule() + ": not a decision record: " + link);
                    Path record = Path.of(m.group(1));
                    assertTrue(Files.exists(record), record + " is missing");
                    assertTrue(read(record.toString()).contains(e.rule()), record + " does not name " + e.rule());
                }
            }
            // PAUTA-08: one plain sentence for the user, without rule text or IDs.
            assertTrue(e.reason().endsWith("."), e.rule() + ": the reason is a sentence");
            assertFalse(e.reason().matches(".*\\b[A-Z]{2,6}-\\d{2,3}\\b.*"), e.rule() + ": the reason names a rule");
            assertFalse(e.reason().matches(".*[.!?] [A-Z].*"), e.rule() + ": the reason is more than one sentence");
        }
    }

    // ---- the checked list (PAUTA-18) ----

    @Test
    void eachCheckedEntryIsWellFormedAndCurrent() throws IOException {
        Map<String, Rule> rules = edition().rules();
        Set<String> excepted = new HashSet<>();
        for (Entry e : file().exceptions()) excepted.add(e.rule());
        String pom = pomEdition();
        Set<String> seen = new HashSet<>();
        for (Checked checked : file().checked()) {
            String id = checked.rule();
            assertFalse(id.isBlank(), "a checked entry has no rule");
            assertFalse(checked.edition().isBlank(), "checked " + id + " has no edition");
            assertTrue(checked.date().matches("\\d{4}-\\d{2}-\\d{2}"), "checked " + id + "'s date");
            Rule rule = rules.get(id);
            assertNotNull(rule, "checked " + id + " is not a rule of Pauta " + pom);
            assertEquals("by hand", rule.checkKind(), "checked " + id + ": only a rule checked by hand is recorded");
            assertTrue(seen.add(id), "checked " + id + " appears twice");
            assertFalse(excepted.contains(id), id + " is both checked and an exception");
            assertTrue(
                    compare(checked.edition(), pom) <= 0,
                    "checked " + id + ": " + checked.edition() + " is newer than " + pom);
            String changed = rule.changed().isBlank() ? pom : rule.changed();
            assertTrue(
                    compare(checked.edition(), changed) >= 0,
                    "checked " + id + " is stale: checked against " + checked.edition() + ", the rule changed in "
                            + changed + "; remove it and check the rule again");
        }
    }

    /** Compares two editions number by number: 4.10.0 is newer than 4.9.0. */
    private static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    // ---- the README (PAUTA-15) and the environment block (ABOUT-09) ----

    @Test
    void theReadmeNamesTheEditionAndTheConformanceAndLinksTheDocsPage() throws IOException {
        String edition = pomEdition();
        Conformance conformance = PautaConformance.conformance();
        String readme = read("README.md");
        String docs = AppInfo.HOMEPAGE + "/docs/pauta/";

        Matcher badge = Pattern.compile("<a href=\"([^\"]+)\"><img alt=\"[^\"]*\""
                        + " src=\"https://img\\.shields\\.io/badge/Pauta-([0-9.]+)%20%C2%B7%20(\\w+)-(\\w+)\"></a>")
                .matcher(readme);
        assertTrue(badge.find(), "README has the Pauta badge");
        assertEquals(docs, badge.group(1), "the badge links the conformance page");
        assertEquals(edition, badge.group(2), "the badge's edition");
        assertEquals(conformance.english(), badge.group(3), "the badge's conformance");
        // Green for Complete, blue for Core, amber for Partial (Pauta's README badges).
        String color = switch (conformance) {
            case COMPLETE -> "2ea043";
            case CORE -> "5b8def";
            case PARTIAL -> "d29922";
        };
        assertEquals(color, badge.group(4), "the badge's color");

        String opening = conformance == Conformance.PARTIAL ? "Working toward" : "Built to";
        String other = conformance == Conformance.PARTIAL ? "Built to [Pauta" : "Working toward [Pauta";
        String line = opening + " [Pauta (the adl desktop apps guidelines)](https://pauta-project.dev/), edition ["
                + edition + "](https://pauta-project.dev/" + edition + "/), [Pauta conformance: "
                + conformance.english() + "](" + docs + ").";
        assertTrue(readme.contains(line), "README's line: " + line);
        assertFalse(readme.contains(other), "README still says " + other);
    }

    @Test
    void theEnvironmentBlockSaysTheConformanceRightAfterTheEdition() {
        List<String> details = AppInfo.details();
        int pauta = details.indexOf("Pauta: " + AppInfo.PAUTA_EDITION);
        assertTrue(pauta > 0, details.toString());
        assertEquals(PautaConformance.environmentLine(PautaConformance.exceptions()), details.get(pauta + 1));
        assertTrue(details.get(pauta + 1).startsWith("Pauta conformance: "), details.get(pauta + 1));
    }
}
