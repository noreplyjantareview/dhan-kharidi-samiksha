package com.vsk.attendance.face

import org.junit.Assert.*
import org.junit.Test

/**
 * The blink sequence replaces the head turn, so every case drives the two entry points the analyzer
 * has: mesh guidance on every frame, and the shape print only when the eyes and the framing say the
 * frame is worth measuring.
 */
class FaceCheckSessionTest {
    private val dimensions = FaceTemplate.DIMENSIONS

    private fun axis(index: Int = 0, magnitude: Float = 1f): List<Float> =
        List(dimensions) { if (it == index) magnitude else 0f }

    private fun stored(): String = FaceTemplate(samples = List(5) { axis() }).encode()

    private fun vector(offset: Float): List<Float> =
        FaceTemplate.normalized(List(dimensions) { if (it == 0) 1f else if (it == 1) offset else 0f })!!

    /** A print [score] away from [axis], in the plane of components 0 and [index]. */
    private fun near(score: Float, index: Int = 1): List<Float> =
        List(dimensions) {
            when (it) { 0 -> score; index -> kotlin.math.sqrt(1f - score * score); else -> 0f }
        }

    /** The print exactly between two neighbours; the cosine to each is the square root of their own. */
    private fun bisector(a: List<Float>, b: List<Float>): List<Float> =
        FaceTemplate.normalized(List(dimensions) { a[it] + b[it] })!!

