package com.termina.pty;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk from a PEB to the current directory, against a made-up address space laid out as 64-bit
 * Windows lays out a process. Only the walk: the system calls around it have not been run.
 */
class WindowsCwdTest {

    /** Byte-addressed memory with a few regions written into it; anything else cannot be read. */
    private static final class FakeProcess implements WindowsCwd.AddressSpace {
        final Map<Long, byte[]> regions = new HashMap<>();

        void pointer(long at, long value) {
            regions.put(
                    at,
                    ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putLong(value)
                            .array());
        }

        void ushort(long at, int value) {
            regions.put(
                    at,
                    ByteBuffer.allocate(2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putShort((short) value)
                            .array());
        }

        void bytes(long at, byte[] value) {
            regions.put(at, value);
        }

        @Override
        public byte[] read(long address, int length) {
            byte[] region = regions.get(address);
            return region != null && region.length >= length ? java.util.Arrays.copyOf(region, length) : null;
        }
    }

    private static final long PEB = 0x7ff6_1000_0000L;
    private static final long PARAMETERS = 0x0000_01a0_0002_0000L;
    private static final long BUFFER = 0x0000_01a0_0003_0000L;

    private static FakeProcess processIn(String directory) {
        FakeProcess process = new FakeProcess();
        byte[] utf16 = directory.getBytes(StandardCharsets.UTF_16LE);
        process.pointer(PEB + WindowsCwd.PEB_PROCESS_PARAMETERS, PARAMETERS);
        process.ushort(PARAMETERS + WindowsCwd.PARAMETERS_CURRENT_DIRECTORY_LENGTH, utf16.length);
        process.pointer(PARAMETERS + WindowsCwd.PARAMETERS_CURRENT_DIRECTORY_BUFFER, BUFFER);
        process.bytes(BUFFER, utf16);
        return process;
    }

    @Test
    void followsThePebToTheDirectoryWithoutItsTrailingBackslash() {
        assertEquals(Optional.of("C:\\Users\\adl\\src"), WindowsCwd.read(PEB, processIn("C:\\Users\\adl\\src\\")));
    }

    @Test
    void theRootKeepsItsBackslash() {
        assertEquals(Optional.of("D:\\"), WindowsCwd.read(PEB, processIn("D:\\")));
    }

    @Test
    void aPathOutsideTheBasicPlaneSurvives() {
        assertEquals(
                Optional.of("C:\\Users\\adl\\\uD83D\uDCC1 notes"),
                WindowsCwd.read(PEB, processIn("C:\\Users\\adl\\\uD83D\uDCC1 notes\\")));
    }

    @Test
    void anUnreadableStepAnswersNothing() {
        FakeProcess process = processIn("C:\\x\\");
        process.regions.remove(BUFFER);
        assertTrue(WindowsCwd.read(PEB, process).isEmpty());
        assertTrue(WindowsCwd.read(0, processIn("C:\\x\\")).isEmpty());
        assertTrue(WindowsCwd.read(PEB, new FakeProcess()).isEmpty());
    }

    @Test
    void aLengthThatCannotBeAPathIsAMisreadNotAPath() {
        FakeProcess odd = processIn("C:\\x\\");
        odd.ushort(PARAMETERS + WindowsCwd.PARAMETERS_CURRENT_DIRECTORY_LENGTH, 7);
        assertTrue(WindowsCwd.read(PEB, odd).isEmpty());
        FakeProcess empty = processIn("C:\\x\\");
        empty.ushort(PARAMETERS + WindowsCwd.PARAMETERS_CURRENT_DIRECTORY_LENGTH, 0);
        assertTrue(WindowsCwd.read(PEB, empty).isEmpty());
    }

    @Test
    void aShellsOwnReportWinsOnWindowsOnly() {
        assertTrue(ProcessCwd.reportedDirectoryWins("Windows 11"));
        assertFalse(ProcessCwd.reportedDirectoryWins("Linux"));
        assertFalse(ProcessCwd.reportedDirectoryWins("Mac OS X"));
    }

    @Test
    void wslsLaunchersAreNotTheShell() {
        assertFalse(ProcessCwd.osKnowsDirectoryOf("wsl.exe"));
        assertFalse(ProcessCwd.osKnowsDirectoryOf("C:\\Windows\\System32\\wsl.exe"));
        assertFalse(ProcessCwd.osKnowsDirectoryOf("C:\\Windows\\System32\\bash.exe"));
        assertTrue(ProcessCwd.osKnowsDirectoryOf("pwsh.exe"));
        assertTrue(ProcessCwd.osKnowsDirectoryOf("/bin/bash"));
        assertTrue(ProcessCwd.osKnowsDirectoryOf(""));
    }
}
