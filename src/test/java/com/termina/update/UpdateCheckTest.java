package com.termina.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The update check.
 *
 * <p>Worth testing carefully because both failure directions are silent. Say a release is older
 * than it is and nobody is ever told about it; say it is newer and everybody is told about a
 * release they already have, on every launch, with no way to tell it is wrong.
 */
class UpdateCheckTest {

    private static String release(String tag, boolean draft, boolean prerelease) {
        return """
                {
                  "tag_name": "%s",
                  "name": "Termina %s",
                  "html_url": "https://github.com/adriandeleon/Termina/releases/tag/%s",
                  "draft": %s,
                  "prerelease": %s,
                  "author": {"name": "someone", "html_url": "https://example.invalid"},
                  "assets": [{"name": "Termina.dmg", "url": "https://example.invalid/a"}]
                }
                """
                .formatted(tag, tag, tag, draft, prerelease);
    }

    @Test
    void aReleaseIsReadFromThePayload() {
        ReleaseInfo info = UpdateCheck.parseLatest(release("v0.2.0", false, false));
        assertEquals("0.2.0", info.version());
        assertTrue(info.url().endsWith("v0.2.0"));
    }

    @Test
    void nestedObjectsDoNotSupplyTheFields() {
        // The payload's author and assets carry their own "name" and "url". Scanning for the first
        // match rather than tracking depth picks up the wrong one, and the release page link then
        // points at an asset or a person.
        ReleaseInfo info = UpdateCheck.parseLatest(release("v1.0.0", false, false));
        assertEquals("Termina v1.0.0", info.name());
        assertFalse(info.url().contains("example.invalid"), info.url());
    }

    @Test
    void draftsAndPrereleasesAreNotOffered() {
        assertNull(UpdateCheck.parseLatest(release("v9.9.9", true, false)));
        assertNull(UpdateCheck.parseLatest(release("v9.9.9", false, true)));
    }

    @Test
    void unusablePayloadsGiveNothingRatherThanThrowing() {
        assertNull(UpdateCheck.parseLatest(null));
        assertNull(UpdateCheck.parseLatest(""));
        assertNull(UpdateCheck.parseLatest("not json at all"));
        assertNull(UpdateCheck.parseLatest("[]"));
        assertNull(UpdateCheck.parseLatest("{\"message\": \"Not Found\"}"));
    }

    @Test
    void versionsCompareNumericallyNotAsText() {
        // The one that matters: as strings "0.10.0" sorts below "0.9.0", so the tenth release would
        // never be offered to anyone on the ninth.
        assertTrue(UpdateCheck.isNewer("0.9.0", "0.10.0"));
        assertFalse(UpdateCheck.isNewer("0.10.0", "0.9.0"));
        assertTrue(UpdateCheck.isNewer("1.2.9", "1.3.0"));
    }

    @Test
    void theSameVersionIsNotAnUpdate() {
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.0"));
        assertFalse(UpdateCheck.isNewer("1.0.0", "v1.0.0"));
    }

    @Test
    void aSnapshotIsOlderThanTheReleaseItBecomes() {
        // Otherwise every development build reports itself as up to date against its own release.
        assertTrue(UpdateCheck.isNewer("0.1.0-SNAPSHOT", "0.1.0"));
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.1.0-SNAPSHOT"));
    }

    @Test
    void aReleaseCandidateSortsBelowItsRelease() {
        assertTrue(UpdateCheck.isNewer("1.0.0-rc1", "1.0.0"));
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.0-rc1"));
    }

    @Test
    void aShorterVersionIsPaddedRatherThanTreatedAsSmaller() {
        assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"));
        assertTrue(UpdateCheck.isNewer("1.0", "1.0.1"));
    }

    @Test
    void anUnknownCurrentVersionAcceptsAnything() {
        // AppInfo falls back to a blank version when build-info is missing; better to surface the
        // real latest than to go quiet.
        assertTrue(UpdateCheck.isNewer("", "0.1.0"));
        assertTrue(UpdateCheck.isNewer(null, "0.1.0"));
        assertFalse(UpdateCheck.isNewer("1.0.0", null));
        assertFalse(UpdateCheck.isNewer("1.0.0", ""));
    }

    @Test
    void aNonNumericSegmentDoesNotThrow() {
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.x.0"));
    }

    @Test
    void theCheckIsDueOncePerInterval() {
        long day = UpdateCheck.DEFAULT_INTERVAL_MS;
        assertTrue(UpdateCheck.isDue(0, 1_000_000, day), "never checked");
        assertFalse(UpdateCheck.isDue(1_000_000, 1_000_000 + day - 1, day));
        assertTrue(UpdateCheck.isDue(1_000_000, 1_000_000 + day, day));
    }

    @Test
    void aClockThatMovedBackwardsDoesNotDisableTheCheckForever() {
        // A stored timestamp in the future would otherwise hold it off until real time caught up.
        assertTrue(UpdateCheck.isDue(5_000_000, 1_000_000, UpdateCheck.DEFAULT_INTERVAL_MS));
    }
}
