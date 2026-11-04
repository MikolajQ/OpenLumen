package com.openlumen.prefs

import com.google.common.truth.Truth.assertThat
import com.openlumen.engine.LumenMatrix
import org.junit.Test

/**
 * The custom colour carries a luma share ("Keep brightness contrast"), and a
 * preset can be copied into it. A copy is only honest if the screen does not
 * move, so most of these compare the rendered matrix before and after.
 */
class CustomColorTest {

    private fun on(key: String, intensity: Float = 1f, dim: Float = 0f) = Preferences(
        activePresetKey = key,
        presetIntensity = intensity,
        dim = dim,
        customMatrix = MatrixDto(r = 0.3f, g = 0.6f, b = 0.9f)
    )

    @Test fun `a custom colour at half luma renders as honey`() {
        val custom = on("custom").copy(
            customMatrix = MatrixDto(r = 1f, g = 0.55f, b = 0.10f, lumaShare = 0.5f)
        )
        assertSameLook(custom.effectiveMatrix(), on("honey").effectiveMatrix())
    }

    @Test fun `a custom colour with no luma share is unchanged`() {
        // Every stored custom colour decodes with a share of 0.
        val m = on("custom").effectiveMatrix()
        assertThat(m.hasColorMatrix).isFalse()
        assertThat(m.r).isWithin(1e-5f).of(0.3f)
    }

    @Test fun `editing a preset as custom keeps the screen as it was`() {
        for (key in listOf("ruby", "honey", "red", "amber", "gray", "deep", "pwm")) {
            for (intensity in listOf(1f, 0.6f)) {
                val before = on(key, intensity = intensity, dim = 0.1f)
                val after = before.withActivePresetAsCustom()

                assertThat(after.activePresetKey).isEqualTo(Preferences.CUSTOM_PRESET_KEY)
                assertThat(after.previousPresetKey).isEqualTo(key)
                if (key == "gray") {
                    // Rec. 709 against the tint's Rec. 601: the same idea,
                    // not the same numbers. White still stays white.
                    assertThat(after.effectiveMatrix().surfaceRgbMatrix().sum())
                        .isWithin(1e-4f).of(before.effectiveMatrix().surfaceRgbMatrix().sum())
                } else {
                    assertSameLook(after.effectiveMatrix(), before.effectiveMatrix())
                }
            }
        }
    }

    @Test fun `a preset's own dim moves into the dim setting`() {
        val after = on("deep", dim = 0f).withActivePresetAsCustom()
        assertThat(after.dim).isWithin(1e-6f).of(0.30f)
        assertThat(after.customMatrix.dim).isEqualTo(0f)
    }

    @Test fun `the copy is the preset's tint and share`() {
        val after = on("ruby").withActivePresetAsCustom()
        assertThat(after.customMatrix.r).isEqualTo(1f)
        assertThat(after.customMatrix.g).isEqualTo(0f)
        assertThat(after.customMatrix.b).isEqualTo(0.05f)
        assertThat(after.customMatrix.lumaShare).isEqualTo(1f)
    }

    @Test fun `a copy clears an explicit matrix an import brought`() {
        val imported = on("red").copy(
            customMatrix = MatrixDto(hasColorMatrix = true, matrixRg = 0.5f)
        )
        val after = imported.withActivePresetAsCustom()
        assertThat(after.customMatrix.hasColorMatrix).isFalse()
        assertThat(after.customMatrix.matrixRg).isEqualTo(0f)
        assertSameLook(after.effectiveMatrix(), imported.effectiveMatrix())
    }

    @Test fun `there is nothing to copy from off, custom or a colour-vision preset`() {
        for (key in listOf("off", "custom", "protan", "deutan", "tritan")) {
            val before = on(key)
            assertThat(before.withActivePresetAsCustom()).isEqualTo(before)
        }
    }

    @Test fun `the luma slider selects custom and clamps`() {
        assertThat(on("ruby").withCustomLumaShare(0.35f).activePresetKey)
            .isEqualTo(Preferences.CUSTOM_PRESET_KEY)
        assertThat(on("custom").withCustomLumaShare(0.35f).customMatrix.lumaShare)
            .isEqualTo(0.35f)
        assertThat(on("custom").withCustomLumaShare(3f).customMatrix.lumaShare).isEqualTo(1f)
        assertThat(on("custom").withCustomLumaShare(-1f).customMatrix.lumaShare).isEqualTo(0f)
        assertThat(on("custom").withCustomLumaShare(Float.NaN).customMatrix.lumaShare).isEqualTo(0f)
    }

    @Test fun `the luma slider owns the custom colour from then on`() {
        val imported = on("custom").copy(
            customMatrix = MatrixDto(r = 1f, g = 0.5f, b = 0.2f, hasColorMatrix = true, matrixRg = 0.5f)
        )
        val back = imported.withCustomLumaShare(0.4f).withCustomLumaShare(0f)
        assertThat(back.customMatrix.hasColorMatrix).isFalse()
        assertThat(back.effectiveMatrix().isDiagonalMatrix()).isTrue()
    }

    @Test fun `profiles carry the share`() {
        val withShare = on("custom").withCustomLumaShare(0.5f)
        val snapshot = Profiles.saveCurrentAs(withShare, "Warm")
            .savedProfiles.single().snapshot
        assertThat(snapshot.customMatrix.lumaShare).isEqualTo(0.5f)
    }

    private fun assertSameLook(actual: LumenMatrix, expected: LumenMatrix) {
        val a = actual.toSurfaceFlinger16()
        val e = expected.toSurfaceFlinger16()
        for (i in e.indices) assertThat(a[i]).isWithin(1e-5f).of(e[i])
        assertThat(actual.effectiveDim).isWithin(1e-5f).of(expected.effectiveDim)
        assertThat(actual.scalarRgb().toList().zip(expected.scalarRgb().toList())
            .all { (x, y) -> kotlin.math.abs(x - y) < 1e-5f }).isTrue()
    }
}
