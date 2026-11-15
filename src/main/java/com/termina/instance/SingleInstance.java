package com.termina.instance;

import java.io.IOException;
import java.io.StringWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.jspecify.annotations.Nullable;

/**
 * One Termina per config folder (CLI-07, CLI-08, Pauta 2.0.2). The first launch takes a lock on
 * {@code instance.lock} in the folder and listens on a Unix-domain socket beside it; a later launch
 * finds the lock taken, hands its arguments and its working directory to the first over the socket,
 * and exits once they are acknowledged. The first opens a window for them, as a fresh process would
 * have. {@code --new-instance} skips all of it.
 *
 * <p>A Unix-domain socket rather than a TCP port: it is a file in the user's own config folder, so
 * nothing else on the machine can reach it, and it needs no port to be free. Java has it on Windows
 * 10 as well.
 */
public final class SingleInstance implements AutoCloseable {

    /** What a second launch asks the first to do: start as if with these arguments, from there. */
    public record Request(String cwd, List<String> args) {}

    /** The lock's file in the config folder. */
    static final String LOCK_FILE = "instance.lock";

    /** Unix socket paths are limited to about a hundred bytes; past this one goes in the temp folder. */
    static final int MAX_SOCKET_PATH = 100;

    private static final JsonFactory JSON = new JsonFactory();

    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Path socket;
    private final Path configDir;
    private @Nullable ServerSocketChannel server;

    private SingleInstance(FileChannel lockChannel, FileLock lock, Path socket, Path configDir) {
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.socket = socket;
        this.configDir = configDir.toAbsolutePath().normalize();
    }

