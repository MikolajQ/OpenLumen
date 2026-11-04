package com.openlumen.prefs

import com.openlumen.engine.Presets
import com.openlumen.engine.composeDim

/**
 * Select the custom colour with [share] of its tint carried by brightness
 * ("Keep brightness contrast"), as the Home slider sets it.
 *
 * Clears any explicit matrix an imported profile brought along: from here on
 * the slider is what the custom colour is, and a matrix nothing on screen
 * shows would come back as soon as the share returned to 0.
 */
fun Preferences.withCustomLumaShare(share: Float): Preferences = copy(
    activePresetKey = Preferences.CUSTOM_PRESET_KEY,
    customMatrix = customMatrix.withoutExplicitMatrix().copy(
        lumaShare = if (share.isFinite()) share.coerceIn(0f, 1f) else 0f
    )
)

/**
 * Copy the active preset's colour into the custom controls and select them,
 * so a preset such as Ruby can be tuned from where it is instead of rebuilt
 * by hand. The screen does not change: the tint and luma share are what the
 * preset is made of, and a preset's own dim, which custom colours do not
 * carry, moves into the Dim setting at the strength it was rendering at.
 *
 * The preset is kept as the previous one, so the undo action goes back to it.
 * Unchanged when the active preset has no tint (Off, custom, colour vision).
 */
fun Preferences.withActivePresetAsCustom(): Preferences {
    val entry = Presets.byKey(activePresetKey) ?: return this
    val tint = entry.tint ?: return this
    val presetDim = entry.matrix.withIntensity(presetIntensity.coerceIn(0f, 1f)).dim
    return copy(
        activePresetKey = Preferences.CUSTOM_PRESET_KEY,
        previousPresetKey = activePresetKey,
        customMatrix = customMatrix.withoutExplicitMatrix().copy(
            r = tint.r,
            g = tint.g,
            b = tint.b,
            lumaShare = tint.lumaShare
        ),
        dim = if (presetDim > 0f) composeDim(presetDim, dim) else dim
    )
}

private fun MatrixDto.withoutExplicitMatrix(): MatrixDto {
    val identity = MatrixDto()
    return copy(
        hasColorMatrix = false,
        matrixRr = identity.matrixRr,
        matrixRg = identity.matrixRg,
        matrixRb = identity.matrixRb,
        matrixGr = identity.matrixGr,
        matrixGg = identity.matrixGg,
        matrixGb = identity.matrixGb,
        matrixBr = identity.matrixBr,
        matrixBg = identity.matrixBg,
        matrixBb = identity.matrixBb
    )
}
