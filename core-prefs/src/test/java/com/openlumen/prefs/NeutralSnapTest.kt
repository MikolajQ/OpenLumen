package com.openlumen.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A continuous slider dropped back at "100%" lands a few thousandths off, and
 * a contrast a hair under 1.0 lifts black for every pixel on the screen.
 */
class NeutralSnapTest {

    @Test fun `a contrast a finger left near neutral keeps black at zero`() {
        // The value a real device carried after the slider was moved back.
        val m = Preferences(activePresetKey = "red", contrast = 0.995283f).effectiveMatrix()

        assertThat(m.biasR).isEqualTo(0f)
        assertThat(m.biasG).isEqualTo(0f)
        assertThat(m.biasB).isEqualTo(0f)
    }

    @Test fun `near-neutral gamma is neutral`() {
        val m = Preferences(
            activePresetKey = "red",
            customMatrix = MatrixDto(gammaR = 0.9976303f, gammaG = 1.0048232f, gammaB = 1.0023961f)
        ).effectiveMatrix()

        assertThat(floatArrayOf(m.gammaR, m.gammaG, m.gammaB).toList())
            .containsExactly(1f, 1f, 1f)
    }

    @Test fun `a deliberate setting is left alone`() {
        assertThat(Preferences.snapNeutral(0.9f)).isEqualTo(0.9f)
        assertThat(Preferences.snapNeutral(1.1f)).isEqualTo(1.1f)
        val m = Preferences(activePresetKey = "red", contrast = 0.9f).effectiveMatrix()
        assertThat(m.biasR).isWithin(1e-6f).of(0.05f)
    }
}
