package com.termina.pty;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * A Windows process's current directory, read out of its own memory — what Windows offers instead
 * of {@code /proc/<pid>/cwd}.
 *
 * <p>There is no API for another process's directory. It lives in the process's own parameters
 * block, which a process with {@code PROCESS_VM_READ} on it can read: the PEB (found with {@code
 * NtQueryInformationProcess}) points at {@code RTL_USER_PROCESS_PARAMETERS}, whose {@code
 * CurrentDirectory.DosPath} is a {@code UNICODE_STRING} — a byte length and a pointer to UTF-16. On
 * 64-bit Windows, x64 and ARM64 alike:
 *
 * <pre>
 *   PROCESS_BASIC_INFORMATION   +0x08  PebBaseAddress
 *   PEB                         +0x20  ProcessParameters
 *   RTL_USER_PROCESS_PARAMETERS +0x38  CurrentDirectory.DosPath.Length (USHORT, bytes)
 *                               +0x40  CurrentDirectory.DosPath.Buffer
 * </pre>
 *
 * <p>Undocumented in the sense that Microsoft does not promise the layout, documented in the sense
 * that every process explorer and debugger has read it the same way since Windows XP x64. A 32-bit
 * process under WOW64 has a second, 32-bit PEB with other offsets; it is not read, and answers
 * nothing, rather than being read wrongly.
 *
 * <p>The pointer walking is {@link #read}, over an {@link AddressSpace}, so it is tested against a
 * made-up address space; only {@link #of} touches Windows. <b>That part has not been run.</b>
 */
final class WindowsCwd {

    static final int PEB_PROCESS_PARAMETERS = 0x20;
    static final int PARAMETERS_CURRENT_DIRECTORY_LENGTH = 0x38;
    static final int PARAMETERS_CURRENT_DIRECTORY_BUFFER = 0x40;

    /** A path longer than Windows' own limit for a current directory is a misread, not a path. */
    static final int MAX_PATH_BYTES = 32767 * 2;

    private WindowsCwd() {}

    /** Another process's memory, as a reader of it sees it: null where it cannot be read. */
    interface AddressSpace {
        byte[] read(long address, int length);
    }

    /** The current directory, given where the process's PEB is and a way to read its memory. */
    static Optional<String> read(long pebAddress, AddressSpace memory) {
        if (pebAddress == 0) return Optional.empty();
        Long parameters = pointer(memory, pebAddress + PEB_PROCESS_PARAMETERS);
        if (parameters == null || parameters == 0) return Optional.empty();
        byte[] length = memory.read(parameters + PARAMETERS_CURRENT_DIRECTORY_LENGTH, 2);
        Long buffer = pointer(memory, parameters + PARAMETERS_CURRENT_DIRECTORY_BUFFER);
        if (length == null || buffer == null || buffer == 0) return Optional.empty();
        int bytes = ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
        if (bytes == 0 || bytes % 2 != 0 || bytes > MAX_PATH_BYTES) return Optional.empty();
        byte[] text = memory.read(buffer, bytes);
        if (text == null) return Optional.empty();
        return Optional.of(trimmed(new String(text, StandardCharsets.UTF_16LE)));
    }

    /**
     * Windows keeps the current directory with a trailing backslash ({@code C:\Users\adl\}), which
     * no shell shows. The root keeps it: {@code C:} on its own means "the current directory on C".
     */
    static String trimmed(String path) {
        if (path.length() > 3 && path.endsWith("\\")) return path.substring(0, path.length() - 1);
        return path;
    }

    private static Long pointer(AddressSpace memory, long address) {
        byte[] bytes = memory.read(address, 8);
        return bytes == null
                ? null
                : ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    // --- the Windows half -------------------------------------------------------------------

    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_BASIC_INFORMATION = 0;
    /** {@code PROCESS_BASIC_INFORMATION} on 64-bit Windows: six pointer-sized fields. */
    private static final int BASIC_INFORMATION_SIZE = 48;

    private static final int BASIC_INFORMATION_PEB = 8;

    /** Loaded lazily, as {@code ProcessCwd}'s libproc is, so nothing here runs off Windows. */
    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer OpenProcess(int access, boolean inherit, int pid);

        boolean ReadProcessMemory(Pointer process, Pointer address, Pointer buffer, long size, Pointer read);

        boolean IsWow64Process(Pointer process, IntByReference wow64);

        boolean CloseHandle(Pointer handle);
    }

    private interface NtDll extends Library {
        NtDll INSTANCE = Native.load("ntdll", NtDll.class);

        int NtQueryInformationProcess(
                Pointer process, int infoClass, Pointer info, int length, IntByReference returned);
    }

    /** The directory of a process by pid, or empty. Never throws; see {@code ProcessCwd}. */
    static Optional<String> of(long pid) {
        if (Native.POINTER_SIZE != 8) return Optional.empty();
        Pointer process = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, (int) pid);
        if (process == null) return Optional.empty();
        try {
            IntByReference wow64 = new IntByReference();
            if (Kernel32.INSTANCE.IsWow64Process(process, wow64) && wow64.getValue() != 0) return Optional.empty();
            try (com.sun.jna.Memory info = new com.sun.jna.Memory(BASIC_INFORMATION_SIZE)) {
                info.clear();
                int status = NtDll.INSTANCE.NtQueryInformationProcess(
                        process, PROCESS_BASIC_INFORMATION, info, BASIC_INFORMATION_SIZE, new IntByReference());
                if (status != 0) return Optional.empty();
                long peb = info.getLong(BASIC_INFORMATION_PEB);
                return read(peb, (address, length) -> readRemote(process, address, length));
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private static byte[] readRemote(Pointer process, long address, int length) {
        try (com.sun.jna.Memory buffer = new com.sun.jna.Memory(length)) {
            if (!Kernel32.INSTANCE.ReadProcessMemory(process, new Pointer(address), buffer, length, null)) return null;
            return buffer.getByteArray(0, length);
        }
    }
}
