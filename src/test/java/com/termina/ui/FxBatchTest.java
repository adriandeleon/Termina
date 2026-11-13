package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PERF-07 (Pauta 6.0.1): a stream reaches the FX thread in batches a frame apart, not item by item. */
class FxBatchTest {

    /** The FX thread and its clock, stood in for: hand-offs and waits are run by the test. */
    private final List<Runnable> handOffs = new ArrayList<>();

    private final List<Long> waits = new ArrayList<>();
    private final List<Runnable> afterWait = new ArrayList<>();
    private final List<List<String>> drained = new ArrayList<>();
    private long nowNanos = 1_000_000_000L;

    private FxBatch<String> batch() {
        return new FxBatch<>(
                drained::add,
                handOffs::add,
                (millis, then) -> {
                    waits.add(millis);
                    afterWait.add(then);
                },
                () -> nowNanos);
    }

    @Test
    void manyItemsAddedBeforeTheFxThreadRunsAreOneHandOffAndOneBatch() {
        FxBatch<String> batch = batch();
        batch.add("one");
        batch.add("two");
        batch.add("three");
        assertEquals(1, handOffs.size(), "one runLater, however many items");
        handOffs.remove(0).run();
        assertEquals(List.of(List.of("one", "two", "three")), drained);
        assertEquals(List.of(), waits, "the first batch does not wait");
    }

    @Test
    void aSecondBatchWaitsOutTheRestOfTheFrame() {
        FxBatch<String> batch = batch();
        batch.add("one");
        handOffs.remove(0).run();
        // Five milliseconds later more arrives: the drain waits the other eleven.
        nowNanos += 5_000_000L;
        batch.add("two");
        batch.add("three");
        assertEquals(1, handOffs.size());
        handOffs.remove(0).run();
        assertEquals(List.of(11L), waits);
        assertEquals(1, drained.size(), "nothing drained until the wait is over");
        // What arrives during the wait rides in the same batch, with no hand-off of its own.
        batch.add("four");
        assertEquals(0, handOffs.size());
        nowNanos += 11_000_000L;
        afterWait.remove(0).run();
        assertEquals(List.of(List.of("one"), List.of("two", "three", "four")), drained);
    }

    @Test
    void aBatchLongAfterTheLastDrainsAtOnce() {
        FxBatch<String> batch = batch();
        batch.add("one");
        handOffs.remove(0).run();
        nowNanos += 500_000_000L;
        batch.add("two");
        handOffs.remove(0).run();
        assertEquals(List.of(), waits);
        assertEquals(List.of(List.of("one"), List.of("two")), drained);
    }

    @Test
    void theWaitIsWhatIsLeftOfSixteenMilliseconds() {
        assertEquals(16, FxBatch.waitMillis(1_000_000L, 1_000_000L));
        assertEquals(11, FxBatch.waitMillis(6_000_000L, 1_000_000L));
        assertEquals(0, FxBatch.waitMillis(17_000_000L, 1_000_000L));
        assertEquals(0, FxBatch.waitMillis(900_000_000L, 1_000_000L));
        // A clock that went backwards waits for nothing.
        assertEquals(0, FxBatch.waitMillis(1_000_000L, 90_000_000L));
    }
}
