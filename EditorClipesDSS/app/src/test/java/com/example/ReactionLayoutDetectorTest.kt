package com.example.domain.analyzer

import org.junit.Assert.*
import org.junit.Test

class ReactionLayoutDetectorTest {
    private fun face(second: Int, x: Float, y: Float, id: Int = 1) =
        FaceTrackPoint(
            timeUs = second * 1_000_000L, centerX = x, centerY = y,
            frameWidth = 1920, frameHeight = 1080,
            faceWidthRatio = 0.09f, faceHeightRatio = 0.14f,
            trackingId = id
        )

    @Test fun keepsPersistentUpperRightCameraDespiteManyBottomFaces() {
        val frames = (0..29).flatMap { t ->
            listOf(face(t, 0.86f, 0.14f), face(t, 0.15f, 0.85f, 7),
                   face(t, 0.25f, 0.80f, 8))
        }
        val layout = ReactionLayoutDetector().detect(frames)
        assertNotNull(layout)
        assertEquals("janela superior direita", layout!!.cameraPosition)
        assertTrue(layout.streamerCamera.top < 0.1f)
    }

    @Test fun rejectsFacesOutsideUpperWebcamCorners() {
        val frames = (0..29).map { t -> face(t, 0.18f, 0.81f) }
        assertNull(ReactionLayoutDetector().detect(frames))
    }

    @Test fun rejectsVeryShortIntermittentUpperCamera() {
        val frames = (0..29).map { t -> face(t, 0.50f, 0.51f) } +
            (0..5).map { t -> face(t, 0.13f, 0.13f) }
        assertNull(ReactionLayoutDetector().detect(frames))
    }
}