    /**
     * Claims the config folder for this process, or returns null when another Termina holds it.
     * The lock lasts as long as the process: the operating system lets go when it ends, however it
     * ends, so a crash never leaves a folder claimed.
     */
    public static @Nullable SingleInstance claim(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        FileChannel channel =
                FileChannel.open(configDir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        return new SingleInstance(channel, lock, socketFor(configDir), configDir);
    }

    /**
     * Listens for later launches; each request goes to {@code onRequest}, on the listening thread,
     * after it has been acknowledged.
     */
    public void serve(Consumer<Request> onRequest) throws IOException {
        serve(() -> true, onRequest);
    }

    /**
     * As {@link #serve(Consumer)}, asking {@code accepting} first, per request: false sends the
     * launch away to start a Termina of its own, which is what the setting that turns the hand-off
     * off does. Asked on the listening thread, so it must be safe to call off the FX thread.
     */
    public void serve(BooleanSupplier accepting, Consumer<Request> onRequest) throws IOException {
        Path folder = socket.getParent();
        if (folder != null && !folder.equals(configDir)) privateFolder(folder, configDir.resolve(LOCK_FILE));
        // Ours to remove: the lock is held, so a file left here is a dead process's.
        Files.deleteIfExists(socket);
        ServerSocketChannel listening = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        listening.bind(UnixDomainSocketAddress.of(socket));
        ownerOnly(socket);
        server = listening;
        Thread thread = new Thread(
                () -> {
                    while (listening.isOpen()) {
                        try (SocketChannel client = listening.accept()) {
                            Request request = decode(readLine(client));
                            boolean accepted = accepting.getAsBoolean();
                            client.write(ByteBuffer.wrap(
                                    ((accepted ? ACCEPTED : DECLINED) + "\n").getBytes(StandardCharsets.UTF_8)));
                            if (accepted) onRequest.accept(request);
                        } catch (IOException | IllegalArgumentException e) {
                            if (!listening.isOpen()) return;
                            // A malformed or abandoned request: the next one may be fine.
                        }
                    }
                },
                "termina-instance");
        thread.setDaemon(true);
        thread.start();
    }

    /** The two replies: the request was taken, or the launch should start a Termina of its own. */
    static final String ACCEPTED = "ok";

    static final String DECLINED = "own";

    /** How a hand-off went. */
    public enum Answer {
        /** The running Termina has the request; this launch is done. */
        ACCEPTED,
        /** The running Termina is set to leave launches alone; this one starts on its own. */
        DECLINED,
        /** Nothing answered in time; this one starts on its own, and says so. */
        NONE
    }

    /** The reply line as an answer; anything unrecognised is no answer. */
    static Answer answerOf(String reply) {
        return switch (reply.strip()) {
            case ACCEPTED -> Answer.ACCEPTED;
            case DECLINED -> Answer.DECLINED;
            default -> Answer.NONE;
        };
    }

    /**
     * Makes a folder of our own for the socket outside the config folder, readable by nobody else:
     * the temp folder is everybody's, and whoever can connect can ask for a window running any
     * command. Refuses a folder that already exists and belongs to someone else.
     */
    private static void privateFolder(Path folder, Path ours) throws IOException {
        Files.createDirectories(folder);
        if (!Files.getFileStore(folder)
                .supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
            return;
        }
        // The lock file is this process's own making, so its owner is who we are.
        if (!Files.getOwner(folder).equals(Files.getOwner(ours))) {
            throw new IOException(folder + " belongs to someone else");
        }
        Files.setPosixFilePermissions(folder, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    /** The socket file to its owner alone, where permissions mean anything. */
    private static void ownerOnly(Path socket) {
        try {
            Files.setPosixFilePermissions(socket, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the profile folder's ACL is what keeps others out.
        }
    }

    /**
     * Hands a request to the Termina holding the folder, and says how that went: taken, declined,
     * or no answer within {@code timeout}.
     *
     * <p>A refused connection is tried again until the timeout rather than taken as the answer. The
     * holder takes the lock as it starts and listens only once its first window is up, so a second
     * launch a moment after the first — a double click — finds the lock held and no socket yet.
     */
    public static Answer handOff(Path configDir, Request request, Duration timeout) {
        Path socket = socketFor(configDir);
        long deadline = System.nanoTime() + timeout.toNanos();
        // On a thread of its own, never the common pool (PERF-05, Pauta 6.0.1): the connect and the
        // read block for as long as the other Termina takes, and that pool is everybody's.
        CompletableFuture<Answer> answered = CompletableFuture.supplyAsync(
                () -> {
                    SocketChannel channel = connect(socket, deadline);
                    if (channel == null) return Answer.NONE;
                    try (channel) {
                        channel.write(ByteBuffer.wrap((encode(request) + "\n").getBytes(StandardCharsets.UTF_8)));
                        return answerOf(readLine(channel));
                    } catch (IOException | RuntimeException e) {
                        return Answer.NONE;
                    }
                },
                SingleInstance::onHandOffThread);
        try {
            return answered.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            answered.cancel(true);
            return Answer.NONE;
        }
    }

    /** Connects, retrying a socket that is not there yet or not yet listening; null past the deadline. */
    private static @Nullable SocketChannel connect(Path socket, long deadline) {
        while (true) {
            try {
                return SocketChannel.open(UnixDomainSocketAddress.of(socket));
            } catch (IOException | RuntimeException e) {
                if (System.nanoTime() + CONNECT_RETRY.toNanos() > deadline) return null;
                try {
                    Thread.sleep(CONNECT_RETRY);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** Between connection attempts while the holder is still starting. */
    static final Duration CONNECT_RETRY = Duration.ofMillis(50);

    /** The hand-off's thread: a daemon, so a Termina that never answers cannot hold this one open. */
    static void onHandOffThread(Runnable task) {
        Thread thread = new Thread(task, HAND_OFF_THREAD);
        thread.setDaemon(true);
        thread.start();
    }

    static final String HAND_OFF_THREAD = "termina-handoff";

    /**
     * Where the socket is: in the config folder, or, when that path is too long for a socket, in a
     * folder of this user's own under a name derived from the config folder, so both launches find
     * the same one.
     */
    static Path socketFor(Path configDir) {
        return socketFor(
                configDir,
                System.getenv("XDG_RUNTIME_DIR"),
                System.getProperty("java.io.tmpdir"),
                System.getProperty("user.name", "user"));
    }

    /**
     * The decision, with the environment passed in. The fallback is {@code $XDG_RUNTIME_DIR}, which
     * is the user's own by definition, and otherwise a {@code termina-<user>} folder in the temp
     * folder, which {@link #serve} makes private: the temp folder itself is shared on Linux.
     */
    static Path socketFor(Path configDir, @Nullable String runtimeDir, String tmpDir, String user) {
        Path folder = configDir.toAbsolutePath().normalize();
        Path inFolder = folder.resolve("instance.sock");
        if (fits(inFolder)) return inFolder;
        String hash = HexFormat.of().formatHex(sha256(folder.toString())).substring(0, 16);
        String name = "termina-" + hash + ".sock";
        if (runtimeDir != null && !runtimeDir.isBlank()) {
            Path runtime = Path.of(runtimeDir).resolve("termina").resolve(name);
            if (fits(runtime)) return runtime;
        }
        String safeUser = user.replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(tmpDir).resolve("termina-" + safeUser).resolve(name);
    }

    private static boolean fits(Path socket) {
        return socket.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_SOCKET_PATH;
    }

    private static byte[] sha256(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** One request, as one JSON line. */
    static String encode(Request request) {
        StringWriter out = new StringWriter();
        try (JsonGenerator json = JSON.createGenerator(out)) {
            json.writeStartObject();
            json.writeStringField("cwd", request.cwd());
            json.writeArrayFieldStart("args");
            for (String arg : request.args()) json.writeString(arg);
            json.writeEndArray();
            json.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("writing to a string cannot fail", e);
        }
        return out.toString();
    }

    static Request decode(String line) {
        String cwd = null;
        List<String> args = new ArrayList<>();
        try (JsonParser parser = JSON.createParser(line)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("not a request");
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("cwd".equals(field) && value == JsonToken.VALUE_STRING) {
                    cwd = parser.getText();
                } else if ("args".equals(field) && value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.VALUE_STRING) args.add(parser.getText());
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("not a request", e);
        }
        if (cwd == null) throw new IllegalArgumentException("a request names its working directory");
        return new Request(cwd, List.copyOf(args));
    }

    /** Reads up to a newline; a request is small, so a few kilobytes is the bound. */
    private static String readLine(SocketChannel channel) throws IOException {
        var in = Channels.newInputStream(channel);
        StringBuilder line = new StringBuilder();
        byte[] buffer = new byte[1];
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        while (bytes.size() < 64 * 1024) {
            int read = in.read(buffer);
            if (read < 0 || buffer[0] == '\n') break;
            bytes.write(buffer[0]);
        }
        line.append(bytes.toString(StandardCharsets.UTF_8));
        return line.toString();
    }

    @Override
    public void close() {
        try {
            ServerSocketChannel listening = server;
            if (listening != null) listening.close();
            Files.deleteIfExists(socket);
            lock.release();
            lockChannel.close();
        } catch (IOException ignored) {
            // The process is ending; the operating system releases the rest.
        }
    }
}
