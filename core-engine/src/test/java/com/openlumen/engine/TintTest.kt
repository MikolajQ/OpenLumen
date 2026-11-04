package com.openlumen.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "Keep brightness contrast" is one formula: row c of the matrix is
 * `t_c * ((1 - s) * e_c + s * luma)`. Ruby and Honey were written out by hand
 * before the formula had a name, so the first thing to pin is that building
 * them from it changed nothing on screen.
 */
class TintTest {

    private val luma = floatArrayOf(Tint.LUMA_R, Tint.LUMA_G, Tint.LUMA_B)

    @Test fun `ruby and honey are the matrices they were written out as`() {
        // The hand-written values from before Tint existed.
        val ruby = floatArrayOf(
            0.299f, 0.587f, 0.114f,
            0f, 0f, 0f,
            0.299f * 0.05f, 0.587f * 0.05f, 0.114f * 0.05f
        )
        val honey = floatArrayOf(
            0.6495f, 0.2935f, 0.057f,
            0.082225f, 0.436425f, 0.03135f,
            0.01495f, 0.02935f, 0.0557f
        )
        assertMatrix(Presets.RUBY.surfaceRgbMatrix(), ruby)
        assertMatrix(Presets.HONEY.surfaceRgbMatrix(), honey)
        // The scalar fallback is the tint.
        assertThat(floatArrayOf(Presets.RUBY.r, Presets.RUBY.g, Presets.RUBY.b).toList())
            .containsExactly(1f, 0f, 0.05f).inOrder()
        assertThat(floatArrayOf(Presets.HONEY.r, Presets.HONEY.g, Presets.HONEY.b).toList())
            .containsExactly(1f, 0.55f, 0.10f).inOrder()
    }

    @Test fun `a share of 0 is a plain channel scale with no matrix`() {
        // A scalar driver must not report an approximation for a tint that
        // has nothing to approximate.
        val m = Tint(1f, 0.1f, 0f).toMatrix()
        assertThat(m).isEqualTo(LumenMatrix(r = 1f, g = 0.1f, b = 0f))
        assertThat(m.requiredCapabilities()).doesNotContain(EngineCapability.COLOR_MATRIX)
    }

    @Test fun `white lands on the tint at any share`() {
        for (share in listOf(0f, 0.05f, 0.25f, 0.5f, 0.75f, 1f)) {
            val tint = Tint(1f, 0.55f, 0.1f, share)
            val m = tint.toMatrix().surfaceRgbMatrix()
            val t = floatArrayOf(tint.r, tint.g, tint.b)
            for (row in 0 until 3) {
                val white = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
                assertThat(white).isWithin(1e-5f).of(t[row])
            }
        }
    }

    @Test fun `a share of 1 is the tint times the input's luma`() {
        val m = Tint(0.9f, 0.4f, 0.2f, lumaShare = 1f).toMatrix().surfaceRgbMatrix()
        val t = floatArrayOf(0.9f, 0.4f, 0.2f)
        for (row in 0 until 3) for (col in 0 until 3) {
            assertThat(m[row * 3 + col]).isWithin(1e-6f).of(t[row] * luma[col])
        }
    }

    @Test fun `a share above 0 needs the colour matrix`() {
        val m = Tint(1f, 0.1f, 0f, lumaShare = 0.05f).toMatrix()
        assertThat(m.requiredCapabilities()).contains(EngineCapability.COLOR_MATRIX)
        assertThat(m.unsupportedBy(KcalCapabilities)).containsExactly(EngineCapability.COLOR_MATRIX)
    }

    @Test fun `out of range values are clamped`() {
        assertThat(Tint(1f, 1f, 1f, lumaShare = 2f).toMatrix())
            .isEqualTo(Tint(1f, 1f, 1f, lumaShare = 1f).toMatrix())
        assertThat(Tint(1f, 1f, 1f, lumaShare = Float.NaN).toMatrix())
            .isEqualTo(LumenMatrix.IDENTITY)
        assertThat(Tint(1.5f, -1f, Float.NaN).toMatrix())
            .isEqualTo(LumenMatrix(r = 1f, g = 0f, b = 1f))
    }

    @Test fun `every preset that tints says how`() {
        // A channel-scale preset is its own tint at a share of 0, so the Home
        // controls show the numbers that are on screen.
        for (entry in Presets.ALL) {
            val tint = entry.tint ?: continue
            if (tint.lumaShare == 0f) {
                assertThat(tint).isEqualTo(Tint(entry.matrix.r, entry.matrix.g, entry.matrix.b))
                assertThat(entry.matrix.hasColorMatrix).isFalse()
            }
        }
        val shares = Presets.ALL.associate { it.key to it.tint?.lumaShare }
        assertThat(shares["red"]).isEqualTo(0f)
        assertThat(shares["ruby"]).isEqualTo(1f)
        assertThat(shares["honey"]).isEqualTo(0.5f)
        assertThat(shares["gray"]).isEqualTo(1f)
        // Off and the colour-vision remaps have no tint to show.
        for (key in listOf("off", "protan", "deutan", "tritan")) {
            assertThat(shares[key]).isNull()
        }
    }

    private fun assertMatrix(actual: FloatArray, expected: FloatArray) {
        for (i in expected.indices) {
            assertThat(actual[i]).isWithin(1e-6f).of(expected[i])
        }
    }

    private companion object {
        val KcalCapabilities = com.openlumen.engine.engines.KcalEngine().capabilities
    }
}
