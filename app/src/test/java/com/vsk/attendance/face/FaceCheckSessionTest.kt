package com.vsk.attendance.face

import org.junit.Assert.*
import org.junit.Test

class FaceCheckSessionTest {
    private fun axis(index: Int = 0, magnitude: Float = 1f): List<Float> =
        List(128) { if (it == index) magnitude else 0f }

    private fun stored(): String = FaceTemplate(samples = List(5) { axis() }).encode()

    // Exercise the entire security matrix even when an earlier case fails.
    private fun <T> assertEveryCase(cases: List<T>, check: (T) -> Unit) {
        val failures = cases.mapIndexedNotNull { index, value ->
            runCatching { check(value) }.exceptionOrNull()?.let { "case $index: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private inner class Driver(val enrolling: Boolean = true, val turn: String = "left") {
        val startedAt = 1000L
        val session = FaceCheckSession(enrolling, if (enrolling) null else stored(), turn, startedAt)
        var now = startedAt - 350
        val turnYaw: Float get() = if (turn == "left") 0.30f else -0.30f

        fun frame(yaw: Float = 0f, vector: List<Float> = axis(), delta: Long = 350): FaceCheckProgress {
            now += delta
            return session.accept(vector, yaw, now)
        }

        fun reject(identityFailure: Boolean = false, delta: Long = 350, message: String = "Invalid frame"): FaceCheckProgress {
            now += delta
            return session.rejected(message, now, identityFailure)
        }

        fun enterFinalStage() {
            assertFalse(frame().complete)
            assertFalse(frame().complete)
            assertFalse(frame(turnYaw).complete)
            assertFalse(frame(turnYaw).complete)
        }

        fun finish(): FaceCheckProgress {
            assertFalse("First final frame cannot complete", frame().complete)
            assertFalse("Second final frame cannot complete", frame().complete)
            return frame().also { assertTrue("Third final frame must complete", it.complete) }
        }

        fun challenge(): FaceCheckProgress {
            enterFinalStage()
            return finish()
        }
    }

    @Test fun enrollmentRequiresTwoCenterTwoCorrectTurnAndThreeFinalFramesForBothDirections() {
        for (turn in listOf("left", "right")) {
            val driver = Driver(turn = turn)
            val first = driver.frame()
            assertEquals(1, first.accepted)
            assertFalse(first.complete)
            val second = driver.frame()
            assertEquals(2, second.accepted)
            assertTrue(second.message.contains(turn))
            assertFalse(driver.frame(driver.turnYaw).complete)
            assertFalse(driver.frame(driver.turnYaw).complete)
            val result = driver.finish()
            assertEquals(5, result.accepted)
            val template = FaceTemplate.decode(result.template)!!
            assertEquals(5, template.samples.size)
        }
    }

    @Test fun enrollmentStoresOnlyTwoInitialAndThreeFinalSamplesNotTurnSamples() {
        fun vector(offset: Float) = FaceTemplate.normalized(
            List(128) { if (it == 0) 1f else if (it == 1) offset else 0f }
        )!!
        val vectors = List(7) { vector(it * 0.05f) }
        val driver = Driver()
        val yaws = listOf(0f, 0f, driver.turnYaw, driver.turnYaw, 0f, 0f, 0f)
        val results = yaws.indices.map { driver.frame(yaws[it], vectors[it]) }
        assertTrue(results.last().complete)
        assertEquals(listOf(vectors[0], vectors[1], vectors[4], vectors[5], vectors[6]),
            FaceTemplate.decode(results.last().template)!!.samples)
    }

    @Test fun verificationAlsoRequiresThreeFinalFramesAndDoesNotEmitNewTemplate() {
        for (turn in listOf("left", "right")) {
            val driver = Driver(enrolling = false, turn = turn)
            driver.enterFinalStage()
            val result = driver.finish()
            assertEquals(5, result.accepted)
            assertNull(result.template)
        }
    }

    @Test fun verificationRequiresCurrentStoredTemplate() {
        for (invalid in listOf(null, "", "{}", stored().replace("\"v\":3", "\"v\":2"))) {
            assertThrows(IllegalArgumentException::class.java) {
                FaceCheckSession(false, invalid, "left", 0)
            }
        }
    }

    @Test fun challengeDirectionMustBeLeftOrRight() {
        for (turn in listOf("", "up", "LEFT", "random")) {
            assertThrows(IllegalArgumentException::class.java) {
                FaceCheckSession(true, null, turn, 0)
            }
        }
    }

    @Test fun centerFramesAloneCannotComplete() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            repeat(20) { assertFalse(driver.frame().complete) }
        }
    }

