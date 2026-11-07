package com.termina.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a problem report must not carry into a public issue (DIAG-06, SET-15, Pauta 2.0.2). */
class RedactorTest {

    private final Redactor redactor = new Redactor("/home/jdoe", "jdoe");

    @Test
    void theHomeFolderBecomesATilde() {
        assertEquals("~/.termina/settings.json", redactor.redact("/home/jdoe/.termina/settings.json"));
        assertEquals("~\\AppData\\x", new Redactor("C:\\Users\\jdoe", "jdoe").redact("C:\\Users\\jdoe\\AppData\\x"));
    }

    @Test
    void theLoginNameGoesWhereverItIsAWord() {
        assertEquals("shell of <user> running", redactor.redact("shell of jdoe running"));
        assertEquals("jdoes-tool", redactor.redact("jdoes-tool"), "only as a whole word");
    }

    @Test
    void addressesAndHostsGo() {
        assertEquals("mail <address> now", redactor.redact("mail someone@example.com now"));
        assertEquals("ssh <address>", redactor.redact("ssh deploy@build-01"));
    }

    @Test
    void credentialsInAUrlGoAndTheHostStays() {
        assertEquals(
                "https://<credentials>@github.com/x/y.git",
                redactor.redact("https://bob:ghp_abcdefghijklmnopqrstuvwxyz0123@github.com/x/y.git"));
    }

    @Test
    void tokensGo() {
        String text = redactor.redact("using ghp_abcdefghijklmnopqrstuvwxyz0123 and AKIAABCDEFGHIJKLMNOP");
        assertFalse(text.contains("ghp_"), text);
        assertFalse(text.contains("AKIA"), text);
        assertEquals("Authorization: <redacted>", redactor.redact("Authorization: Bearer"));
        assertEquals("token=<redacted>", redactor.redact("token=s3cr3t"));
        assertTrue(redactor.redact("password: hunter2").endsWith("<redacted>"));
    }

    @Test
    void aStackFramesModuleAndVersionStay() {
        String frame = "\tat com.termina@0.15.0-SNAPSHOT/com.termina.App.main(App.java:40)";
        assertEquals(frame, redactor.redact(frame));
        assertEquals("\tat javafx.graphics@27.0.1/x", redactor.redact("\tat javafx.graphics@27.0.1/x"));
        assertEquals("ssh <address>", redactor.redact("ssh admin@10.0.0.5"), "an address by number is still a host");
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        String text = "Termina 0.15.0\nJava: 25.0.1+9-LTS\nfont.size=13";
        assertEquals(text, redactor.redact(text));
    }
}
