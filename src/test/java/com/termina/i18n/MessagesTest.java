package com.termina.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The string catalogues. */
class MessagesTest {

    private static Properties catalogue(String lang) throws Exception {
        String name = "en".equals(lang)
                ? "/com/termina/i18n/messages.properties"
                : "/com/termina/i18n/messages_" + lang + ".properties";
        Properties p = new Properties();
        try (InputStream in = MessagesTest.class.getResourceAsStream(name)) {
            assertTrue(in != null, "missing catalogue for " + lang);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyLanguageHasExactlyTheKeysEnglishHas() throws Exception {
        // Drift is the failure mode, and it is silent: a key added to English and forgotten
        // elsewhere shows English to those users, which nobody testing in English will ever see.
        Set<String> english = new TreeSet<>(catalogue("en").stringPropertyNames());
        assertFalse(english.isEmpty());
        for (String lang : Messages.available().keySet()) {
            if ("en".equals(lang)) continue;
            Set<String> theirs = new TreeSet<>(catalogue(lang).stringPropertyNames());
            Set<String> missing = new TreeSet<>(english);
            missing.removeAll(theirs);
            Set<String> extra = new TreeSet<>(theirs);
            extra.removeAll(english);
            assertEquals(Set.of(), missing, lang + " is missing keys");
            assertEquals(Set.of(), extra, lang + " has keys English does not");
        }
    }

    @Test
    void everyApostropheIsWrittenTheWayItsValueIsRead() {
        // A value with a placeholder is a MessageFormat pattern and wants '' for an apostrophe; one
        // without is plain text and shows '' as two. French showed "n''est" in four places because
        // the rule used to depend on how a caller looked the key up rather than on the value.
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (String lang : Messages.available().keySet()) {
            Messages.bundle(lang).forEach((key, value) -> {
                if (Messages.isPattern(value)) {
                    try {
                        new MessageFormat(value);
                    } catch (IllegalArgumentException e) {
                        wrong.add(lang + " " + key + ": not a valid pattern");
                    }
                    // A lone quote starts a quoted run and eats what follows it.
                    if (value.replace("''", "").contains("'")) wrong.add(lang + " " + key + ": lone ' in a pattern");
                } else if (value.contains("''")) {
                    wrong.add(lang + " " + key + ": '' in plain text");
                }
            });
        }
        assertEquals(java.util.List.of(), wrong);
    }

    @Test
    void plainTextIsShownAsWrittenAndAPatternIsFormattedEitherWay() {
        assertEquals("C'est {file}", Messages.render("C'est {file}", new Object[] {"x"}));
        assertEquals("C'est x", Messages.render("C''est {0}", new Object[] {"x"}));
        assertEquals("C'est {0}", Messages.render("C''est {0}", null));
    }

    @Test
    void aPlaceholderSurvivesEveryTranslation() throws Exception {
        // An apostrophe in a MessageFormat pattern quotes what follows, so a French or Italian
        // translation of a parameterised string eats its own {0} unless the quote is doubled. The
        // symptom is a version number that simply never appears.
        Properties english = catalogue("en");
        for (String lang : Messages.available().keySet()) {
            Properties theirs = catalogue(lang);
            for (String key : english.stringPropertyNames()) {
                if (!english.getProperty(key).contains("{0}")) continue;
                String formatted = MessageFormat.format(theirs.getProperty(key), "XYZZY");
                assertTrue(formatted.contains("XYZZY"), lang + "/" + key + " lost its argument: " + formatted);
            }
        }
    }

    @Test
    void anUnknownKeyShowsItselfRatherThanThrowing() {
        Messages.init("en");
        assertEquals("no.such.key", Messages.tr("no.such.key"));
    }

    @Test
    void anUnbundledLanguageFallsBackToEnglish() {
        assertEquals("en", Messages.resolve("kl", Messages.available().keySet(), "kl"));
        assertEquals("de", Messages.resolve(null, Messages.available().keySet(), "de"));
        assertEquals("fr", Messages.resolve("fr", Messages.available().keySet(), "de"));
    }

    @Test
    void automaticKeepsTheOperatingSystemsLocaleWhole() {
        // The country decides dates and numbers; de_AT reduced to de loses it, and a Klingon OS
        // that falls back to English menus is no reason to also change how its dates are written.
        java.util.Locale austria = java.util.Locale.forLanguageTag("de-AT");
        assertEquals(austria, Messages.defaultLocale("", "de", austria));
        java.util.Locale klingon = java.util.Locale.forLanguageTag("tlh-US");
        assertEquals(klingon, Messages.defaultLocale(null, "en", klingon));
    }

    @Test
    void anExplicitChoiceMovesTheLocaleButKeepsAMatchingCountry() {
        java.util.Locale austria = java.util.Locale.forLanguageTag("de-AT");
        assertEquals(java.util.Locale.forLanguageTag("fr"), Messages.defaultLocale("fr", "fr", austria));
        assertEquals(austria, Messages.defaultLocale("de", "de", austria), "same language: the OS country stays");
    }

    @Test
    void aTranslationIsActuallyUsed() {
        Messages.init("es");
        assertEquals("Copiar", Messages.tr("menu.copy"));
        Messages.init("en");
        assertEquals("Copy", Messages.tr("menu.copy"));
    }
}
