package com.termina.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The opacity floor. */
class WindowOpacityTest {

    @Test
    void aNormalValuePassesThrough() {
        assertEquals(0.85, WindowOpacity.clamp(0.85));
    }

    @Test
    void aValueBelowTheFloorIsRaised() {
        // The setting that can hide the window used to change it. A hand-edited 0.05 has to come
        // back as something you can still read Settings through.
        assertEquals(WindowOpacity.MIN, WindowOpacity.clamp(0.05));
        assertEquals(WindowOpacity.MIN, WindowOpacity.clamp(0));
        assertEquals(WindowOpacity.MIN, WindowOpacity.clamp(-3));
    }

    @Test
    void aValueAboveOpaqueIsCapped() {
        assertEquals(1.0, WindowOpacity.clamp(4));
    }

    @Test
    void garbageMeansOpaque() {
        assertEquals(1.0, WindowOpacity.clamp(Double.NaN));
    }

    @Test
    void percentIsForDisplay() {
        assertEquals(100, WindowOpacity.percent(1.0));
        assertEquals(85, WindowOpacity.percent(0.85));
        assertEquals(60, WindowOpacity.percent(0.02));
    }
}
