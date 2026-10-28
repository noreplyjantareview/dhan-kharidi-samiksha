package com.vsk.attendance.face

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class FaceTemplateTest {
    private val dimensions = FaceTemplate.DIMENSIONS

    private fun axis(index: Int = 0, magnitude: Float = 1f): List<Float> =
        List(dimensions) { if (it == index) magnitude else 0f }

    private fun template(samples: List<List<Float>> = List(5) { axis() }) =
        FaceTemplate(samples = samples)

    /**
     * A print sitting `score` away from [axis] in the plane of two components. Real prints of one face
     * are this close to each other, which is what the consensus cases have to be built from.
     */
    private fun near(score: Float, index: Int = 1): List<Float> =
        List(dimensions) {
            when (it) { 0 -> score; index -> sqrt(1f - score * score); else -> 0f }
        }

    private fun changed(field: String, value: JsonElement): String =
        JsonObject(Json.parseToJsonElement(template().encode()).jsonObject + (field to value)).toString()

    @Test fun constantsDescribeV3MeshPrintContract() {
        assertEquals(3, template().v)
        assertEquals("mesh-print-v1", FaceTemplate.MODEL)
        assertEquals(78, dimensions)
        assertEquals(78, FaceMeshSignature.DIMENSIONS)
        assertEquals(5, FaceTemplate.SAMPLE_COUNT)
    }

    @Test fun roundTripIncludesVersionAndModelDefaults() {
        val original = template(List(5) { axis(it % dimensions) })
        val encoded = original.encode()
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(3), fields["v"])
        assertEquals(JsonPrimitive("mesh-print-v1"), fields["model"])
        assertEquals(setOf("v", "model", "samples"), fields.keys)
        assertEquals(original, FaceTemplate.decode(encoded))
        assertTrue(FaceTemplate.isCurrent(encoded))
    }

    @Test fun nullEmptyMalformedAndOversizedTemplatesAreRejected() {
        val invalid = listOf(null, "", " ", "null", "[]", "{}", "{", "not-json",
            "{\"v\":3,\"samples\":null}", template().encode() + "garbage", " ".repeat(40001))
        invalid.forEach {
            assertNull("Unexpected decode of ${it?.take(60)}", FaceTemplate.decode(it))
            assertFalse(FaceTemplate.isCurrent(it))
        }
    }

    @Test fun oldAndFutureVersionsAreRejected() {
        listOf(-1, 0, 1, 2, 4, Int.MAX_VALUE).forEach {
            assertNull(FaceTemplate.decode(changed("v", JsonPrimitive(it))))
        }
    }

    /**
     * The SFace registrations the previous build stored are the important rejection here: this build
     * must refuse them outright so an employee is told to re-enroll rather than matching against a
     * template measured in a completely different space.
     */
    @Test fun sfaceAndOtherModelTemplatesAreRejected() {
        listOf("", "sface", "SFACE-2021dec", "sface-2021dec", "mesh-print-v2", "legacy").forEach {
            assertNull(FaceTemplate.decode(changed("model", JsonPrimitive(it))))
        }
        assertNull(FaceTemplate.decode("{\"v\":2,\"descriptor\":[1,0,0]}"))
    }

    @Test fun unknownFieldsAreRejectedRatherThanSilentlyIgnored() {
        assertNull(FaceTemplate.decode(changed("unknown", JsonPrimitive(true))))
        assertNull(FaceTemplate.decode(changed("threshold", JsonPrimitive(0))))
    }

    @Test fun exactlyFiveSamplesAreRequired() {
        listOf(0, 1, 2, 3, 4, 6, 8).forEach { count ->
            assertNull("sample count $count", FaceTemplate.decode(template(List(count) { axis() }).encode()))
        }
        assertNotNull(FaceTemplate.decode(template().encode()))
    }

    @Test fun everySampleMustHaveTheMeshPrintLength() {
        listOf(0, 1, 64, 77, 79, 128, 256).forEach { count ->
            val invalid = List(count) { if (it == 0) 1f else 0f }
            val samples = List(5) { if (it == 3) invalid else axis() }
            assertNull("dimension $count", FaceTemplate.decode(template(samples).encode()))
        }
    }

    @Test fun everySampleMustBeUnitLengthWithinDocumentedTolerance() {
        listOf(0f, 0.1f, 0.979f, 1.021f, 2f, -2f).forEach { magnitude ->
            val samples = List(5) { if (it == 4) axis(magnitude = magnitude) else axis() }
            assertNull("norm $magnitude", FaceTemplate.decode(template(samples).encode()))
        }
        listOf(0.981f, 1f, 1.019f, -1f).forEach { magnitude ->
            assertNotNull(FaceTemplate.decode(template(List(5) { axis(magnitude = magnitude) }).encode()))
        }
    }

    @Test fun nonFiniteAndOverflowJsonCoordinatesAreRejected() {
        val encoded = template().encode()
        listOf("NaN", "Infinity", "-Infinity", "1e999", "-1e999").forEach { literal ->
            assertNull(literal, FaceTemplate.decode(encoded.replaceFirst("1.0", literal)))
        }
    }

    @Test fun normalizedProducesFiniteUnitVectorWithoutChangingInput() {
        val vector = List(dimensions) { if (it == 0) 3f else if (it == 1) 4f else 0f }
        val normalized = FaceTemplate.normalized(vector)!!
        assertEquals(0.6f, normalized[0], 0.00001f)
        assertEquals(0.8f, normalized[1], 0.00001f)
        assertEquals(1.0, sqrt(normalized.sumOf { it.toDouble() * it }), 0.000001)
        assertEquals(3f, vector[0], 0f)
    }

    @Test fun normalizationRejectsWrongSizeZeroNearZeroAndNonFiniteInput() {
        val vectors = listOf(emptyList(), List(dimensions - 1) { 1f }, List(dimensions + 1) { 1f },
            axis(magnitude = 0f), axis(magnitude = 0.0000001f),
            axis(magnitude = Float.NaN), axis(magnitude = Float.POSITIVE_INFINITY),
            axis(magnitude = Float.NEGATIVE_INFINITY))
        vectors.forEach { assertNull(FaceTemplate.normalized(it)) }
    }

    @Test fun normalizationHandlesVeryLargeFiniteInputWithoutOverflow() {
        val result = FaceTemplate.normalized(List(dimensions) { Float.MAX_VALUE })!!
        assertTrue(result.all { it.isFinite() })
        assertEquals(1.0, sqrt(result.sumOf { it.toDouble() * it }), 0.000001)
    }

    @Test fun identicalVectorsMatchButOrthogonalAndOppositeDoNot() {
        val decoded = FaceTemplate.decode(template().encode())!!
        assertTrue(decoded.matches(axis()))
        assertFalse(decoded.matches(axis(1)))
        assertFalse(decoded.matches(axis(magnitude = -1f)))
    }

    @Test fun matchRejectsInvalidProbeVectors() {
        listOf(emptyList(), List(dimensions - 1) { 0f }, List(dimensions + 1) { 0f }, axis(magnitude = 0f),
            axis(magnitude = 2f), axis(magnitude = Float.NaN),
            axis(magnitude = Float.POSITIVE_INFINITY)).forEach {
            assertFalse(template().matches(it))
        }
    }

    @Test fun matchingRequiresAtLeastTwoAgreeingSamplesEvenWithAlignedCentroid() {
        val a = axis()
        // Four prints a hair below the operating point, each leaning a different way so their average
        // still points straight at the probe: the centroid agrees, yet only one sample does.
        val almost = List(4) { near(FaceTemplate.MATCH_THRESHOLD - 0.0001f, index = it + 1) }
        assertFalse(template(listOf(a) + almost).matches(a))
        // Adding a second agreeing print is the only thing that flips it, so the consensus rule is
        // what rejected the set above - not the centroid.
        assertTrue(template(listOf(a, a) + almost.take(3)).matches(a))
    }

    @Test fun matchingTwoSamplesStillRequiresCentroidAgreement() {
        val a = axis()
        val opposite = axis(magnitude = -1f)
        assertFalse(template(listOf(a, a, opposite, opposite, opposite)).matches(a))
    }

    @Test fun matchingAllowsTwoSampleConsensusWithAlignedCentroid() {
        val a = axis()
        val b = near(FaceTemplate.MATCH_THRESHOLD + 0.0005f)
        val reference = template(listOf(a, a, b, b, b))
        // The template drifted onto b, but two prints still agree with either probe and the average
        // sits between them, so a drifted registration does not have to start again.
        assertTrue(reference.matches(a))
        assertTrue(reference.matches(b))
    }

    @Test fun bestScoreReportsTheClosestStoredSample() {
        val a = axis()
        val b = axis(1)
        val reference = template(listOf(a, b, b, a, b))
        assertEquals(1f, reference.bestScore(a), 0.00001f)
        assertEquals(1f, reference.bestScore(b), 0.00001f)
        assertEquals(-1f, reference.bestScore(axis(magnitude = 2f)), 0f)
    }

    @Test fun zeroCentroidDoesNotMatchEvenWithTwoAgreeingSamples() {
        fun vector(x: Float, y: Float) = List(dimensions) {
            when (it) { 0 -> x; 1 -> y; else -> 0f }
        }
        val samples = listOf(vector(0.8f, 0.6f), vector(0.8f, -0.6f),
            vector(-0.3f, sqrt(0.91f)), vector(-0.3f, -sqrt(0.91f)), axis(magnitude = -1f))
        assertNotNull(FaceTemplate.decode(template(samples).encode()))
        assertFalse(template(samples).matches(axis()))
    }

    @Test fun cosineOperatingPointRejectsBelowAndAcceptsAboveThreshold() {
        fun probe(score: Float) = List(dimensions) {
            when (it) { 0 -> score; 1 -> sqrt(1f - score * score); else -> 0f }
        }
        val near = 0.0001f
        assertFalse(template().matches(probe(FaceTemplate.MATCH_THRESHOLD - near)))
        assertTrue(template().matches(probe(FaceTemplate.MATCH_THRESHOLD + near)))
    }

    @Test fun geometryThresholdSitsFarAboveAnEmbeddingThreshold() {
        // Documents why the number is not SFace's 0.363: mesh prints of any two faces are close.
        assertTrue(FaceTemplate.MATCH_THRESHOLD > 0.9f)
    }

    @Test fun cosineHandlesKnownDirectionsAndInvalidDimensions() {
        assertEquals(1f, FaceTemplate.cosine(axis(), axis()), 0f)
        assertEquals(0f, FaceTemplate.cosine(axis(), axis(1)), 0f)
        assertEquals(-1f, FaceTemplate.cosine(axis(), axis(magnitude = -1f)), 0f)
        assertEquals(-1f, FaceTemplate.cosine(emptyList(), axis()), 0f)
        assertEquals(-1f, FaceTemplate.cosine(axis(), emptyList()), 0f)
    }
}