    // Exercise the whole security matrix even when an earlier case fails.
    private fun <T> assertEveryCase(cases: List<T>, check: (T) -> Unit) {
        val failures = cases.mapIndexedNotNull { index, value ->
            runCatching { check(value) }.exceptionOrNull()?.let { "case $index: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private inner class Driver(
        val enrolling: Boolean = true,
        template: String? = if (enrolling) null else stored()
    ) {
        val startedAt = 1000L
        val session = FaceCheckSession(enrolling, template, startedAt)
        var now = startedAt

        /** This person's resting eyelid ratio; the tracker learns the blink baseline from it. */
        val openRatio = 0.30f

        fun guide(
            open: Boolean = true,
            framing: FramingReason? = null,
            delta: Long = 350,
            expressionClosure: Float = 0f
        ): FaceCheckProgress {
            now += delta
            return session.onGuidance(
                FaceGuidance(if (open) openRatio else 0.02f, expressionClosure, framing), now
            )
        }

        /** One analyzer tick: guidance first, then the heavy models only if the session asks. */
        fun frame(
            yaw: Float = 0f,
            vector: List<Float> = axis(),
            delta: Long = 350,
            open: Boolean = true,
            framing: FramingReason? = null
        ): FaceCheckProgress {
            val seen = guide(open, framing, delta)
            return if (session.needsEvidence) session.onEvidence(vector, yaw, now) else seen
        }

        fun reject(
            failure: FaceInterruption = FaceInterruption.Quality,
            delta: Long = 350,
            message: FaceInstruction = FaceCopy.TOO_BLURRY
        ): FaceCheckProgress {
            now += delta
            return session.rejected(message, now, failure)
        }

        fun eyesShut(frames: Int = 2, spacing: Long = 150) {
            repeat(frames) { guide(open = false, delta = spacing) }
        }

        /** Close and reopen without claiming the unlock worked. */
        fun blinkAttempt(
            closedFrames: Int = 2,
            spacing: Long = 150,
            vector: List<Float> = axis()
        ): FaceCheckProgress {
            eyesShut(closedFrames, spacing)
            return frame(vector = vector, delta = spacing)
        }

        /** The two centred, open frames that build the identity anchor. */
        fun align(vector: List<Float> = axis()) {
            repeat(FaceCheckSession.ALIGN_SAMPLES) { assertFalse(frame(vector = vector).complete) }
            assertEquals(FaceStage.Blink, session.currentStage)
        }

        /** A natural blink, which must unlock post-blink sampling. */
        fun blink(
            closedFrames: Int = 2,
            spacing: Long = 150,
            vector: List<Float> = axis()
        ): FaceCheckProgress {
            val reopened = blinkAttempt(closedFrames, spacing, vector)
            assertEquals("A blink must unlock sampling", FaceStage.Capture, session.currentStage)
            assertFalse(reopened.complete)
            return reopened
        }

        /** Draw the post-blink burst to completion; the blink itself banked the first sample. */
        fun capture(vector: List<Float> = axis()): FaceCheckProgress {
            var last: FaceCheckProgress? = null
            var completed = false
            repeat(FaceCheckSession.FINAL_SAMPLES) {
                if (!completed) {
                    last = frame(vector = vector)
                    completed = last!!.complete
                }
            }
            assertTrue("Post-blink sampling did not complete in the expected frames", completed)
            return last!!
        }

        /** The whole check from a clean session. */
        fun challenge(vector: List<Float> = axis()): FaceCheckProgress {
            align(vector)
            blink(vector = vector)
            return capture(vector)
        }
    }

    @Test fun enrollmentNeedsTwoOpenFramesOneBlinkThenThreeSamples() {
        val driver = Driver()
        val first = driver.frame()
        assertEquals(1, first.accepted)
        assertEquals(FaceStage.Align, driver.session.currentStage)
        driver.align()
        // Without a blink the check waits, however long the employee stares at the camera.
        repeat(6) {
            val waiting = driver.frame()
            assertFalse(waiting.complete)
            assertEquals(2, waiting.accepted)
        }
        assertEquals(3, driver.blink().accepted)
        assertFalse(driver.frame().complete)
        val result = driver.frame()
        assertTrue(result.complete)
        assertEquals(5, result.accepted)
        assertEquals(5, FaceTemplate.decode(result.template)!!.samples.size)
    }

    @Test fun onlyOpenFramedEvidenceFramesEnterTheTemplate() {
        val kept = List(5) { vector(0.01f * (it + 1)) }
        val driver = Driver()
        assertEquals(1, driver.frame(vector = kept[0]).accepted)
        assertEquals(2, driver.frame(vector = kept[1]).accepted)
        // Shut eyes and an unframed face never reach recognition, so they cannot be stored.
        assertFalse(driver.guide(open = false, delta = 100).complete)
        assertFalse(driver.session.needsEvidence)
        assertFalse(driver.guide(framing = FramingReason.TooFar, delta = 100).complete)
        assertFalse(driver.session.needsEvidence)
        assertEquals(3, driver.blink(vector = kept[2]).accepted)
        assertFalse(driver.frame(vector = kept[3]).complete)
        val result = driver.frame(vector = kept[4])
        assertTrue(result.complete)
        assertEquals(kept, FaceTemplate.decode(result.template)!!.samples)
    }

    @Test fun verificationFollowsTheSameBlinkAndReturnsNoTemplate() {
        val driver = Driver(enrolling = false)
        driver.align()
        driver.blink()
        assertFalse(driver.frame().complete)
        val result = driver.frame()
        assertTrue(result.complete)
        assertEquals(5, result.accepted)
        assertNull(result.template)
    }

    @Test fun verificationRequiresCurrentStoredTemplate() {
        for (invalid in listOf(null, "", "{}", stored().replace("\"v\":3", "\"v\":2"))) {
            assertThrows(IllegalArgumentException::class.java) { FaceCheckSession(false, invalid, 0) }
        }
    }

    @Test fun alignmentFramesAloneCannotComplete() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            repeat(40) { assertFalse(driver.frame().complete) }
            assertEquals(FaceStage.Blink, driver.session.currentStage)
        }
    }

