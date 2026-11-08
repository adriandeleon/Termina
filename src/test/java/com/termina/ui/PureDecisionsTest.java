package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window's enablement, visibility and navigation rules sit in classes that import no JavaFX
 * type, each with a test that does not need the toolkit (QUAL-05, Pauta 2.0.2). A rule added to one
 * of these that reaches for an event or a node fails here, where it is cheap to move it out.
 */
class PureDecisionsTest {

    /** Each decision class, and the plain test that covers it. */
    private static final List<String[]> DECISIONS = List.of(
            new String[] {"WindowRules", "WindowRulesPureTest"},
            new String[] {"PaletteKeys", "PaletteKeysTest"},
            new String[] {"MouseEncoding", "MouseEncodingTest"},
            new String[] {"TabRanges", "TabRangesTest"},
            new String[] {"FocusGuard", "FocusGuardTest"},
            new String[] {"SettingsSearch", "SettingsSearchTest"},
            new String[] {"PasteSafety", "PasteSafetyTest"},
            new String[] {"ZoomLevels", "ZoomLevelsTest"},
            new String[] {"WindowPlacement", "WindowPlacementTest"},
            new String[] {"WindowGeometry", "WindowGeometryTest"},
            new String[] {"ClosedTabs", "ClosedTabsTest"},
            new String[] {"TabMarks", "TabMarksTest"},
            new String[] {"CursorDraw", "CursorDrawTest"},
            new String[] {"CellWidth", "CellWidthTest"});

    @Test
    void decisionsImportNoJavaFxAndHaveATestWithoutTheToolkit() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (String[] pair : DECISIONS) {
            String main = Files.readString(Path.of("src/main/java/com/termina/ui", pair[0] + ".java"));
            if (main.contains("import javafx.")) wrong.add(pair[0] + " imports JavaFX");
            Path testFile = Path.of("src/test/java/com/termina/ui", pair[1] + ".java");
            if (!Files.exists(testFile)) {
                wrong.add(pair[0] + " has no " + pair[1]);
                continue;
            }
            if (Files.readString(testFile).contains("@Tag(\"fx\")")) wrong.add(pair[1] + " needs the toolkit");
        }
        assertEquals(List.of(), wrong);
    }
}
