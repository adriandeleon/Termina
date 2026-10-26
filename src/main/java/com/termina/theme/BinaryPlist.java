package com.termina.theme;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A binary property list ({@code bplist00}) read into maps, lists and values — the format of each
 * colour inside a Terminal {@code .terminal} profile, where an {@code NSColor} is archived with
 * {@code NSKeyedArchiver} and stored as {@code <data>}.
 *
 * <p>Only what an archived colour uses, but all of the container format, so a profile written by any
 * version of Terminal reads: integers, reals, booleans, data, ASCII and UTF-16 strings, UIDs, arrays
 * and dictionaries. A UID — how a keyed archive refers to another of its objects — comes back as a
 * {@link Uid}. Bounds are checked everywhere, since the bytes are a downloaded file's.
 */
final class BinaryPlist {

    /** A reference from one archived object to another, by its index in {@code $objects}. */
    record Uid(int index) {}

    private final ByteBuffer bytes;
    private final int offsetSize;
    private final int refSize;
    private final long[] offsets;
    /** Objects being read, to refuse a reference cycle rather than recurse forever. */
    private final boolean[] reading;

    private BinaryPlist(byte[] data) throws ThemeImportException {
        if (data.length < 8 + 32 || !new String(data, 0, 8, StandardCharsets.US_ASCII).equals("bplist00")) {
            throw new ThemeImportException("not a binary property list");
        }
        bytes = ByteBuffer.wrap(data);
        int trailer = data.length - 32;
        offsetSize = data[trailer + 6] & 0xff;
        refSize = data[trailer + 7] & 0xff;
        long count = bytes.getLong(trailer + 8);
        long tableAt = bytes.getLong(trailer + 24);
        if (offsetSize < 1
                || offsetSize > 8
                || refSize < 1
                || refSize > 8
                || count < 1
                || count > 1_000_000
                || tableAt < 8
                || tableAt + count * offsetSize > trailer) {
            throw new ThemeImportException("a damaged binary property list");
        }
        offsets = new long[(int) count];
        for (int i = 0; i < count; i++) offsets[i] = sized((int) (tableAt + (long) i * offsetSize), offsetSize);
        reading = new boolean[(int) count];
    }

    /** The top object. */
    static Object parse(byte[] data) throws ThemeImportException {
        BinaryPlist plist = new BinaryPlist(data);
        long top = plist.bytes.getLong(data.length - 32 + 16);
        try {
            return plist.object(top);
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException e) {
            throw new ThemeImportException("a damaged binary property list", e);
        }
    }

    private Object object(long ref) throws ThemeImportException {
        if (ref < 0 || ref >= offsets.length) throw new ThemeImportException("a damaged binary property list");
        int index = (int) ref;
        if (reading[index]) throw new ThemeImportException("a binary property list that refers to itself");
        reading[index] = true;
        try {
            return read(checked(offsets[index]));
        } finally {
            reading[index] = false;
        }
    }

    private Object read(int at) throws ThemeImportException {
        int marker = bytes.get(at) & 0xff;
        int high = marker >> 4;
        int low = marker & 0x0f;
        switch (high) {
            case 0x0 -> {
                return switch (low) {
                    case 0x8 -> Boolean.FALSE;
                    case 0x9 -> Boolean.TRUE;
                    default -> null;
                };
            }
            case 0x1 -> {
                int size = 1 << low;
                if (size > 8) throw new ThemeImportException("an integer too large to read");
                return sized(at + 1, size);
            }
            case 0x2 -> {
                return low == 2 ? (double) bytes.getFloat(at + 1) : bytes.getDouble(at + 1);
            }
            case 0x3 -> {
                return bytes.getDouble(at + 1);
            }
            case 0x4 -> {
                int[] length = length(at, low);
                byte[] data = new byte[length[0]];
                bytes.get(checked(length[1]), data);
                return data;
            }
            case 0x5 -> {
                int[] length = length(at, low);
                byte[] data = new byte[length[0]];
                bytes.get(checked(length[1]), data);
                return new String(data, StandardCharsets.US_ASCII);
            }
            case 0x6 -> {
                int[] length = length(at, low);
                byte[] data = new byte[length[0] * 2];
                bytes.get(checked(length[1]), data);
                return new String(data, StandardCharsets.UTF_16BE);
            }
            case 0x8 -> {
                return new Uid((int) sized(at + 1, low + 1));
            }
            case 0xA -> {
                int[] length = length(at, low);
                List<Object> out = new ArrayList<>(length[0]);
                for (int i = 0; i < length[0]; i++) out.add(object(sized(length[1] + i * refSize, refSize)));
                return out;
            }
            case 0xD -> {
                int[] length = length(at, low);
                Map<String, Object> out = new LinkedHashMap<>();
                int values = length[1] + length[0] * refSize;
                for (int i = 0; i < length[0]; i++) {
                    Object key = object(sized(length[1] + i * refSize, refSize));
                    out.put(String.valueOf(key), object(sized(values + i * refSize, refSize)));
                }
                return out;
            }
            default -> throw new ThemeImportException("an unsupported value in a binary property list");
        }
    }

    /** A container's or blob's length and where its contents start: {@code {length, start}}. */
    private int[] length(int at, int low) throws ThemeImportException {
        if (low != 0xF) return new int[] {low, at + 1};
        int marker = bytes.get(at + 1) & 0xff;
        if (marker >> 4 != 0x1) throw new ThemeImportException("a damaged binary property list");
        int size = 1 << (marker & 0x0f);
        long length = sized(at + 2, size);
        if (length < 0 || length > bytes.capacity()) throw new ThemeImportException("a damaged binary property list");
        return new int[] {(int) length, at + 2 + size};
    }

    private long sized(int at, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) value = value << 8 | (bytes.get(at + i) & 0xff);
        return value;
    }

    private int checked(long at) throws ThemeImportException {
        if (at < 0 || at >= bytes.capacity()) throw new ThemeImportException("a damaged binary property list");
        return (int) at;
    }
}
