package dev.sonora.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the player-reported position replaces the one the lyric pane is counting for itself.
 *
 * The pane runs a playhead on the frame clock and the player reports where it is a couple of times a
 * second. This rule is the line between the two: a report that the running playhead has already
 * passed is drift and is ignored, and one it could not have reached is a seek or a new track and is
 * taken. Getting it wrong in either direction is visible — too eager and the wipe moves in the
 * staircase the frame clock was there to remove; too reluctant and a seek does nothing for half a
 * second.
 */
class PlayheadAdoptionTest {

    @Test
    fun aReportThePlayheadHasPassedIsDrift() {
        // Half a second of polling, against a playhead counting in real time: the report is always
        // behind by up to one poll, and re-seeding from it on every poll is what made the wipe move
        // in visible steps.
        assertFalse(shouldAdoptReportedPosition(reportedMs = 30_000L, runningMs = 30_420L))
        assertFalse(shouldAdoptReportedPosition(reportedMs = 30_000L, runningMs = 30_500L))
    }

    @Test
    fun aReportAheadOfThePlayheadByLessThanTheToleranceIsDriftToo() {
        // Clock drift and a poll that lands early both look like this, and neither is worth acting
        // on: the difference is a fraction of a line of text.
        assertFalse(shouldAdoptReportedPosition(reportedMs = 30_200L, runningMs = 30_000L))
    }

    @Test
    fun aSeekIsAdoptedAtOnce() {
        // The listener dragged the scrubber. A playhead still counting from where it was would put
        // the words back where they were for another half second, which is long enough to look like
        // the tap did nothing.
        assertTrue(shouldAdoptReportedPosition(reportedMs = 214_000L, runningMs = 30_000L))
        assertTrue(shouldAdoptReportedPosition(reportedMs = 4_000L, runningMs = 210_000L))
    }

    @Test
    fun aTrackChangeIsAdoptedAtOnce() {
        // A new track starts at zero while the pane was counting two minutes into the last one.
        assertTrue(shouldAdoptReportedPosition(reportedMs = 0L, runningMs = 120_000L))
    }
}