    @Test fun initialNonCenteredFaceDoesNotCount() {
        val driver = Driver()
        repeat(4) { assertEquals(0, driver.frame(0.30f).accepted) }
        assertNotNull(driver.challenge().template)
    }

    @Test fun wrongDirectionCannotCompleteButCorrectDirectionCanRecover() {
        for (turn in listOf("left", "right")) {
            val driver = Driver(turn = turn)
            driver.frame()
            driver.frame()
            repeat(4) { assertFalse(driver.frame(-driver.turnYaw).complete) }
            repeat(4) { assertFalse(driver.frame().complete) }
            driver.frame(driver.turnYaw)
            driver.frame(driver.turnYaw)
            driver.finish()
        }
    }

    @Test fun turnNeedsTwoConsecutiveInRangeFrames() {
        val driver = Driver()
        driver.frame()
        driver.frame()
        driver.frame(driver.turnYaw)
        driver.frame(-driver.turnYaw)
        driver.frame(driver.turnYaw)
        repeat(3) { assertFalse(driver.frame().complete) }
        driver.frame(driver.turnYaw)
        driver.frame(driver.turnYaw)
        driver.finish()
    }

    @Test fun turnTooSmallOrTooLargeCannotAdvance() {
        for (turn in listOf("left", "right")) {
            val driver = Driver(turn = turn)
            driver.frame()
            driver.frame()
            val sign = if (turn == "left") 1 else -1
            for (yaw in listOf(0.10f, 0.17f, 0.51f, 0.80f)) {
                repeat(3) { assertFalse(driver.frame(yaw * sign).complete) }
            }
            repeat(3) { assertFalse(driver.frame().complete) }
            driver.frame(driver.turnYaw)
            driver.frame(driver.turnYaw)
            driver.finish()
        }
    }

    @Test fun turnIsRelativeToInitialYawBaseline() {
        val driver = Driver()
        driver.frame(0.1f)
        driver.frame(0.1f)
        repeat(3) { assertFalse(driver.frame(0.25f).complete) }
        driver.frame(0.35f)
        driver.frame(0.35f)
        assertFalse(driver.frame(0.1f).complete)
        assertFalse(driver.frame(0.1f).complete)
        assertTrue(driver.frame(0.1f).complete)
    }

    @Test fun framesCloserThan350MillisecondsDoNotAdvanceAnyStage() {
        val driver = Driver()
        assertEquals(1, driver.frame().accepted)
        assertEquals(1, driver.frame(delta = 349).accepted)
        assertEquals(2, driver.frame(delta = 1).accepted)
        driver.frame(driver.turnYaw)
        driver.frame(driver.turnYaw, delta = 349)
        // A duplicate/too-soon turn cannot put us in the final stage.
        assertTrue(driver.frame(0f, delta = 0).message.contains("left"))
        driver.frame(driver.turnYaw, delta = 1)
        assertFalse(driver.frame().complete)
        assertFalse(driver.frame(delta = 349).complete)
        assertFalse(driver.frame(delta = 1).complete)
        assertFalse(driver.frame(delta = 349).complete)
        assertTrue(driver.frame(delta = 1).complete)
    }

    @Test fun repeatedTimestampsCannotAccumulateEvidence() {
        val driver = Driver()
        driver.frame()
        repeat(10) { assertEquals(1, driver.frame(delta = 0).accepted) }
        driver.frame()
        driver.frame(driver.turnYaw)
        driver.frame(driver.turnYaw)
        driver.finish()
    }

