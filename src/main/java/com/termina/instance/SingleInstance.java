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
    private @Nullable ServerSocketChannel server;

    private SingleInstance(FileChannel lockChannel, FileLock lock, Path socket) {
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.socket = socket;
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
        return new SingleInstance(channel, lock, socketFor(configDir));
    }

    /**
     * Listens for later launches; each request goes to {@code onRequest}, on the listening thread,
     * after it has been acknowledged.
     */
    public void serve(Consumer<Request> onRequest) throws IOException {
        // Ours to remove: the lock is held, so a file left here is a dead process's.
        Files.deleteIfExists(socket);
        ServerSocketChannel listening = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        listening.bind(UnixDomainSocketAddress.of(socket));
        server = listening;
        Thread thread = new Thread(
                () -> {
                    while (listening.isOpen()) {
                        try (SocketChannel client = listening.accept()) {
                            Request request = decode(readLine(client));
                            client.write(ByteBuffer.wrap("ok\n".getBytes(StandardCharsets.UTF_8)));
                            onRequest.accept(request);
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

    /**
     * Hands a request to the Termina holding the folder. True once it has acknowledged; false when
     * nothing answered within {@code timeout}, in which case the caller starts on its own.
     */
    public static boolean handOff(Path configDir, Request request, Duration timeout) {
        Path socket = socketFor(configDir);
        CompletableFuture<Boolean> answered = CompletableFuture.supplyAsync(() -> {
            try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                channel.write(ByteBuffer.wrap((encode(request) + "\n").getBytes(StandardCharsets.UTF_8)));
                return "ok".equals(readLine(channel).strip());
            } catch (IOException | RuntimeException e) {
                return false;
            }
        });
        try {
            return answered.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            answered.cancel(true);
            return false;
        }
    }

    /**
     * Where the socket is: in the config folder, or, when that path is too long for a socket, in
     * the temp folder under a name derived from the folder, so both launches find the same one.
     */
    static Path socketFor(Path configDir) {
        Path inFolder = configDir.toAbsolutePath().normalize().resolve("instance.sock");
        if (inFolder.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_SOCKET_PATH) return inFolder;
        String hash = HexFormat.of()
                .formatHex(sha256(configDir.toAbsolutePath().normalize().toString()))
                .substring(0, 16);
        return Path.of(System.getProperty("java.io.tmpdir"), "termina-" + hash + ".sock");
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
