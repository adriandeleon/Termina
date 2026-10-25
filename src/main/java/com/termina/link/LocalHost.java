package com.termina.link;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import com.termina.process.CommandOutput;

/**
 * This machine's name, as programs that print {@code file://} URIs spell it.
 *
 * <p>{@code ls --hyperlink} and the shells' OSC 7 put {@code gethostname()} in the host part, and a
 * URI naming this machine is a local file while one naming another is not. Java has no
 * {@code gethostname}: {@code InetAddress.getLocalHost()} resolves the name through DNS, which on a
 * Mac with an unresolvable name has been measured in seconds. So the name is read the way each
 * platform keeps it — the kernel's on Linux, the environment on Windows, {@code hostname}
 * elsewhere — once, on first use.
 */
public final class LocalHost {

    private LocalHost() {}

    private static final class Holder {
        static final String NAME = read();
    }

    /** The name, or empty when it could not be read — in which case only an empty host is local. */
    public static String name() {
        return Holder.NAME;
    }

    /** Whether a URI's host part names this machine. */
    public static boolean isLocal(String host) {
        return isLocal(host, name());
    }

    /**
     * Compared both whole and by first label: a shell may report {@code devbox} for a machine
     * whose full name is {@code devbox.lan}, and the other way round.
     */
    static boolean isLocal(String host, String localName) {
        if (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost")) return true;
        if (localName == null || localName.isEmpty()) return false;
        if (host.equalsIgnoreCase(localName)) return true;
        return firstLabel(host).equals(firstLabel(localName));
    }

    private static String firstLabel(String name) {
        int dot = name.indexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    private static String read() {
        try {
            String env = System.getenv("COMPUTERNAME");
            if (env != null && !env.isBlank()) return env.strip();
            Path kernel = Path.of("/proc/sys/kernel/hostname");
            if (Files.isReadable(kernel))
                return Files.readString(kernel, StandardCharsets.UTF_8).strip();
            return CommandOutput.read(new ProcessBuilder("hostname"), Duration.ofSeconds(2))
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8).strip())
                    .orElse("");
        } catch (Exception unreadable) {
            return "";
        }
    }
}