    @Test fun nonCenteredFinalFrameClearsFinalSampleStreak() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.enterFinalStage()
            driver.frame()
            driver.frame()
            val interrupted = driver.frame(driver.turnYaw)
            assertFalse(interrupted.complete)
            if (enrolling) assertEquals(2, interrupted.accepted)
            val result = driver.finish()
            if (enrolling) assertEquals(5, FaceTemplate.decode(result.template)!!.samples.size)
        }
    }

    @Test fun identitySwitchResetsEnrollmentAndCannotReuseOldChallenge() {
        val driver = Driver()
        driver.enterFinalStage()
        driver.frame()
        driver.frame()
        val failure = driver.frame(vector = axis(1))
        assertFalse(failure.complete)
        assertEquals(0, failure.accepted)
        repeat(3) { assertFalse(driver.frame(vector = axis(1)).complete) }
        driver.frame(driver.turnYaw, axis(1))
        driver.frame(driver.turnYaw, axis(1))
        assertFalse(driver.frame(vector = axis(1)).complete)
        assertFalse(driver.frame(vector = axis(1)).complete)
        val result = driver.frame(vector = axis(1))
        assertTrue(result.complete)
        assertTrue(FaceTemplate.decode(result.template)!!.samples.all { it == axis(1) })
    }

    @Test fun verificationRejectsDifferentIdentityAndRequiresFreshChallenge() {
        val driver = Driver(enrolling = false)
        driver.enterFinalStage()
        driver.frame()
        driver.frame()
        val failure = driver.frame(vector = axis(1))
        assertFalse(failure.complete)
        assertEquals(0, failure.accepted)
        driver.challenge()
    }

    @Test fun identitySwitchInsideSamplingWindowStillResetsEvidence() {
        assertEveryCase(listOf(true, false)) { enrolling ->
            val driver = Driver(enrolling)
            driver.enterFinalStage()
            driver.frame()
            driver.frame()
            val failure = driver.frame(vector = axis(1), delta = 100)
            assertFalse(failure.complete)
            val following = driver.frame(delta = 250)
            assertEquals("Identity changes must not be hidden by sampling throttling", 0, failure.accepted)
            assertFalse(following.complete)
        }
    }

    @Test fun verificationAnchorRejectsSwitchEvenIfBothVectorsMatchStoredConsensus() {
        val a = axis()
        val b = axis(1)
        val reference = FaceTemplate(samples = listOf(a, a, b, b, axis(2)))
        assertTrue(reference.matches(a))
        assertTrue(reference.matches(b))
        val session = FaceCheckSession(false, reference.encode(), "left", 0)
        session.accept(a, 0f, 0)
        session.accept(a, 0f, 350)
        session.accept(a, 0.3f, 700)
        session.accept(a, 0.3f, 1050)
        session.accept(a, 0f, 1400)
        session.accept(a, 0f, 1750)
        val result = session.accept(b, 0f, 2100)
        assertFalse(result.complete)
        assertEquals(0, result.accepted)
    }

    @Test fun noFaceMultipleFacesAndPadInterruptionsResetEveryPartialChallenge() {
        for (enrolling in listOf(true, false)) {
            for (message in listOf("No face", "Multiple faces", "PAD rejected")) {
                for (count in listOf(1, 4, 6)) {
                    val driver = Driver(enrolling)
                    val yaws = listOf(0f, 0f, driver.turnYaw, driver.turnYaw, 0f, 0f)
                    repeat(count) { driver.frame(yaws[it]) }
                    val rejected = driver.reject(identityFailure = true, delta = 50, message = message)
                    assertEquals(message, rejected.message)
                    assertEquals(0, rejected.accepted)
                    assertFalse(rejected.complete)
                    assertNull(rejected.template)
                    driver.challenge()
                }
            }
        }
    }

    @Test fun rejectedQualityFrameNeverAddsSamplesOrCompletes() {
        val driver = Driver()
        driver.enterFinalStage()
        driver.frame()
        val result = driver.reject(message = "Blurred frame")
        assertEquals(3, result.accepted)
        assertFalse(result.complete)
        assertNull(result.template)
    }

    @Test fun prolongedQualityRejectionsResetContinuity() {
        val driver = Driver()
        driver.enterFinalStage()
        driver.frame()
        driver.frame()
        repeat(5) { assertFalse(driver.reject(delta = 350).complete) }
        assertEquals(0, driver.reject(delta = 51).accepted)
        driver.challenge()
    }

    @Test fun continuityGapLongerThan1800MillisecondsRestartsChallenge() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.enterFinalStage()
            driver.frame()
            driver.frame()
            val next = driver.frame(delta = 1801)
            assertFalse(next.complete)
            assertEquals(1, next.accepted)
            driver.frame()
            driver.frame(driver.turnYaw)
            driver.frame(driver.turnYaw)
            driver.finish()
        }
    }

    @Test fun continuityGapOfExactly1800MillisecondsIsAllowed() {
        val driver = Driver()
        driver.enterFinalStage()
        driver.frame()
        driver.frame()
        assertTrue(driver.frame(delta = 1800).complete)
    }

    @Test fun timeoutCannotCompleteEvenWhenOnlyOneFrameRemains() {
        for (enrolling in listOf(true, false)) {
            val driver = Driver(enrolling)
            driver.now = driver.startedAt + 65000 - 2100
            driver.enterFinalStage()
            driver.frame()
            driver.frame()
            val timedOut = driver.frame(delta = 351)
            assertFalse(timedOut.complete)
            assertNull(timedOut.template)
            assertTrue(timedOut.message.contains("timed out", ignoreCase = true))
            repeat(8) { assertFalse(driver.frame().complete) }
        }
    }

    @Test fun invalidEnrollmentVectorsDoNotBecomeInitialSamples() {
        assertEveryCase(listOf(emptyList(), List(127) { 0f }, List(129) { 0f },
            axis(magnitude = 0f), axis(magnitude = 2f), axis(magnitude = Float.NaN),
            axis(magnitude = Float.POSITIVE_INFINITY))) { invalid ->
            val driver = Driver()
            val result = driver.frame(vector = invalid)
            assertFalse(result.complete)
            assertEquals("Invalid embedding must not be accepted", 0, result.accepted)
        }
    }

    @Test fun invalidFinalEmbeddingCannotProduceInvalidCompletedTemplate() {
        assertEveryCase(listOf(emptyList(), List(127) { 0f }, List(129) { 0f },
            axis(magnitude = 0f), axis(magnitude = 2f), axis(magnitude = Float.NaN),
            axis(magnitude = Float.POSITIVE_INFINITY))) { invalid ->
            val driver = Driver()
            driver.enterFinalStage()
            driver.frame()
            driver.frame()
            val result = driver.frame(vector = invalid)
            assertFalse("Invalid embedding must not finish enrollment", result.complete)
            assertNull(result.template)
        }
    }

    @Test fun invalidEmbeddingDuringVerificationResetsWithoutCompleting() {
        assertEveryCase(listOf(emptyList(), List(127) { 0f }, List(129) { 0f },
            axis(magnitude = 0f), axis(magnitude = 2f), axis(magnitude = Float.NaN),
            axis(magnitude = Float.POSITIVE_INFINITY))) { invalid ->
            val driver = Driver(enrolling = false)
            driver.enterFinalStage()
            driver.frame()
            driver.frame()
            val result = driver.frame(vector = invalid)
            assertFalse(result.complete)
            assertEquals(0, result.accepted)
            assertFalse(driver.frame().complete)
        }
    }

    @Test fun nonFiniteInitialYawCannotCountAsCenteredEvidence() {
        assertEveryCase(listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) { yaw ->
            val driver = Driver()
            val result = driver.frame(yaw)
            assertFalse(result.complete)
            assertEquals("Non-finite yaw must not count", 0, result.accepted)
        }
    }

    @Test fun nonFiniteFinalYawCannotCompleteEnrollmentOrVerification() {
        val cases = listOf(true, false).flatMap { enrolling ->
            listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).map { enrolling to it }
        }
        assertEveryCase(cases) { (enrolling, yaw) ->
            val driver = Driver(enrolling)
            driver.enterFinalStage()
            driver.frame()
            driver.frame()
            assertFalse("Non-finite yaw cannot complete", driver.frame(yaw).complete)
        }
    }
}
