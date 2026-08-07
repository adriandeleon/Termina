package com.termina.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

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
    void aTranslationIsActuallyUsed() {
        Messages.init("es");
        assertEquals("Copiar", Messages.tr("menu.copy"));
        Messages.init("en");
        assertEquals("Copy", Messages.tr("menu.copy"));
    }
}
