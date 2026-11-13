package com.termina.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every context-menu and settings-sidebar glyph actually draws. */
@Tag("fx")
class MenuIconsTest {

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    @Test
    @DisplayName("every glyph parses to a shape with a size")
    void everyGlyphDraws() throws Exception {
        // Found by reflection so a glyph added later is covered without anyone remembering to list
        // it — the failure this guards against is silent (see ProfileIconsTest).
        List<String> checked = new ArrayList<>();
        for (Method method : MenuIcons.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) continue;
            if (method.getReturnType() != Node.class) continue;
            method.setAccessible(true);
            Node node = (Node) method.invoke(null);
            assertTrue(node.getBoundsInLocal().getWidth() > 0, method.getName() + " has no width");
            assertTrue(node.getBoundsInLocal().getHeight() > 0, method.getName() + " has no height");
            checked.add(method.getName());
        }
        assertTrue(
                checked.containsAll(List.of("appearance", "terminal", "profiles", "keyboard", "sync", "advanced")),
                checked::toString);
    }

    @Test
    @DisplayName("Configure Profiles wears its own glyph, not the Settings gear")
    void aCommandHasOneGlyph() {
        // ICON-20 (Pauta 6.0.1): the profile menu's row once wore the gear and the File menu's the
        // layers. Both now ask this lookup.
        assertEquals(path(MenuIcons.profiles()), path(MenuIcons.forCommand("configureProfiles")));
        assertEquals(path(MenuIcons.settings()), path(MenuIcons.forCommand("settings")));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                path(MenuIcons.settings()), path(MenuIcons.forCommand("configureProfiles")));
        org.junit.jupiter.api.Assertions.assertNull(MenuIcons.forCommand("noSuchCommand"));
        org.junit.jupiter.api.Assertions.assertNull(MenuIcons.forCommand(null));
    }

    private static String path(Node glyph) {
        return ((javafx.scene.shape.SVGPath) glyph).getContent();
    }
}