    @Test fun recognitionRunsOnlyWhileTheEyesAreOpenAndTheFaceIsFramed() {
        val driver = Driver()
        assertFalse("Nothing has been read yet", driver.session.needsEvidence)
        driver.frame()
        assertTrue(driver.session.needsEvidence)
        driver.eyesShut(1)
        assertFalse("A shut eye is not worth a recognition run", driver.session.needsEvidence)
        driver.frame(open = false)
        driver.frame(framing = FramingReason.TooFar)
        assertFalse("An unframed face is not worth a recognition run", driver.session.needsEvidence)
        assertFalse(driver.guide().complete)
        assertTrue("An open, framed face runs recognition again", driver.session.needsEvidence)
        assertEquals(2, driver.frame().accepted)
    }

    @Test fun blinkBeforeAlignmentIsFinishedDoesNotSkipToSampling() {
        val driver = Driver()
        val stray = driver.blinkAttempt()
        assertFalse(stray.complete)
        assertEquals(FaceStage.Align, driver.session.currentStage)
        assertEquals(1, stray.accepted)
        assertTrue(driver.challenge().complete)
    }

    @Test fun holdingTheEyesShutIsNotABlink() {
        val driver = Driver()
        driver.align()
        val squeezed = driver.blinkAttempt(closedFrames = 6, spacing = 300)
        assertFalse(squeezed.complete)
        assertEquals("A squeeze is not a live-face proof", FaceStage.Blink, driver.session.currentStage)
        assertEquals(2, squeezed.accepted)
        // Recovery: one natural blink and the check finishes normally.
        driver.blink()
        assertTrue(driver.capture().complete)
    }

