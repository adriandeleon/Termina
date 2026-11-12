package com.termina;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry point answers the command line without the JavaFX toolkit (CLI-01, CLI-05).
 *
 * <p>A main class that extends {@code Application} has the toolkit started for it by the Java
 * launcher before {@code main} runs, so {@code --version}, {@code --help}, {@code --report} and a
 * mistyped option all needed a display, and died without one. Nothing fails when that comes back
 * on a machine with a display, which is every developer's: these are what fails instead.
 */
class MainTest {

    /** {@code -m com.termina/com.termina.Main}, {@code <mainClass>}, {@code <module>}. */
    private static final Pattern ENTRY_POINT = Pattern.compile("com\\.termina/(com\\.termina\\.[A-Za-z0-9_.]+)");

    /** Everything that starts the application by naming its main class. */
    private static final List<String> LAUNCHERS = List.of("pom.xml", "scripts/dev-run.sh", "scripts/aot-train.sh");

    @Test
    void theEntryPointIsNotAnApplication() {
        assertFalse(
                Application.class.isAssignableFrom(Main.class),
                "the launcher starts the toolkit before the main of an Application subclass");
    }

    @Test
    void everyLauncherNamesAnEntryPointThatIsNotAnApplication() throws Exception {
        List<String> named = new ArrayList<>();
        for (String launcher : LAUNCHERS) {
            Matcher m = ENTRY_POINT.matcher(Files.readString(Path.of(launcher)));
            assertTrue(m.find(), launcher + " names no entry point; has the way it launches changed?");
            do {
                named.add(launcher + ": " + m.group(1));
                Class<?> entry = Class.forName(m.group(1), false, MainTest.class.getClassLoader());
                assertFalse(
                        Application.class.isAssignableFrom(entry),
                        launcher + " launches " + entry.getName() + ", an Application: the toolkit starts first");
                Method main = entry.getMethod("main", String[].class);
                assertTrue(Modifier.isStatic(main.getModifiers()), entry.getName() + ".main is not static");
            } while (m.find());
        }
        // Both places in the pom: `javafx:run` and the packaged image's launcher.
        assertEquals(
                List.of(
                        "pom.xml: com.termina.Main",
                        "pom.xml: com.termina.Main",
                        "scripts/dev-run.sh: com.termina.Main",
                        "scripts/aot-train.sh: com.termina.Main"),
                named);
    }

    /**
     * The window class is the {@code Application} subclass's name, not the entry point's: JavaFX
     * names the glass application after the class given to {@code launch}
     * ({@code PlatformImpl.setApplicationName}), and GTK glass sets WM_CLASS from that. The desktop
     * entry matches a window to its launcher by it, so renaming {@link App} has to rename it there.
     */
    @Test
    void theDesktopEntryNamesTheApplicationClassAsItsWindowClass() throws IOException {
        String wmClass = "StartupWMClass=" + App.class.getName();
        assertTrue(
                Files.readString(Path.of("packaging/linux/Termina.desktop"))
                        .lines()
                        .anyMatch(wmClass::equals),
                "packaging/linux/Termina.desktop has no line " + wmClass);
        assertTrue(
                Files.readString(Path.of("scripts/check-linux-packages.sh")).contains("\"^" + wmClass + "$\""),
                "scripts/check-linux-packages.sh does not assert " + wmClass);
    }

    /**
     * The real thing, in a process of its own where no toolkit can start: no display, and a glass
     * platform that does not exist, so a toolkit started on any operating system is an exception
     * and a non-zero exit rather than a slower answer. {@code --report} is left out only because it
     * opens a browser.
     */
    @Test
    void theCommandLineIsAnsweredWhereNoToolkitCanStart(@TempDir Path config) throws Exception {
        Result version = run(config, "--version");
        assertEquals(0, version.exit(), version.toString());
        assertTrue(version.out().startsWith(AppInfo.NAME + " "), version.toString());

        Result help = run(config, "--help");
        assertEquals(0, help.exit(), help.toString());
        assertTrue(help.out().contains("--version"), help.toString());

        Result mistake = run(config, "--no-such-option");
        assertEquals(2, mistake.exit(), mistake.toString());
        assertTrue(mistake.err().contains("--no-such-option"), mistake.toString());
    }

    private record Result(int exit, String out, String err) {}

    private static Result run(Path config, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                // Surefire starts the tests from a jar that only names the class path.
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "-Dglass.platform=NoSuchPlatform",
                Main.class.getName()));
        command.addAll(List.of(args));
        Path out = config.resolve("out.txt");
        Path err = config.resolve("err.txt");
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().remove("DISPLAY");
        builder.environment().remove("WAYLAND_DISPLAY");
        builder.environment().put("TERMINA_CONFIG_DIR", config.toString());
        Process process = builder.start();
        boolean answered = process.waitFor(2, TimeUnit.MINUTES);
        if (!answered) process.destroyForcibly().waitFor();
        Result result = new Result(
                answered ? process.exitValue() : -1,
                Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
        assertTrue(answered, "no answer to " + List.of(args) + ": " + result);
        return result;
    }
}
