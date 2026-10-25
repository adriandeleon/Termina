package com.termina.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every context-menu and settings-sidebar glyph actually draws. */
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
}
