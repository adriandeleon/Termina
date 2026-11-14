package com.termina.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.termina.AppInfo;
import com.termina.PautaConformance;
import com.termina.PautaConformance.Conformance;
import com.termina.PautaConformance.Entry;
import com.termina.PautaConformance.Kind;
import com.termina.config.Settings;
import com.termina.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pauta window (PWIN-01..08, Pauta 6.0.1) and About's two Pauta links (ABOUT-08, PAUTA-04): the
 * edition and the conformance at the top, the exceptions grouped pending first, each rule linked
 * to its address in this edition and each entry to its issue or decision, a Copy as text button
 * that leaves the window open, and Escape to close.
 */
@Tag("fx")
class PautaWindowFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    private static final String ISSUE = "https://github.com/adriandeleon/Termina/issues/212";
    private static final String DECISION =
            "https://github.com/adriandeleon/Termina/blob/master/docs/decisions/0003-tabs.md";

    private static Entry entry(String rule, String level, Kind kind, String reason, String link) {
        return new Entry(rule, level, "https://pauta-project.dev/6.0.1/x/#" + rule.toLowerCase(), kind, reason, link);
    }

    private static final List<Entry> SAMPLE = List.of(
            entry("WIN-22", "MUST", Kind.NOT_APPLICABLE, "There is one kind of window.", null),
            entry("TABS-04", "SHOULD", Kind.DEVIATION, "Tabs keep their own order.", DECISION),
            entry("MENU-14", "SHOULD", Kind.PENDING, "Some menu rows have no icon yet.", ISSUE),
            entry("OS-04", "MUST", Kind.PENDING, "Three things still differ by system.", ISSUE));

    @Test
    void theWindowListsTheExceptionsByKindWithTheirLinksAndCopiesThemAsText() throws Exception {
        List<String> links = new CopyOnWriteArrayList<>();
        List<String> seen = onFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 300, 200));
            owner.show();
            PautaWindow window = new PautaWindow(links::add, SAMPLE);
            window.show(owner);
            window.scene().getRoot().applyCss();
            window.scene().getRoot().layout();
            List<String> out = new ArrayList<>();

            // The head: the edition, the conformance the helper computes, and what it means.
            out.add(String.join(
                    "|", texts(window.scene().getRoot(), Label.class).subList(0, 3)));
            // The list: a header per kind, then its rows, MUSTs first.
            List<String> rows = new ArrayList<>();
            for (Node child : window.listForTest().getChildren()) {
                if (child instanceof Label header) rows.add("[" + header.getText() + "]");
                else rows.add(String.join(" ", texts(child, Hyperlink.class)));
            }
            out.add(rows.toString());

            for (Hyperlink link : find(window.scene().getRoot(), Hyperlink.class)) link.fire();
            window.copyButtonForTest().fire();
            out.add(window.copyButtonForTest().getText());
            out.add(String.valueOf(window.scene().getWindow().isShowing()));
            out.add(Clipboard.getSystemClipboard().getString());

            window.scene()
                    .getRoot()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            out.add(String.valueOf(window.scene().getWindow().isShowing()));
            // Shown again, the button is back to its own label.
            window.show(owner);
            out.add(window.copyButtonForTest().getText());
            window.scene().getWindow().hide();
            owner.hide();
            return out;
        });

        assertEquals(
                "Pauta " + AppInfo.PAUTA_EDITION + "|Partial|" + AppInfo.NAME
                        + " does not meet every MUST yet: 1 is pending.",
                seen.get(0));
        assertEquals(
                "[Pending], OS-04 #212, MENU-14 #212, [Deviation], TABS-04 Decision 0003,"
                        + " [Not applicable], WIN-22",
                seen.get(1).substring(1, seen.get(1).length() - 1));
        assertEquals(Messages.tr("pauta.copied"), seen.get(2));
        assertEquals("true", seen.get(3), "copying leaves the window open");
        assertEquals(PautaWindow.markdown(SAMPLE), seen.get(4));
        assertEquals("false", seen.get(5), "Escape closes it");
        assertEquals(Messages.tr("pauta.copy"), seen.get(6));

        // Every rule goes to its own address, every entry to its issue or record, and the two
        // links under the list to the edition's rules and the user guide's page.
        for (Entry entry : SAMPLE) assertTrue(links.contains(entry.url()), entry.rule());
        assertTrue(links.contains(ISSUE) && links.contains(DECISION), links::toString);
        assertTrue(links.contains(AppInfo.pautaUrl()), links::toString);
        assertTrue(links.contains(PautaWindow.DOCS_PAGE), links::toString);
    }

    @Test
    void theTextCopiedIsMarkdownWithALinePerException() {
        String text = PautaWindow.markdown(SAMPLE);
        assertTrue(text.startsWith("# Pauta " + AppInfo.PAUTA_EDITION + ": Partial\n\n"), text);
        assertTrue(
                text.contains("\n## Pending\n\n- [OS-04](https://pauta-project.dev/6.0.1/x/#os-04) MUST: Three things"
                        + " still differ by system. [#212](" + ISSUE + ")\n- [MENU-14]("),
                text);
        assertTrue(text.contains("\n## Deviation\n\n- [TABS-04]("), text);
        assertTrue(text.contains("[Decision 0003](" + DECISION + ")"), text);
        assertTrue(
                text.contains("\n## Not applicable\n\n- [WIN-22](https://pauta-project.dev/6.0.1/x/#win-22) MUST:"
                        + " There is one kind of window.\n"),
                text);
        assertTrue(text.endsWith("\n" + AppInfo.pautaUrl() + "\n"), text);
    }

    @Test
    void theCountsLeaveOutAKindWithNoneAndNoExceptionsSaysSo() throws Exception {
        assertEquals("2 pending · 1 deviation · 1 not applicable", PautaWindow.counts(SAMPLE));
        assertEquals("", PautaWindow.counts(List.of()));
        List<Entry> two = List.of(
                entry("A-01", "SHOULD", Kind.DEVIATION, "One.", DECISION),
                entry("A-02", "SHOULD", Kind.DEVIATION, "Two.", DECISION));
        assertEquals("2 deviations", PautaWindow.counts(two));
        assertEquals(
                AppInfo.NAME + " meets every MUST and SHOULD of this edition.",
                PautaWindow.meaning(Conformance.COMPLETE, two));
        assertEquals(
                AppInfo.NAME + " meets every MUST of this edition; 2 SHOULDs are pending.",
                PautaWindow.meaning(
                        Conformance.CORE,
                        List.of(
                                entry("A-01", "SHOULD", Kind.PENDING, "One.", ISSUE),
                                entry("A-02", "SHOULD", Kind.PENDING, "Two.", ISSUE))));

        String none = onFx(() -> {
            PautaWindow window = new PautaWindow(link -> {}, List.of());
            return String.join("|", texts(window.listForTest(), Label.class));
        });
        assertEquals(Messages.tr("pauta.none", AppInfo.NAME, AppInfo.PAUTA_EDITION), none);
        assertTrue(PautaWindow.markdown(List.of()).contains(none));
    }

    @Test
    void aboutSaysWhetherTheAppIsBuiltToOrWorkingTowardItsEditionAndOpensThePautaWindow() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        List<String> links = new CopyOnWriteArrayList<>();
        List<String> opened = new CopyOnWriteArrayList<>();
        Conformance conformance = PautaConformance.conformance();
        String edition = Messages.tr(
                conformance == Conformance.PARTIAL ? "about.pautaWorkingToward" : "about.pauta", AppInfo.PAUTA_EDITION);
        String status = Messages.tr("about.pautaConformance", PautaWindow.conformanceName(conformance));

        List<String> found = onFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 300, 200));
            owner.show();
            AboutWindow about = new AboutWindow(settings, links::add, () -> opened.add("pauta"));
            about.show(owner);
            List<String> out = new ArrayList<>();
            for (Hyperlink link : find(about.scene().getRoot(), Hyperlink.class)) {
                if (edition.equals(link.getText()) || status.equals(link.getText())) {
                    out.add(link.getText());
                    link.fire();
                }
            }
            about.scene().getWindow().hide();
            owner.hide();
            return out;
        });

        // The edition first, and the conformance right after it (ABOUT-08).
        assertEquals(List.of(edition, status), found);
        assertEquals(List.of(AppInfo.pautaUrl()), links, "the edition goes to its rules");
        assertEquals(List.of("pauta"), opened, "the conformance opens the Pauta window");
        assertFalse(status.contains("{"), status);
    }

    private static <T extends Node> List<String> texts(Node root, Class<T> type) {
        List<String> out = new ArrayList<>();
        for (T node : find(root, type)) {
            String text = node instanceof Hyperlink link
                    ? link.getText()
                    : node instanceof Label label ? label.getText() : "";
            if (text != null && !text.isEmpty()) out.add(text);
        }
        return out;
    }

    private static <T extends Node> List<T> find(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) out.add(type.cast(node));
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), type, out);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, type, out);
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(20, TimeUnit.SECONDS);
    }
}
