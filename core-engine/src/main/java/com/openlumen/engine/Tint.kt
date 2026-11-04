package com.openlumen.engine

/**
 * A filter colour as the user sets it: the colour white turns into, and how
 * much of that colour is carried by each pixel's brightness instead of by its
 * own channels.
 *
 * At [lumaShare] 0 each channel is scaled on its own, the classic filter:
 * colours survive, but under a strong tint shapes that differ only in green
 * or blue merge, such as a white symbol on a red icon. At 1 every output
 * channel is the tint times the input's luma, a single-colour screen in which
 * light and dark stay apart. In between, row `c` of the matrix is
 * `t_c * ((1 - s) * e_c + s * luma)`, so white lands on the tint at any share.
 *
 * Luma uses the Rec. 601 weights, as CF.Lumen does. [Presets.RUBY] is a red
 * tint at a share of 1 and [Presets.HONEY] Amber's tint at 0.5.
 */
data class Tint(
    val r: Float,
    val g: Float,
    val b: Float,
    val lumaShare: Float = 0f
) {
    /**
     * The matrix this tint renders as. A share of 0 is a plain channel scale
     * with no colour matrix, so a scalar driver loses nothing and says so.
     * The scalar triplet is the tint either way: it is what a driver with no
     * cross-channel terms falls back to.
     */
    fun toMatrix(): LumenMatrix {
        val tr = r.unit(default = 1f)
        val tg = g.unit(default = 1f)
        val tb = b.unit(default = 1f)
        val s = lumaShare.unit(default = 0f)
        if (s <= 0f) return LumenMatrix(r = tr, g = tg, b = tb)
        val own = 1f - s
        return LumenMatrix(
            r = tr,
            g = tg,
            b = tb,
            hasColorMatrix = true,
            matrixRr = tr * (own + s * LUMA_R),
            matrixRg = tr * (s * LUMA_G),
            matrixRb = tr * (s * LUMA_B),
            matrixGr = tg * (s * LUMA_R),
            matrixGg = tg * (own + s * LUMA_G),
            matrixGb = tg * (s * LUMA_B),
            matrixBr = tb * (s * LUMA_R),
            matrixBg = tb * (s * LUMA_G),
            matrixBb = tb * (own + s * LUMA_B)
        )
    }

    private fun Float.unit(default: Float): Float =
        if (isFinite()) coerceIn(0f, 1f) else default

    companion object {
        /** Rec. 601 luma weights. */
        const val LUMA_R: Float = 0.299f
        const val LUMA_G: Float = 0.587f
        const val LUMA_B: Float = 0.114f
    }
}
