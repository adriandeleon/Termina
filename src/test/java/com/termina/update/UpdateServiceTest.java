package com.termina.update;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The part of the network path that can be tested without a network. */
class UpdateServiceTest {

    @Test
    void theBodyIsBoundedWhileItIsRead() throws Exception {
        // BodyHandlers.ofString buffered the whole response before the cap could look at it, so
        // the cap bounded nothing and the real limit was the timeout.
        byte[] small = "{\"tag_name\": \"v1.0.0\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"tag_name\": \"v1.0.0\"}", UpdateService.readCapped(new ByteArrayInputStream(small), 1024));

        byte[] large = new byte[2048];
        java.util.Arrays.fill(large, (byte) 'x');
        assertNull(UpdateService.readCapped(new ByteArrayInputStream(large), 1024), "past the cap is not a payload");
        assertEquals(
                1024,
                UpdateService.readCapped(new ByteArrayInputStream(large, 0, 1024), 1024)
                        .length(),
                "exactly the cap is fine");
    }
}
