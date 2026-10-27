package com.termina.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.termina.link.CommandPath;
import org.jspecify.annotations.Nullable;

/**
 * Finds a program the operating system provides — {@code notify-send}, {@code gdbus}, {@code wl-paste},
 * {@code xclip} — on the PATH this process was given and in the system's own directories.
 *
 * <p>Not through {@link LoginShellPath}, which starts the user's login shell to learn the PATH they
 * set up for themselves. That is for tools the user installed (Homebrew, {@code ~/.local/bin}); these
 * come from the distribution, and the first middle-click or notification should not wait up to five
 * seconds for a shell's profile to run before it can find {@code /usr/bin/xclip}.
 */
final class SystemTools {

    private static final List<Path> SYSTEM_DIRECTORIES =
            List.of(Path.of("/usr/local/bin"), Path.of("/usr/bin"), Path.of("/bin"));

    private SystemTools() {}

    /** The program's absolute path, or null when it is not installed. */
    static @Nullable Path find(String program) {
        List<Path> directories = new ArrayList<>(CommandPath.entries(System.getenv("PATH")));
        for (Path system : SYSTEM_DIRECTORIES) {
            if (!directories.contains(system)) directories.add(system);
        }
        return CommandPath.resolve(program, directories, Files::isExecutable);
    }
}