    @Test fun oneSeenBlinkFrameIsEnoughEvenOnASlowPhone() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.align()
            // A phone that barely manages a frame every 800ms can catch only the closed frame.
            driver.blinkAttempt(closedFrames = 1, spacing = 800)
            assertEquals(FaceStage.Capture, driver.session.currentStage)
            assertTrue(driver.capture().complete)
        }
    }

    @Test fun postBlinkBurstThatDragsOnIsRestarted() {
        val driver = Driver()
        driver.align()
        driver.blink()
        assertEquals(4, driver.frame(delta = 2000).accepted)
        val expired = driver.frame(delta = 2100)
        assertFalse(expired.complete)
        assertEquals("Post-blink samples are discarded with the window", 2, expired.accepted)
        assertEquals(FaceStage.Blink, driver.session.currentStage)
        assertEquals(FaceCopy.BLINK_NOW, expired.message)
        driver.blink()
        val result = driver.capture()
        assertTrue(result.complete)
        assertEquals(5, FaceTemplate.decode(result.template)!!.samples.size)
    }

    @Test fun movingOutOfFrameAfterTheBlinkDiscardsOnlyPostBlinkSamples() {
        val driver = Driver()
        driver.align()
        driver.blink()
        assertEquals(4, driver.frame().accepted)
        val quiet = driver.frame(framing = FramingReason.TooFar)
        assertFalse(quiet.complete)
        assertEquals("A one-frame flicker must not start shouting", 4, quiet.accepted)
        assertEquals(FaceCopy.KEEP_LOOKING, quiet.message)
        val interrupted = driver.frame(framing = FramingReason.TooFar, delta = 700)
        assertEquals(FaceCopy.MOVE_CLOSER, interrupted.message)
        assertEquals(2, interrupted.accepted)
        assertEquals(FaceStage.Blink, driver.session.currentStage)
        driver.blink()
        val result = driver.capture()
        assertTrue(result.complete)
        assertEquals(5, FaceTemplate.decode(result.template)!!.samples.size)
    }

    @Test fun framingComplaintWaitsForTheFailureToSettle() {
        val driver = Driver()
        assertEquals(FaceCopy.LOOK, driver.frame(framing = FramingReason.TooFar).message)
        assertEquals(FaceCopy.LOOK, driver.frame(framing = FramingReason.Tilt, delta = 500).message)
        assertEquals(FaceCopy.UPRIGHT, driver.frame(framing = FramingReason.Tilt, delta = 200).message)
    }

    @Test fun wrongPersonCannotReuseTheBlinkTheyAlreadyGave() {
        val driver = Driver()
        driver.align()
        driver.blink()
        driver.frame()
        val switched = driver.frame(vector = axis(1))
        assertFalse(switched.complete)
        assertEquals(0, switched.accepted)
        assertEquals(FaceStage.Align, driver.session.currentStage)
        val result = driver.challenge(axis(1))
        assertTrue(result.complete)
        assertTrue(FaceTemplate.decode(result.template)!!.samples.all { it == axis(1) })
    }

    @Test fun verificationRejectsDifferentIdentityAndRequiresFreshChallenge() {
        val driver = Driver(enrolling = false)
        driver.align()
        driver.blink()
        driver.frame()
        val failure = driver.frame(vector = axis(1))
        assertFalse(failure.complete)
        assertEquals(0, failure.accepted)
        assertTrue(driver.challenge().complete)
    }

    @Test fun identityChangeIsNotHiddenByEvidenceThrottling() {
        assertEveryCase(listOf(true, false)) { enrolling ->
            val driver = Driver(enrolling)
            driver.align()
            driver.blink()
            driver.frame()
            val insideSpacing = driver.frame(vector = axis(1), delta = 100)
            assertEquals("Identity changes must not be swallowed by sampling throttling",
                0, insideSpacing.accepted)
            assertFalse(insideSpacing.complete)
        }
    }

    @Test fun storedTemplateConsensusCannotHideAnIdentitySwitch() {
        val a = axis()
        // A registration that drifted between two neighbouring prints: both are accepted on their own,
        // because two samples plus the average agree with each of them.
        val b = near(0.9985f)
        val middle = bisector(a, b)
        val reference = FaceTemplate(samples = listOf(a, b, middle, middle, middle))
        assertTrue(reference.matches(a))
        assertTrue(reference.matches(b))
        val driver = Driver(enrolling = false, template = reference.encode())
        driver.align(a)
        driver.blink(vector = a)
        val switched = driver.frame(vector = b)
        assertFalse(switched.complete)
        assertEquals(0, switched.accepted)
        assertEquals(FaceStage.Align, driver.session.currentStage)
    }

    @Test fun lookingAwayCannotAnchorTheCheck() {
        val driver = Driver()
        for (yaw in listOf(0.30f, -0.30f, 1.2f, -2f)) {
            val away = driver.frame(yaw = yaw)
            assertEquals(0, away.accepted)
            assertEquals(FaceCopy.LOOK, away.message)
        }
        assertEquals(1, driver.frame().accepted)
    }

    @Test fun nonFiniteYawResetsInsteadOfCounting() {
        for (yaw in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val driver = Driver()
            assertEquals(0, driver.frame(yaw = yaw).accepted)
            assertEquals("A usable frame after a broken one must still count",
                1, driver.frame().accepted)
        }
    }

    @Test fun invalidPrintsNeverBecomeSamplesOrATemplate() {
        assertEveryCase(
            listOf(emptyList(), List(dimensions - 1) { 0f }, List(dimensions + 1) { 0f },
                axis(magnitude = 0f), axis(magnitude = 2f), axis(magnitude = Float.NaN),
                axis(magnitude = Float.POSITIVE_INFINITY))
        ) { invalid ->
            for (enrolling in listOf(true, false)) {
                val driver = Driver(enrolling)
                driver.align()
                driver.blink()
                val result = driver.frame(vector = invalid)
                assertFalse("Invalid embedding must not finish a check", result.complete)
                assertNull(result.template)
                assertEquals(0, result.accepted)
                assertEquals(FaceStage.Align, driver.session.currentStage)
            }
        }
    }

    @Test fun framesCloserThanTheSpacingDoNotDoubleCount() {
        val driver = Driver()
        assertEquals(1, driver.frame().accepted)
        assertEquals(1, driver.frame(delta = 349).accepted)
        assertEquals(2, driver.frame(delta = 1).accepted)
        driver.blink()
        assertFalse(driver.frame(delta = 349).complete)
        assertFalse(driver.frame(delta = 1).complete)
        assertTrue(driver.frame(delta = 350).complete)
    }

    @Test fun repeatedTimestampsCannotAccumulateEvidence() {
        val driver = Driver()
        driver.frame()
        repeat(10) { assertEquals(1, driver.frame(delta = 0).accepted) }
        assertTrue(driver.challenge().complete)
    }

    @Test fun transientFaceLossKeepsTheBlinkAndTheSamples() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.align()
            driver.blink()
            repeat(FaceCheckSession.FACE_LOST_FRAMES - 1) {
                val stalled = driver.reject(FaceInterruption.FaceLost, message = FaceCopy.FACE_LOST)
                assertFalse(stalled.complete)
                assertNull(stalled.template)
                if (enrolling) assertEquals(3, stalled.accepted)
            }
            assertFalse(driver.frame().complete)
            assertTrue("Dropped frames must not restart the challenge", driver.frame().complete)
        }
    }

    @Test fun sustainedFaceLossRestartsTheWholeCheck() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.align()
            driver.blink()
            var last: FaceCheckProgress? = null
            repeat(FaceCheckSession.FACE_LOST_FRAMES) {
                last = driver.reject(FaceInterruption.FaceLost, message = FaceCopy.FACE_LOST)
            }
            assertEquals(0, last!!.accepted)
            assertEquals(FaceStage.Align, driver.session.currentStage)
            assertTrue(driver.challenge().complete)
        }
    }

    @Test fun anIdentityMismatchCarriesTheScoreThatFailed() {
        val reference = FaceTemplate(samples = List(5) { axis() })
        val stranger = Driver(enrolling = false, template = reference.encode())
        val mismatch = stranger.frame(vector = axis(1))
        assertEquals(FaceCopy.NO_MATCH, mismatch.message)
        assertEquals("A completely different print scores zero", 0f, mismatch.score, 0.00001f)
        val driver = Driver()
        driver.frame()
        val switched = driver.frame(vector = axis(1))
        assertEquals(FaceCopy.ONE_PERSON, switched.message)
        assertEquals(0f, switched.score, 0.00001f)
        // Only a comparison that ran carries a number.
        assertEquals(-1f, Driver().frame().score, 0f)
    }

    @Test fun rejectedQualityFrameNeverAddsSamplesOrCompletes() {
        val driver = Driver()
        driver.align()
        driver.blink()
        val result = driver.reject(message = FaceCopy.TOO_BLURRY)
        assertEquals(3, result.accepted)
        assertFalse(result.complete)
        assertNull(result.template)
    }

    @Test fun prolongedQualityRejectionsDoNotErodeContinuity() {
        assertEveryCase(listOf(true, false)) { enrolling ->
            val driver = Driver(enrolling)
            driver.align()
            driver.blink()
            // Real phones take several frames while the user settles; that must not restart anything.
            repeat(6) { assertFalse(driver.reject(delta = 400).complete) }
            assertTrue("Quality rejections must not restart a challenge in progress",
                driver.capture().complete)
        }
    }

    @Test fun continuityGapLongerThanPresenceGapRestartsTheCheck() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.align()
            driver.blink()
            driver.frame()
            val gone = driver.frame(delta = FaceCheckSession.PRESENCE_GAP_MS + 1)
            assertFalse(gone.complete)
            if (enrolling) assertEquals(1, gone.accepted)
            assertEquals(FaceStage.Align, driver.session.currentStage)
            assertTrue(driver.challenge().complete)
        }
    }

    @Test fun continuityGapOfExactlyThePresenceGapIsAllowed() {
        val driver = Driver()
        assertEquals(1, driver.frame().accepted)
        val held = driver.frame(delta = FaceCheckSession.PRESENCE_GAP_MS)
        assertEquals("A gap of exactly the limit is still the same person", 2, held.accepted)
        assertEquals(FaceStage.Blink, driver.session.currentStage)
    }

    @Test fun timeoutCannotCompleteEvenWhenOneFrameRemains() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.now = driver.startedAt + FaceCheckSession.TIMEOUT_MS - 1_500
            driver.align()
            driver.blink()
            assertFalse(driver.frame().complete)
            val timedOut = driver.frame(delta = 350)
            assertFalse(timedOut.complete)
            assertNull(timedOut.template)
            assertTrue(timedOut.message.english.contains("timed out", ignoreCase = true))
            repeat(8) { assertFalse(driver.frame().complete) }
        }
    }

    @Test fun aCompletedCheckReportsHowDecisiveTheBlinkWas() {
        val driver = Driver()
        assertTrue(driver.challenge().complete)
        assertTrue("Peak closure must clear the threshold by a real margin",
            driver.session.blinkMargin > 0.2f)
    }

    @Test fun aFinishedCheckKeepsReportingCompletion() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            val result = driver.challenge()
            assertTrue(result.complete)
            assertEquals(enrolling, result.template != null)
            // Later frames are the camera still running: completion is handed over once only.
            repeat(4) {
                val again = driver.frame()
                assertTrue(again.complete)
                assertEquals(FaceCopy.COMPLETE, again.message)
                assertNull(again.template)
            }
        }
    }

    @Test fun guidanceVocabularyIsAlwaysBilingualAndShort() {
        // Lines an employee has to act on while holding a phone: one breath each.
        val guidance = listOf(
            FaceCopy.LOOK, FaceCopy.BLINK_NOW, FaceCopy.KEEP_LOOKING, FaceCopy.COMPLETE,
            FaceCopy.TIMED_OUT, FaceCopy.UNREADABLE, FaceCopy.NO_MATCH, FaceCopy.ONE_PERSON,
            FaceCopy.FACE_LOST, FaceCopy.MOVE_CLOSER, FaceCopy.MOVE_BACK,
            FaceCopy.FACE_IN_FRAME, FaceCopy.UPRIGHT, FaceCopy.TOO_DARK,
            FaceCopy.TOO_BRIGHT, FaceCopy.FLAT_LIGHT, FaceCopy.TOO_BLURRY,
            FaceCopy.IDLE, FaceCopy.READY_TO_START, FaceCopy.SLOW
        )
        // Lines that end the check and go to a supervisor, so they may name the problem.
        val failures = listOf(
            FaceCopy.ENGINE_UNAVAILABLE, FaceCopy.CAMERA_FAILED, FaceCopy.MODEL_FAILED,
            FaceCopy.PROCESSING_FAILED
        )
        assertEveryCase(guidance + failures) { line ->
            assertTrue(line.english.isNotBlank())
            assertTrue(line.hindi.isNotBlank())
        }
        assertEveryCase(guidance) { line ->
            assertTrue("One readable line for a field employee: ${line.english}",
                line.english.length <= 52)
        }
    }

    @Test fun guidanceNeverAsksForAHeadMovement() {
        val driver = Driver()
        val seen = mutableListOf(
            driver.frame().message,
            driver.frame(framing = FramingReason.TooFar, delta = 900).message,
            driver.frame(framing = FramingReason.TooFar, delta = 900).message,
            driver.reject(FaceInterruption.Quality, message = FaceCopy.TOO_BLURRY).message,
            driver.challenge().message
        )
        assertEveryCase(seen) { line ->
            for (banned in listOf("turn", "tilt your head", "nod", "shake")) {
                assertFalse("Guidance must not ask for a head movement: ${line.english}",
                    line.english.contains(banned, ignoreCase = true))
            }
        }
    }
}
