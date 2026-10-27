package com.termina.link;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Turns a link target into a path on this machine.
 *
 * <p>Pure: it decides what path a token names, never whether that path exists. The existence check
 * is the caller's, because it is the one part that touches the disk and the one part that must not
 * run while painting.
 */
public final class LinkPaths {

    private LinkPaths() {}

    /**
     * The file a path token names, resolved against the shell's directory, or null.
     *
     * <p>Relative to {@code cwd} rather than to Termina's own working directory, which is the whole
     * reason this is useful: {@code src/main/java/Foo.java} in a build's output means that file in
     * the directory the build ran in, and every tab has its own.
     */
    public static @Nullable Path resolve(String token, @Nullable Path cwd) {
        if (token == null || token.isBlank()) return null;
        try {
            String expanded = expandHome(token);
            // Before anything can stat it: see isUnc.
            if (isUnc(token) || isUnc(expanded)) return null;
            Path path = Paths.get(expanded);
            if (path.isAbsolute()) return path.normalize();
            if (cwd == null) return null;
            return cwd.resolve(path).normalize();
        } catch (RuntimeException e) {
            // A token with a NUL or an illegal character for this filesystem is not a path. The
            // pointer is simply over ordinary text.
            return null;
        }
    }

    /** {@code ~} and {@code ~/…} against the running user's home. {@code ~other} is left alone. */
    static String expandHome(String token) {
        if (token.equals("~")) return home();
        if (token.startsWith("~/") || token.startsWith("~\\")) return home() + token.substring(1);
        return token;
    }

    private static String home() {
        return System.getProperty("user.home", "");
    }

    /**
     * Whether a token names a share on another machine — {@code \\host\share}, {@code
     * //host/share}, or any mix of the two separators, which Windows reads alike. The device
     * namespaces {@code \\?\} and {@code \\.\} start the same way and are refused with them.
     *
     * <p>Refused outright, on every platform and before any existence check. Asking Windows whether
     * {@code \\host\share\x} exists is an SMB connection to {@code host}, which hands it the
     * user's NTLM hash; a hover would make it for any text a program printed. On Unix the same
     * spelling is a local path with a doubled slash, and losing that as a link costs nothing.
     */
    public static boolean isUnc(String token) {
        return token != null && token.length() >= 2 && isSeparator(token.charAt(0)) && isSeparator(token.charAt(1));
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    /** Whether a link target is a {@code file:} URL — one that must be opened as a file or not at all. */
    public static boolean isFileUri(String target) {
        return target != null && target.regionMatches(true, 0, "file:", 0, 5);
    }

    /**
     * The file a {@code file:} URL names, or null when it is not one, names another host, or names
     * a share.
     *
     * <p>Kept apart from {@link #resolve} because a URL is percent-encoded and a path is not:
     * {@code file:///tmp/a%20b} is one file whose name has a space in it, and treating those bytes
     * as a path would look for a file called {@code a%20b}.
     */
    public static @Nullable Path fromFileUri(String target) {
        String path = fileUriPath(target, LocalHost::isLocal);
        if (path == null) return null;
        try {
            Path file = Paths.get(path);
            // A drive path on a platform without drives is relative, and would be looked for in
            // Termina's own working directory. Rooted rather than absolute: on Windows /tmp/x has a
            // root and no drive, isAbsolute() is false for it, and it is still a local file — the
            // root of the current drive. (UNC, the rooted form that is not local, is refused above.)
            return file.getRoot() != null ? file.normalize() : null;
        } catch (RuntimeException notAPath) {
            return null;
        }
    }

    /**
     * The path part of a {@code file:} URL, decoded, or null when it is not one of ours.
     *
     * <p>Null for another host: {@code file://host/share} is someone else's filesystem — unless the
     * host is this machine, which is how {@code ls --hyperlink} and every OSC 7 spell a local file.
     * Null for a UNC path too, which is how another host gets in with an empty host part:
     * {@code file:////host/share}. A Windows drive path arrives as {@code /C:/Users/…} and loses the
     * leading slash, which no platform's path parser would take.
     *
     * @param isLocalHost whether a host name is this machine; a parameter so a test needs no network
     */
    static @Nullable String fileUriPath(String target, Predicate<String> isLocalHost) {
        if (!isFileUri(target)) return null;
        try {
            URI uri = new URI(target);
            String host = uri.getHost();
            // An authority URI cannot parse into a host — file://user@[bad]/ — is not a local one.
            if (host == null
                    && uri.getRawAuthority() != null
                    && !uri.getRawAuthority().isEmpty()) return null;
            boolean local =
                    host == null || host.isEmpty() || host.equalsIgnoreCase("localhost") || isLocalHost.test(host);
            if (!local) return null;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return null;
            if (path.length() >= 3
                    && path.charAt(0) == '/'
                    && path.charAt(2) == ':'
                    && Character.isLetter(path.charAt(1))) {
                path = path.substring(1);
            }
            return isUnc(path) ? null : path;
        } catch (Exception malformed) {
            return null;
        }
    }
}
